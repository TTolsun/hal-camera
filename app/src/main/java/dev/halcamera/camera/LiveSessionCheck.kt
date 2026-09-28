package dev.halcamera.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler

/** An unavailable query is unknown, never a claim of support. Actual configure remains authoritative. */
internal fun checkLiveSession(camera: CameraDevice, outputs: List<OutputConfiguration>, handler: Handler,
                             callback: CameraCaptureSession.StateCallback, report: (String) -> Unit) {
    if (Build.VERSION.SDK_INT < 29) { report("unknown: API < 29"); return }
    val supported = try {
        camera.isSessionConfigurationSupported(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
            outputs, { handler.post(it) }, callback))
    } catch (_: UnsupportedOperationException) { report("unknown: query unavailable"); return }
    report(if (supported) "supported outputs; FPS verified by capture results" else "unsupported outputs")
    require(supported) { "카메라가 요청한 출력 조합을 지원하지 않습니다." }
}
