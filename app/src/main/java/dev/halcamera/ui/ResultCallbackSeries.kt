package dev.halcamera.ui

import dev.halcamera.telemetry.Event

/** Offsets share the previous frame's Start callback. Sensor timestamps are equality keys only. */
data class ResultCallbackPoint(val atNs: Long, val latencyMs: Double?, val startAtNs: Long? = null)
data class ResultCallbackTrack(val id: String, val label: String, val unavailable: String?, val points: List<ResultCallbackPoint>, val repeating: Boolean = true)
data class ResultCallbackFrame(val number: Long, val startAtNs: Long, val targets: Set<String>?)
data class ResultCallbackSeries(val tracks: List<ResultCallbackTrack>, val frames: List<ResultCallbackFrame> = emptyList()) {
    companion object {
        const val WINDOW_NS = 10_000_000_000L

        fun read(events: List<Event>, session: String, nowNs: Long, metadata: Map<String, Any?>): ResultCallbackSeries {
            val since = (metadata["callbackStreamsAtNs"] as? Number)?.toLong() ?: Long.MIN_VALUE
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
            val window = current.filter { it.atNs >= nowNs - WINDOW_NS }
            fun points(kind: String, stream: String? = null) = window.filter {
                it.kind == kind && (stream == null || it.values["stream"] == stream)
            }.sortedBy { it.atNs }.map { event ->
                val start = if (stream == null) event.frame?.let { starts[it] } else event.sensorNs?.let { sensorStarts[it] }
                val latency = start?.let { origins[it.atNs] }?.let { event.atNs - it }?.div(1e6)
                ResultCallbackPoint(event.atNs, latency, start?.atNs)
            }
            val tracks = mutableListOf(
                ResultCallbackTrack("start", "Shutter", null, points("capture_started")),
                ResultCallbackTrack("all", "Metadata", null, points("capture_result"))
            )
            val streams = (metadata["callbackStreams"] as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
            for (stream in streams) {
                val id = stream["id"] as? String ?: continue
                val label = stream["label"] as? String ?: id
                val unavailable = if (stream["observable"] == false) "No callback" else null
                val kind = stream["eventKind"] as? String ?: if (id == "preview") "preview_available" else "image_available"
                tracks += ResultCallbackTrack(id, label, unavailable, if (unavailable == null) points(kind, id) else emptyList(), stream["repeating"] != false)
            }
            return ResultCallbackSeries(tracks, starts.values.map { start ->
                ResultCallbackFrame(start.frame!!, start.atNs,
                    (start.values["streams"] as? List<*>)?.filterIsInstance<String>()?.toSet())
            }.sortedBy { it.startAtNs })
        }
    }
}
