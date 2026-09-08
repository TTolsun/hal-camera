package dev.cameradoctor.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import dev.cameradoctor.telemetry.Telemetry

interface CameraEngine {
    fun start()
    fun capture()
    /** Callback means this engine has relinquished its camera. */
    fun close(done: () -> Unit)
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
        "physicalCameraIds" to if (android.os.Build.VERSION.SDK_INT >= 28) c.physicalCameraIds.toList() else emptyList<String>(),
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
