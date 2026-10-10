package dev.halcamera.camera

import dev.halcamera.telemetry.Telemetry

/** Only the main service contributes to its graph; the added service has a separate frame timeline. */
internal class PipCallbacks(private val telemetry: Telemetry, private val session: String,
    private val outputs: PipOutputs, private val alive: () -> Boolean) {
    fun input(index: Int, timestamp: Long) {
        outputs.inputs.getOrNull(index)?.let { if (alive()) record(it.eventKind, it.id, timestamp) }
    }
    fun photo(timestamp: Long) { if (alive()) record(outputs.photo.eventKind, outputs.photo.id, timestamp) }
    private fun record(kind: String, stream: String, timestamp: Long) {
        telemetry.recorder.record(session, kind, sensorNs = timestamp, values = mapOf("stream" to stream))
    }
    fun <T> configure(configured: StreamConfiguration<T>) =
        telemetry.configureCallbackStreams(session, configured.metadata() + outputs.photo.metadata())
}
