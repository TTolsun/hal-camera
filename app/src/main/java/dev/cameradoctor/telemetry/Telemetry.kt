package dev.cameradoctor.telemetry

import android.hardware.camera2.*
import android.os.SystemClock
import android.os.Trace
import java.util.concurrent.ConcurrentHashMap

class Telemetry(val recorder: FlightRecorder) {
    val sessions = ConcurrentHashMap<String, Map<String, Any?>>()
    fun event(session: String, kind: String, values: Map<String, Any?> = emptyMap()) = recorder.record(session, kind, values = values)
    fun callback(sessionId: String, alive: () -> Boolean): CameraCaptureSession.CaptureCallback {
        val tracker = FrameTracker()
        return object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                if (!alive()) return
                recorder.record(sessionId, "capture_started", frameNumber, timestamp,
                    mapOf("requestTag" to request.tag?.toString(), "templateObservable" to false))
                recorder.record(sessionId, "request_observed", frameNumber, values = requestValues(request))
            }
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                if (!alive()) return
                Trace.beginSection("CD.result")
                try {
                    val sensor = result[CaptureResult.SENSOR_TIMESTAMP]
                    val stats = tracker.add(result.frameNumber, sensor)
                    recorder.record(sessionId, "capture_result", result.frameNumber, sensor, mapOf(
                        "ae" to result[CaptureResult.CONTROL_AE_STATE],
                        "af" to result[CaptureResult.CONTROL_AF_STATE],
                        "afMode" to (result[CaptureResult.CONTROL_AF_MODE] ?: request[CaptureRequest.CONTROL_AF_MODE]),
                        "awb" to result[CaptureResult.CONTROL_AWB_STATE],
                        "exposureNs" to result[CaptureResult.SENSOR_EXPOSURE_TIME],
                        "iso" to result[CaptureResult.SENSOR_SENSITIVITY],
                        "frameDurationNs" to result[CaptureResult.SENSOR_FRAME_DURATION],
                        "focusDiopters" to result[CaptureResult.LENS_FOCUS_DISTANCE],
                        "zoomRatio" to if (android.os.Build.VERSION.SDK_INT >= 30) result[CaptureResult.CONTROL_ZOOM_RATIO] else null,
                        "cropRegion" to result[CaptureResult.SCALER_CROP_REGION]?.toShortString(),
                        "intervalMs" to stats.intervalMs, "resultFps" to stats.fps,
                        "observedResultGap" to stats.resultGap, "requestTag" to request.tag?.toString()
                    ))
                } finally { Trace.endSection() }
            }
            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                if (alive()) recorder.record(sessionId, "capture_failed", failure.frameNumber,
                    values = mapOf("reason" to failure.reason, "imageCaptured" to failure.wasImageCaptured()))
            }
            override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: android.view.Surface, frameNumber: Long) {
                if (alive()) recorder.record(sessionId, "buffer_lost", frameNumber)
            }
        }
    }
    fun image(session: String, timestamp: Long, width: Int, height: Int, format: Int, stream: String) {
        recorder.record(session, "image_available", sensorNs = timestamp,
            values = mapOf("width" to width, "height" to height, "format" to format, "stream" to stream))
    }
    private fun requestValues(r: CaptureRequest): Map<String, Any?> = mapOf(
        "aeMode" to r[CaptureRequest.CONTROL_AE_MODE], "afMode" to r[CaptureRequest.CONTROL_AF_MODE],
        "awbMode" to r[CaptureRequest.CONTROL_AWB_MODE], "exposureNs" to r[CaptureRequest.SENSOR_EXPOSURE_TIME],
        "iso" to r[CaptureRequest.SENSOR_SENSITIVITY], "fpsRange" to r[CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE]?.toString(),
        "cropRegion" to r[CaptureRequest.SCALER_CROP_REGION]?.toShortString(), "requestTag" to r.tag?.toString(),
        "observation" to "request contents seen in onCaptureStarted; not request submission time"
    )
}

fun nowNs(): Long = SystemClock.elapsedRealtimeNanos()
fun stateName(axis: String, value: Int?): String {
    val names = when (axis) {
        "AE" -> listOf("INACTIVE", "SEARCHING", "CONVERGED", "LOCKED", "FLASH_REQUIRED", "PRECAPTURE")
        "AF" -> listOf("INACTIVE", "PASSIVE_SCAN", "PASSIVE_FOCUSED", "ACTIVE_SCAN", "FOCUSED_LOCKED", "NOT_FOCUSED_LOCKED", "PASSIVE_UNFOCUSED")
        else -> listOf("INACTIVE", "SEARCHING", "CONVERGED", "LOCKED")
    }
    return value?.let { names.getOrNull(it) ?: "UNKNOWN($it)" } ?: "UNAVAILABLE"
}
