package dev.halcamera.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build

fun portraitSupport(manager: CameraManager, id: String, publicCamera: Boolean = true): PortraitSupport {
    if (!publicCamera) return PortraitSupport(null, reason = "Physical camera: select a publicly listed camera ID in LIVE")
    if (Build.VERSION.SDK_INT < 31) return PortraitSupport(null, reason = "Camera2 Portrait requires Android 12 / API 31 or newer.")
    val errors = mutableListOf<String>()
    fun <T> read(label: String, block: () -> T): T? = try { block() } catch (e: Exception) {
        errors += "$label: ${e.javaClass.simpleName}: ${e.message}"; null
    }
    val extension = read("Bokeh characteristics") { manager.getCameraExtensionCharacteristics(id) }
    val mode = CameraExtensionCharacteristics.EXTENSION_BOKEH
    val advertised = read("Supported extensions") { extension?.supportedExtensions?.contains(mode) }
    fun sizes(label: String, block: () -> List<android.util.Size>?): List<LiveSize> =
        if (advertised != true) emptyList() else read(label) {
            block().orEmpty().map { LiveSize(it.width, it.height) }.distinct().sortedBy { it.width.toLong() * it.height }
        }.orEmpty()
    return PortraitSupport(advertised,
        sizes("Bokeh preview sizes") { extension?.getExtensionSupportedSizes(mode, SurfaceTexture::class.java) },
        sizes("Bokeh JPEG sizes") { extension?.getExtensionSupportedSizes(mode, ImageFormat.JPEG) }, errors = errors)
}
