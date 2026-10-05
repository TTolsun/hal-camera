package dev.halcamera.telemetry

import android.hardware.camera2.*
import android.os.SystemClock
import android.os.Trace
import java.util.concurrent.ConcurrentHashMap

class Telemetry(val recorder: FlightRecorder) {
    val sessions = ConcurrentHashMap<String, Map<String, Any?>>()
    fun event(session: String, kind: String, values: Map<String, Any?> = emptyMap()) = recorder.record(session, kind, values = values)
    /** Snapshot the configured outputs, including outputs with no app-visible buffer callback. */
    fun configureCallbackStreams(session: String, outputs: List<Map<String, Any?>>) {
        val event = recorder.record(session, "callback_streams", values = mapOf("outputs" to outputs))
        sessions.computeIfPresent(session) { _, old -> old + mapOf("callbackStreams" to outputs, "callbackStreamsAtNs" to event.atNs) }
    }
    fun callback(sessionId: String, streams: (CaptureRequest) -> List<String>? = { null }, alive: () -> Boolean): CameraCaptureSession.CaptureCallback {
        val tracker = FrameTracker()
        return object : CameraCaptureSession.CaptureCallback() {
            private var previousStart: Event? = null
            @Synchronized
            override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                if (!alive()) return
                val configuredAt = (sessions[sessionId]?.get("callbackStreamsAtNs") as? Number)?.toLong() ?: Long.MIN_VALUE
                val previous = previousStart?.takeIf { it.atNs >= configuredAt }
                previousStart = recorder.record(sessionId, "capture_started", frameNumber, timestamp,
                    mapOf("requestTag" to request.tag?.toString(), "templateObservable" to false, "streams" to streams(request),
                        "previousStartAtNs" to previous?.atNs, "firstStart" to (previous == null)))
                recorder.record(sessionId, "request_observed", frameNumber, values = requestValues(request))
            }
            override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
                if (alive()) recorder.record(sessionId, "capture_partial", partialResult.frameNumber,
                    partialResult[CaptureResult.SENSOR_TIMESTAMP], mapOf("requestTag" to request.tag?.toString()))
            }
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                if (!alive()) return
                Trace.beginSection("CD.result")
                try {
                    val sensor = result[CaptureResult.SENSOR_TIMESTAMP]
                    val stats = tracker.add(result.frameNumber, sensor)
                    recorder.record(sessionId, "capture_result", result.frameNumber, sensor, mapOf(
                        "partialResultsCount" to result.partialResults.size,
                        "captureIntent" to request[CaptureRequest.CONTROL_CAPTURE_INTENT],
                        "ae" to result[CaptureResult.CONTROL_AE_STATE],
                        "af" to result[CaptureResult.CONTROL_AF_STATE],
                        "afMode" to (result[CaptureResult.CONTROL_AF_MODE] ?: request[CaptureRequest.CONTROL_AF_MODE]),
                        "awb" to result[CaptureResult.CONTROL_AWB_STATE],
                        "aeMode" to result[CaptureResult.CONTROL_AE_MODE],
                        "awbMode" to result[CaptureResult.CONTROL_AWB_MODE],
                        "colorGains" to result[CaptureResult.COLOR_CORRECTION_GAINS]?.let { listOf(it.red, it.greenEven, it.greenOdd, it.blue) },
                        "colorTransform" to result[CaptureResult.COLOR_CORRECTION_TRANSFORM]?.let { m -> List(9) { m.getElement(it % 3, it / 3).toFloat() } },
                        "exposureNs" to result[CaptureResult.SENSOR_EXPOSURE_TIME],
                        "iso" to result[CaptureResult.SENSOR_SENSITIVITY],
                        "frameDurationNs" to result[CaptureResult.SENSOR_FRAME_DURATION],
                        "fpsRange" to result[CaptureResult.CONTROL_AE_TARGET_FPS_RANGE]?.toString(),
                        "focusDiopters" to result[CaptureResult.LENS_FOCUS_DISTANCE],
                        "zoomRatio" to if (android.os.Build.VERSION.SDK_INT >= 30) result[CaptureResult.CONTROL_ZOOM_RATIO] else null,
                        "cropRegion" to result[CaptureResult.SCALER_CROP_REGION]?.toShortString(),
                        "opticalStabilization" to result[CaptureResult.LENS_OPTICAL_STABILIZATION_MODE],
                        "videoStabilization" to result[CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE],
                        // What LIVE's EV, lock and flash requests became; the request side is in request_observed.
                        "aeLock" to result[CaptureResult.CONTROL_AE_LOCK],
                        "evApplied" to result[CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION],
                        "flashState" to result[CaptureResult.FLASH_STATE],
                        "afRegions" to result[CaptureResult.CONTROL_AF_REGIONS]?.joinToString { it.rect.toShortString() },
                        "aeRegions" to result[CaptureResult.CONTROL_AE_REGIONS]?.joinToString { it.rect.toShortString() },
                        // Public only on API 29+ and only for logical cameras; null means the lens is not known.
                        "physicalId" to if (android.os.Build.VERSION.SDK_INT >= 29) result[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID] else null,
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
        "frameDurationNs" to r[CaptureRequest.SENSOR_FRAME_DURATION], "focusDiopters" to r[CaptureRequest.LENS_FOCUS_DISTANCE],
        "colorGains" to r[CaptureRequest.COLOR_CORRECTION_GAINS]?.let { listOf(it.red, it.greenEven, it.greenOdd, it.blue) },
        "colorTransform" to r[CaptureRequest.COLOR_CORRECTION_TRANSFORM]?.let { m -> List(9) { m.getElement(it % 3, it / 3).toFloat() } },
        "cropRegion" to r[CaptureRequest.SCALER_CROP_REGION]?.toShortString(), "requestTag" to r.tag?.toString(),
        "opticalStabilization" to r[CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE],
        "videoStabilization" to r[CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE],
        "zoomRatio" to if (android.os.Build.VERSION.SDK_INT >= 30) r[CaptureRequest.CONTROL_ZOOM_RATIO] else null,
        "aeLock" to r[CaptureRequest.CONTROL_AE_LOCK], "evIndex" to r[CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION],
        "flashMode" to r[CaptureRequest.FLASH_MODE], "afTrigger" to r[CaptureRequest.CONTROL_AF_TRIGGER],
        "aePrecaptureTrigger" to r[CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER],
        "afRegions" to r[CaptureRequest.CONTROL_AF_REGIONS]?.joinToString { it.rect.toShortString() },
        "aeRegions" to r[CaptureRequest.CONTROL_AE_REGIONS]?.joinToString { it.rect.toShortString() },
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
