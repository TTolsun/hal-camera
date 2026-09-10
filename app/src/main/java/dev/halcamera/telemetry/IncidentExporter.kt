package dev.halcamera.telemetry

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class IncidentExporter(private val context: Context) {
    fun export(incident: Incident, sessions: Map<String, Map<String, Any?>>): File {
        val directory = File(context.filesDir, "incidents").apply { mkdirs() }
        val destination = File(directory, "${incident.id}.zip")
        val temp = File(directory, "${incident.id}.partial")
        val sessionIds = incident.events.map { it.session }.toSet()
        val selectedSessions = sessions.filterKeys { it in sessionIds }
        val byKind = incident.events.groupBy { it.kind }
        val results = byKind["capture_result"].orEmpty()
        val maxInterval = results.mapNotNull { (it.values["intervalMs"] as? Number)?.toDouble() }.maxOrNull()
        val summary = mapOf(
            "schemaVersion" to 1, "incidentId" to incident.id,
            "clock" to "elapsedRealtimeNanos", "nanosecondsEncoding" to "decimal string",
            "triggerNs" to incident.triggerNs.toString(),
            "requestedStartNs" to incident.requestedStartNs.toString(),
            "requestedEndNs" to incident.requestedEndNs.toString(),
            "finishedNs" to incident.finishedNs.toString(), "finishReason" to incident.finishReason,
            "observedStartNs" to incident.events.firstOrNull()?.atNs?.toString(),
            "observedEndNs" to incident.events.lastOrNull()?.atNs?.toString(),
            "preWindowObservedSeconds" to ((incident.triggerNs - (incident.events.firstOrNull()?.atNs ?: incident.triggerNs)).coerceAtLeast(0) / 1e9),
            "postWindowCompleted" to (incident.finishReason == "completed"),
            "ringCapacityEvictionsSinceAppStart" to incident.capacityEvictions,
            "incidentEventCapReached" to incident.incidentTruncated,
            "eventCount" to incident.events.size, "eventCounts" to byKind.mapValues { it.value.size },
            "maxObservedResultIntervalMs" to maxInterval,
            "halDroppedFrames" to null,
            "omitted" to mapOf("perfetto.trace" to "Engineering-mode collector is not included in MVP",
                "simpleperf.data" to "Engineering-mode collector is not included in MVP",
                "logcat.txt" to "External ADB collection required", "preview_thumbnail.jpg" to "MVP does not persist image pixels"),
            "measurementNotes" to listOf(
                "Callback observation time is not HAL execution time.",
                "Sensor timestamps stay in their own clock domain unless the device reports REALTIME.",
                "request_observed is emitted at onCaptureStarted, not at request submission.",
                "Analysis uses latest-image backpressure. Missing analysis images are not HAL frame drops.",
                "Compare engines only after checking negotiated streams and identical device conditions."
            )
        )
        try {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                fun entry(name: String, content: String) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                }
                entry("incident.json", json(summary).toString(2))
                entry("device.json", json(mapOf("manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL,
                    "sdk" to Build.VERSION.SDK_INT, "release" to Build.VERSION.RELEASE, "fingerprint" to Build.FINGERPRINT,
                    "appVersion" to "0.1.0", "exportedAtUtc" to utcNow())).toString(2))
                entry("camera_characteristics.json", json(selectedSessions).toString(2))
                entry("events.jsonl", incident.events.joinToString("\n", postfix = "\n") { eventJson(it).toString() })
                entry("capture_requests.jsonl", byKind["request_observed"].orEmpty().joinToString("\n") { eventJson(it).toString() })
                entry("capture_results.jsonl", results.joinToString("\n") { eventJson(it).toString() })
                entry("incident.md", """
                    # HAL Camera · ${incident.id}

                    - Finish: ${incident.finishReason}
                    - Events: ${incident.events.size}
                    - Largest observed sensor/result interval: ${maxInterval?.let { "%.2f ms".format(Locale.US, it) } ?: "unavailable"}
                    - Ring capacity evictions since app start: ${incident.capacityEvictions}
                    - Incident event cap reached: ${incident.incidentTruncated}

                    ## Interpretation
                    This bundle contains app-observed Camera2 metadata and callback events.
                    It does not measure HAL JPEG processing duration, displayed preview frame drops,
                    provider CPU usage, or identify a root cause. No AI diagnosis was run.
                    A result/image callback gap includes framework delivery and app scheduling.
                    Join result and image events by session + sensorNs where timestamps are available.
                    Nanosecond timestamps are strings to preserve precision in JavaScript tooling.
                    Request contents observed in onCaptureStarted are not a request-submission timestamp.
                    No photos or preview pixels are persisted. See incident.json for coverage and omitted artifacts.
                """.trimIndent())
            }
            check(temp.renameTo(destination)) { "Could not finalize incident ZIP" }
            return destination
        } catch (e: Exception) { temp.delete(); throw e }
    }
    private fun eventJson(e: Event) = json(mapOf("atNs" to e.atNs.toString(), "session" to e.session,
        "kind" to e.kind, "frame" to e.frame, "sensorNs" to e.sensorNs?.toString(), "values" to e.values))
    private fun json(map: Map<*, *>): JSONObject = JSONObject().also { obj -> map.forEach { (k, v) -> obj.put(k.toString(), value(v)) } }
    private fun value(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> json(v)
        is Iterable<*> -> JSONArray().also { a -> v.forEach { a.put(value(it)) } }
        is Double -> if (v.isFinite()) v else JSONObject.NULL
        is Float -> if (v.isFinite()) v else JSONObject.NULL
        else -> v
    }
    private fun utcNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}
