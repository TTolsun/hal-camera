package dev.halcamera.benchmark.platform

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import dev.halcamera.benchmark.domain.BenchmarkProfile
import dev.halcamera.benchmark.domain.Compatibility
import dev.halcamera.benchmark.domain.ProfileCompatibility
import java.util.concurrent.Executor

/**
 * Reads [ProfileCompatibility.Inputs] from a camera and, on API 35 and above, asks the platform the exact
 * question through CameraManager.getCameraDeviceSetup() without opening the camera. The exact query only ever
 * upgrades the recorded method: a static UNSUPPORTED (a missing size or fps range) stays unsupported, because
 * the exact query answers "is this combination configurable", not "does this size exist".
 */
class ProfileCompatibilityChecker(
    private val manager: CameraManager,
    private val displayWidth: Int? = null,
    private val displayHeight: Int? = null
) {
    fun check(profile: BenchmarkProfile, cameraId: String): Compatibility {
        val inputs = try { inputs(profile, cameraId) } catch (e: Exception) {
            Log.w(TAG, "characteristics unreadable for $cameraId: ${e.message}")
            return Compatibility(ProfileCompatibility.METHOD_STATIC_TABLE, false, listOf(ProfileCompatibility.REASON_STREAM_COMBINATION), null)
        }
        val static = ProfileCompatibility.evaluateStatic(profile, inputs)
        if (!static.supported) return static
        val exact = exactQuery(profile, cameraId) ?: return static
        return static.copy(
            method = ProfileCompatibility.METHOD_DEVICE_SETUP,
            supported = exact,
            reasons = if (exact) emptyList() else listOf(ProfileCompatibility.REASON_STREAM_COMBINATION)
        )
    }

    /** True or false from the platform's exact query; null when this device has no CameraDeviceSetup for the camera. */
    @SuppressLint("NewApi")
    fun exactQuery(profile: BenchmarkProfile, cameraId: String): Boolean? {
        if (Build.VERSION.SDK_INT < 35) return null
        return try {
            if (!manager.isCameraDeviceSetupSupported(cameraId)) return null
            val setup = manager.getCameraDeviceSetup(cameraId)
            val preview = size(profile.previewSize) ?: return null
            val yuv = size(profile.yuvSize) ?: return null
            val jpeg = size(profile.stillSize) ?: return null
            val outputs = listOf(
                OutputConfiguration(preview, SurfaceTexture::class.java),
                OutputConfiguration(ImageFormat.YUV_420_888, yuv),
                OutputConfiguration(ImageFormat.JPEG, jpeg)
            )
            val executor = Executor { it.run() }
            val configuration = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, NoopSessionCallback)
            fpsRange(profile.fpsRange)?.let { range ->
                val request = setup.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                request.set(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                configuration.sessionParameters = request.build()
            }
            setup.isSessionConfigurationSupported(configuration)
        } catch (e: Exception) {
            // An unsupported query is not an unsupported profile: fall back to the static table.
            Log.w(TAG, "CameraDeviceSetup query failed for $cameraId: ${e.message}")
            null
        }
    }

    private fun inputs(profile: BenchmarkProfile, cameraId: String): ProfileCompatibility.Inputs {
        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        val previewSizes = map?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG).orEmpty()
        val preview = size(profile.previewSize)
        val yuv = size(profile.yuvSize)
        return ProfileCompatibility.Inputs(
            previewSizes = previewSizes.map { it.toString() },
            yuvSizes = yuvSizes.map { it.toString() },
            jpegSizes = jpegSizes.map { it.toString() },
            fpsRanges = chars[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES].orEmpty().map { it.toString() },
            hardwareLevel = chars[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL],
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            previewMinFrameDurationNs = preview?.takeIf { it in previewSizes }
                ?.let { map?.getOutputMinFrameDuration(SurfaceTexture::class.java, it) },
            yuvMinFrameDurationNs = yuv?.takeIf { it in yuvSizes }
                ?.let { map?.getOutputMinFrameDuration(ImageFormat.YUV_420_888, it) }
        )
    }

    private object NoopSessionCallback : android.hardware.camera2.CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) = Unit
        override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) = Unit
    }

    companion object {
        private const val TAG = "ProfileCompatibility"

        fun size(text: String): Size? {
            val parts = ProfileCompatibility.normalizeSize(text).split("x")
            if (parts.size != 2) return null
            val w = parts[0].toIntOrNull() ?: return null
            val h = parts[1].toIntOrNull() ?: return null
            return Size(w, h)
        }

        fun fpsRange(text: String): Range<Int>? {
            val parts = ProfileCompatibility.normalizeRange(text).removePrefix("[").removeSuffix("]").split(",")
            if (parts.size != 2) return null
            val lower = parts[0].toIntOrNull() ?: return null
            val upper = parts[1].toIntOrNull() ?: return null
            return Range(lower, upper)
        }
    }
}
