package dev.halcamera.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.OutputConfiguration
import android.os.Handler
import android.view.Surface
import androidx.annotation.RequiresApi

@RequiresApi(28)
internal fun pipOutputConfigurations(outputs: List<ConfiguredOutput<Surface>>) = outputs.map { value ->
    OutputConfiguration(value.target).apply { value.descriptor.physicalId?.let { setPhysicalCameraId(it) } }
}

/** The support query and real session share output identities, sizes and physical routing. */
@RequiresApi(28)
internal fun configurePipSession(camera: CameraDevice, outputs: StreamConfiguration<Surface>, handler: Handler,
    alive: () -> Boolean, configured: (CameraCaptureSession) -> Unit, failed: (String) -> Unit) {
    try {
        @Suppress("DEPRECATION")
        camera.createCaptureSessionByOutputConfigurations(pipOutputConfigurations(outputs.outputs),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (alive()) configured(session) else session.close()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close(); failed("PIP combination unavailable")
                }
            },handler)
    } catch (e: Exception) { failed(e.message ?: "PIP unavailable") }
}
