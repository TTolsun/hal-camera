package dev.halcamera.camera

import dev.halcamera.telemetry.Telemetry

/** Only the main service contributes to its graph; the added service has a separate frame timeline. */
internal class PipCallbacks(private val telemetry: Telemetry, private val session: String,
    private val physicalId: String?, private val alive: () -> Boolean) {
    fun input(index: Int, timestamp: Long) {
        if (alive() && (index == 0 || physicalId != null)) record("pip_preview_available", if (index == 0) "preview" else "pip", timestamp)
    }
    fun photo(timestamp: Long) { if (alive()) record("pip_photo_available", "pip_photo", timestamp) }
    private fun record(kind: String, stream: String, timestamp: Long) {
        telemetry.recorder.record(session, kind, sensorNs = timestamp, values = mapOf("stream" to stream))
    }
    fun configure(analysis: List<Map<String, Any?>> = emptyList()) = telemetry.configureCallbackStreams(session, buildList {
        add(mapOf("id" to "preview", "label" to "Preview", "repeating" to true, "eventKind" to "pip_preview_available"))
        physicalId?.let { add(mapOf("id" to "pip", "label" to "Preview (Phy)", "kind" to "PREVIEW", "physicalId" to it,
            "repeating" to true, "eventKind" to "pip_preview_available")) }
        addAll(analysis)
        add(mapOf("id" to "pip_photo", "label" to "Jpeg", "repeating" to false, "eventKind" to "pip_photo_available"))
    })
}
