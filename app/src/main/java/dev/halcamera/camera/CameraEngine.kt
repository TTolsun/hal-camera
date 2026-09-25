package dev.halcamera.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import dev.halcamera.telemetry.Telemetry

interface CameraEngine {
    fun start()
    fun capture()
    /** Requested zoom ratio. The effective ratio is only known from capture results. */
    fun setZoom(ratio: Float)
    /** Callback means this engine has relinquished its camera. */
    fun close(done: () -> Unit)
}

/**
 * The LIVE media surface an engine may offer beyond [CameraEngine]'s preview contract: gallery stills and
 * video recording. LIVE and the CLI check for this interface instead of a concrete engine class, so which
 * engine records is a capability of the instance, not a hard-coded type. Camera2Engine implements it;
 * CameraXEngine does not yet, and LIVE switches to Camera2 when a media action needs it.
 */
interface MediaCapture {
    /** True while a still pair or a recording is in flight; LIVE and the CLI report BUSY from it. */
    val mediaBusy: Boolean
    /** One YUV + JPEG still pair saved through MediaLibrary. [done] is called on the main thread. */
    fun capturePhoto(requestId: String, done: (Result<PhotoResult>) -> Unit)
    fun startRecording(audio: Boolean = true, started: () -> Unit = {}, done: ((Result<android.net.Uri>) -> Unit)? = null)
    fun stopRecording()
}

/** Zoom ratio range reported by the camera characteristics. Below API 30 only digital zoom >= 1x is available. */
fun zoomRange(manager: CameraManager, id: String): Pair<Float, Float> {
    val c = try { manager.getCameraCharacteristics(id) } catch (_: Exception) { return 1f to 1f }
    if (Build.VERSION.SDK_INT >= 30) c[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]?.let { return it.lower to it.upper }
    return 1f to (c[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM] ?: 1f)
}

/** Preset zoom buttons in the style of stock camera apps: ultra-wide, 1x, 2x, 3x and the longest tele step available. */
fun zoomPresets(range: Pair<Float, Float>): List<Float> {
    val (min, max) = range
    val steps = mutableListOf<Float>()
    if (min < 1f) steps += min
    steps += listOf(1f, 2f, 3f)
    steps += if (max >= 10f) 10f else 5f
    return steps.filter { it >= min - 0.001f && it <= max + 0.001f }.distinct().take(5)
}

fun describeCamera(manager: CameraManager, id: String): Map<String, Any?> {
    val c = manager.getCameraCharacteristics(id)
    val stream = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
    return mapOf(
        "cameraId" to id,
        "hardwareLevel" to c[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL],
        "sensorTimestampSource" to c[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE],
        "sensorTimestampComparableToElapsedRealtime" to (c[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE] == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME),
        "lensFacing" to c[CameraCharacteristics.LENS_FACING],
        "sensorOrientation" to c[CameraCharacteristics.SENSOR_ORIENTATION],
        "capabilities" to c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.toList(),
        "physicalCameraIds" to if (Build.VERSION.SDK_INT >= 28) c.physicalCameraIds.toList() else emptyList<String>(),
        "zoomRatioRange" to zoomRange(manager, id).let { "${it.first}..${it.second}" },
        "outputSizes" to stream?.outputFormats?.associate { format ->
            format.toString() to stream.getOutputSizes(format).map { "${it.width}x${it.height}" }
        },
        "fpsRanges" to c[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]?.map { it.toString() },
        "note" to "Individual format/size support does not guarantee multi-stream combination support."
    )
}

fun Telemetry.registerSession(session: String, engine: String, manager: CameraManager, cameraId: String) {
    sessions[session] = describeCamera(manager, cameraId) + mapOf("engine" to engine, "sessionId" to session)
    event(session, "open_requested", mapOf("engine" to engine, "cameraId" to cameraId))
}

/**
 * Exact stream sizes and fps range an engine must configure, taken from a BenchmarkProfile. When present the
 * engine never falls back to a smaller size: a benchmark run that silently measured 720p would be compared with
 * 1080p runs under the same profile id (METRICS.md 0.4, "no automatic fallback"). The preflight
 * (ProfileCompatibility) is what decides whether these sizes are available at all.
 */
data class StreamSpec(
    val preview: android.util.Size,
    val yuv: android.util.Size,
    val jpeg: android.util.Size,
    val fpsRange: android.util.Range<Int>?,
    /** Recording conditions of the RECORD stage; null for a profile without one. */
    val record: RecordSpec? = null
)

/**
 * Exactly how the benchmark recorder is configured (docs/PLAN-Recording-v0.1.md 4). Like [StreamSpec] these are
 * profile conditions, not preferences: an unavailable size or codec fails the recording cycle rather than being
 * swapped for something the device does support, because 3.x values measured under other conditions are not
 * comparable with the ones stored under the same profile id.
 */
data class RecordSpec(
    val size: android.util.Size,
    val codec: String,
    val bitrate: Int,
    val fps: Int,
    val audio: Boolean
) {
    companion object {
        /**
         * Request tag of the recording requests of one cycle. Defined here because both sides need it and must
         * agree: the engine sets it on the requests, and the benchmark domain filters events by it to tell a
         * recording frame from the preview frames before it. A second spelling would silently measure nothing.
         */
        fun tag(iteration: Int): String = "record-$iteration"

        /** Tag of the preview-only requests submitted while the recorder is being prepared. Never a 3.x sample. */
        fun prepareTag(iteration: Int): String = "record-prep-$iteration"
    }
}
