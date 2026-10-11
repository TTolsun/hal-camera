package dev.halcamera.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

internal fun readPipStreamCapabilities(manager: CameraManager, source: PipSource): PipStreamCapabilities {
    val map = manager.getCameraCharacteristics(source.id)[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
    fun sizes(format: Int) = map?.getOutputSizes(format)?.map { LiveSize(it.width,it.height) }.orEmpty()
    return PipStreamCapabilities(source,
        map?.getOutputSizes(SurfaceTexture::class.java)?.map { LiveSize(it.width,it.height) }.orEmpty(),
        sizes(ImageFormat.YUV_420_888), sizes(ImageFormat.JPEG))
}
