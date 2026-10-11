package dev.halcamera.camera

import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.view.Surface
import dev.halcamera.telemetry.Telemetry

/** Independent service frame numbers and timestamps must never overwrite the main device's timeline. */
internal class PipServiceTelemetry(private val telemetry: Telemetry, parentSession: String,
    manager: CameraManager, cameraId: String, engine: String, private val alive: () -> Boolean) {
    val sessionId = "$parentSession:pip:${java.util.UUID.randomUUID()}"
    private val requests = RequestStreamRegistry<CaptureRequest>()
    val callback = telemetry.callback(sessionId, streams = { request ->
        requests[request]
    }, alive = alive)
    init { telemetry.registerSession(sessionId, engine, manager, cameraId) }
    fun configure(outputs: StreamConfiguration<Surface>) {
        describe(outputs.metadata())
    }
    fun describe(outputs: List<Map<String, Any?>>) = telemetry.configureCallbackStreams(sessionId, outputs)
    fun track(request: CaptureRequest, streams: List<String>): CaptureRequest = requests.track(request, streams)
    fun preview(timestamp: Long) {
        if (alive()) telemetry.recorder.record(sessionId, "pip_preview_available", sensorNs = timestamp,
            values = mapOf("stream" to "preview"))
    }
    fun close() = telemetry.event(sessionId, "closed")
}
