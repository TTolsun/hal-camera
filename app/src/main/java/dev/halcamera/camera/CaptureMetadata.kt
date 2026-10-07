package dev.halcamera.camera

import dev.halcamera.telemetry.Telemetry

/** Never borrow a neighbouring preview's exposure when CameraX does not expose the image's result. */
internal fun captureMetadata(telemetry: Telemetry, session: String, timestamp: Long, requestId: String?): Map<String, Any?> {
    val result = telemetry.recorder.snapshot().lastOrNull {
        it.session == session && it.kind == "capture_result" && it.sensorNs == timestamp
    }
    val values = result?.values.orEmpty()
    return mapOf("requestId" to requestId, "sensorTimestampNs" to timestamp,
        "cameraId" to telemetry.sessions[session]?.get("cameraId"),
        "engine" to telemetry.sessions[session]?.get("engine"),
        "sensorTimestampSource" to telemetry.sessions[session]?.get("sensorTimestampSource"),
        "resultStatus" to if (result == null) "unavailable" else "matched",
        "frameNumber" to result?.frame,
        "exposureTimeNs" to values["exposureNs"], "sensitivityIso" to values["iso"],
        "frameDurationNs" to values["frameDurationNs"], "aeState" to values["ae"],
        "captureResult" to values)
}
