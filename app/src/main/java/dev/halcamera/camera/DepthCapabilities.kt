package dev.halcamera.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics

/** Read each declaration independently so a missing vendor key never becomes a false support claim. */
fun depthSupport(c: CameraCharacteristics): DepthSupport {
    val errors = mutableListOf<String>()
    fun <T> read(name: String, block: () -> T): T? = try { block() } catch (e: Exception) {
        errors += "$name: ${e.javaClass.simpleName}: ${e.message}"
        null
    }
    val capabilities = read("REQUEST_AVAILABLE_CAPABILITIES") { c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES] }
    val map = read("SCALER_STREAM_CONFIGURATION_MAP") { c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] }
    val sizes = read("DEPTH16 sizes") {
        map?.getOutputSizes(ImageFormat.DEPTH16)
            .orEmpty().map { LiveSize(it.width, it.height) }.distinct().sortedBy { it.width.toLong() * it.height }
    }.orEmpty()
    return DepthSupport(
        capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT), sizes,
        read("DEPTH_DEPTH_IS_EXCLUSIVE") { c[CameraCharacteristics.DEPTH_DEPTH_IS_EXCLUSIVE] },
        read("Color output formats") { map?.outputFormats?.any { it != ImageFormat.DEPTH16 && it != ImageFormat.DEPTH_POINT_CLOUD } }, errors,
    )
}
