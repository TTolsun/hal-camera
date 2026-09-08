package dev.cameradoctor.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import dev.cameradoctor.telemetry.Telemetry

interface CameraEngine {
    fun start()
    fun capture()
    /** Requested zoom ratio. The effective ratio is only known from capture results. */
    fun setZoom(ratio: Float)
    /** Callback means this engine has relinquished its camera. */
    fun close(done: () -> Unit)
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
