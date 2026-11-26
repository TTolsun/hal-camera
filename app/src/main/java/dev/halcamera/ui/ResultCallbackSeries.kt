package dev.halcamera.ui

import dev.halcamera.camera.DualOutputs
import dev.halcamera.telemetry.Event

/** Offsets share the previous frame's Start callback. Sensor timestamps are equality keys only. */
data class ResultCallbackPoint(val atNs: Long, val latencyMs: Double?, val startAtNs: Long? = null)
data class ResultCallbackTrack(val id: String, val label: String, val unavailable: String?, val points: List<ResultCallbackPoint>, val repeating: Boolean = true)
data class ResultCallbackFrame(val number: Long, val startAtNs: Long, val targets: Set<String>?,
    val configuredOutputs: Set<String>? = null)
data class ResultCallbackSeries(val tracks: List<ResultCallbackTrack>, val frames: List<ResultCallbackFrame> = emptyList(),
    val currentOutputs: Set<String>? = null, val currentSinceNs: Long = Long.MIN_VALUE,
    val configurations: Collection<Set<String>> = emptyList()) {
    companion object {
        const val WINDOW_NS = 10_000_000_000L

        fun read(events: List<Event>, session: String, nowNs: Long, metadata: Map<String, Any?>): ResultCallbackSeries {
            val configuredAt = (metadata["callbackStreamsAtNs"] as? Number)?.toLong() ?: Long.MIN_VALUE
            val since = (metadata["callbackTimelineAtNs"] as? Number)?.toLong() ?: configuredAt
            val current = events.filter { it.session == session && it.atNs in since..nowNs }
            val starts = current.filter { it.kind == "capture_started" && it.frame != null }.associateBy { it.frame }
            // Persisted references survive ring eviction and opening the overlay mid-session.
            // Older events can use a retained predecessor, but missing history must never become a false zero.
            val orderedStarts = starts.values.sortedBy { it.atNs }
            val origins = orderedStarts.mapIndexed { index, start ->
                start.atNs to when {
                    start.values["firstStart"] == true -> start.atNs
                    start.values["previousStartAtNs"] is Number ->
                        (start.values["previousStartAtNs"] as Number).toLong().takeIf { it >= since }
                    else -> orderedStarts.getOrNull(index - 1)?.atNs
                }
            }.toMap()
            val sensorStarts = current.filter { it.kind == "capture_started" && it.sensorNs != null }.associateBy { it.sensorNs }
            val physicalIds = (metadata["physicalIds"] as? List<*>)?.filterIsInstance<String>().orEmpty()
            fun descriptors(value: Any?) = (value as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
            val active = descriptors(metadata["callbackStreams"])
            val layouts = (metadata["frameConfigurations"] as? Map<*, *>)?.mapValues { descriptors(it.value) }.orEmpty()
            val outputIds = layouts.mapValues { (_, rows) -> rows.mapNotNull { it["id"] as? String }.toSet() }
            val streams = if (layouts.isEmpty()) active else (active + layouts.values.flatten()).distinctBy { it["id"] }
            // Keep conflicting results ambiguous, including when one frame's shutter has been evicted.
            val physicalFrames = mutableMapOf<Pair<String, Long>, Long?>()
            for (event in current) {
                if (event.kind != "capture_result" && event.kind != "dual_physical_result") continue
                val frame = event.frame ?: continue
                val times = (event.values["physicalTimestamps"] ?: event.values["timestamps"]) as? Map<*, *> ?: continue
                for ((id, value) in times) {
                    if (id !is String || value !is Number) continue
                    val key = id to value.toLong()
                    physicalFrames[key] = if (key in physicalFrames && physicalFrames[key] != frame) null else frame
                }
            }
            // DualStillPair accepts the logical result timestamp on either physical reader. Keep
            // that fallback scoped to the actual two-output still request, never a preview frame.
            val dualPhotoStarts = current.filter { it.kind == "capture_result" && it.sensorNs != null }
                .mapNotNull { result ->
                    starts[result.frame]?.takeIf { it.values["requestTag"] == "dual_photo" }?.let { result.sensorNs to it }
                }.toMap()
            val window = current.filter { it.atNs >= nowNs - WINDOW_NS }
            fun points(kind: String, stream: String? = null, configuredId: String? = null) = window.filter {
                it.kind == kind && (stream == null || it.values["stream"] == stream)
            }.sortedBy { it.atNs }.mapNotNull { event ->
                val physicalId = configuredId ?: when (stream) {
                    "preview_main", "photo_main" -> physicalIds.getOrNull(0)
                    "preview_sub", "photo_sub" -> physicalIds.getOrNull(1)
                    else -> null
                }
                val start = if (kind == DualOutputs.GL_INPUT) {
                    val timestamp = event.sensorNs ?: return@mapNotNull null
                    if (timestamp <= 0 || configuredId == null || event.values["physicalId"] != configuredId) return@mapNotNull null
                    starts[physicalFrames[configuredId to timestamp]] ?: return@mapNotNull null
                } else if (stream == null) event.frame?.let { starts[it] }
                    else event.sensorNs?.let { timestamp ->
                        if (physicalId == null) sensorStarts[timestamp]
                        else starts[physicalFrames[physicalId to timestamp]] ?: when {
                            kind == "pip_preview_available" -> sensorStarts[timestamp]
                            stream == "photo_main" || stream == "photo_sub" -> dualPhotoStarts[timestamp]
                            else -> null
                        }
                    }
                val latency = start?.let { origins[it.atNs] }?.let { event.atNs - it }?.div(1e6)
                ResultCallbackPoint(event.atNs, latency, start?.atNs)
            }
            val tracks = mutableListOf(
                ResultCallbackTrack("start", "Shutter", null, points("capture_started")),
                ResultCallbackTrack("all", "Metadata", null, points("capture_result"))
            )
            val physical = window.filter { it.kind == "capture_result" }.flatMap { event ->
                (event.values["physicalTimestamps"] as? Map<*, *>)?.keys.orEmpty().filterIsInstance<String>()
            }.distinct().sortedWith(compareBy<String> { it.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it })
            for (id in physical) {
                val results = window.filter { it.kind == "capture_result" &&
                    (it.values["physicalTimestamps"] as? Map<*, *>)?.containsKey(id) == true }
                tracks += ResultCallbackTrack("metadata:$id", "Meta (Phy)", null, results.map { event ->
                    val start = starts[event.frame]
                    ResultCallbackPoint(event.atNs, start?.let { origins[it.atNs] }?.let { (event.atNs - it) / 1e6 }, start?.atNs)
                })
            }
            for (stream in streams) {
                val id = stream["id"] as? String ?: continue
                val label = stream["label"] as? String ?: id
                val unavailable = if (stream["observable"] == false) "No callback" else null
                val kind = stream["eventKind"] as? String ?: if (id == "preview") "preview_available" else "image_available"
                val arrivals = if (unavailable == null) points(kind, id, stream["physicalId"] as? String) else emptyList()
                tracks += ResultCallbackTrack(id, label, unavailable, arrivals, stream["repeating"] != false)
            }
            return ResultCallbackSeries(tracks, starts.values.map { start ->
                ResultCallbackFrame(start.frame!!, start.atNs,
                    (start.values["streams"] as? List<*>)?.filterIsInstance<String>()?.toSet(),
                    outputIds[start.values["frameConfiguration"]])
            }.sortedBy { it.startAtNs }, outputIds[metadata["frameConfiguration"]], configuredAt, outputIds.values)
        }
    }
}
