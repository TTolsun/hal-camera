package dev.cameradoctor.benchmark

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
import java.util.concurrent.Executor

/**
 * Preflight of the benchmark profile (docs/PLAN-BenchMarker-v0.3.md 3.6). The camera is never opened: an extra
 * open would add an unmeasured launch and blur what "warm reopen" means.
 *
 * The result is SUPPORTED or UNSUPPORTED, never a degraded variant. The app does not lower the streams and keep
 * the same profile id (METRICS.md 0.4, "no automatic fallback"); a device that cannot do 1080p needs its own
 * profile id, which is out of scope for v0.3.
 */
object ProfileCompatibility {
    const val METHOD_DEVICE_SETUP = "device_setup"
    const val METHOD_STATIC_TABLE = "static_table"

    const val REASON_PREVIEW_SIZE = "PREVIEW_SIZE"
    const val REASON_YUV_SIZE = "YUV_SIZE"
    const val REASON_JPEG_SIZE = "JPEG_SIZE"
    const val REASON_FPS_RANGE = "FPS_RANGE"
    const val REASON_STREAM_COMBINATION = "STREAM_COMBINATION"

    /** 30 fps in nanoseconds. Only recorded in compatibility.frame_budget_ok; never part of the verdict. */
    const val FRAME_BUDGET_NS = 33_333_333L

    /**
     * What the static preflight reads from CameraCharacteristics, already normalized to strings so the decision
     * itself is pure Kotlin and testable on the JVM. Null means "could not be read".
     */
    data class Inputs(
        val previewSizes: List<String>,
        val yuvSizes: List<String>,
        val jpegSizes: List<String>,
        val fpsRanges: List<String>,
        val hardwareLevel: Int?,
        /** Display size in pixels; the PREVIEW stream grade is the smaller of the display and 1080p. */
        val displayWidth: Int?,
        val displayHeight: Int?,
        val previewMinFrameDurationNs: Long?,
        val yuvMinFrameDurationNs: Long?
    )

    /** "1920x1080" from any of "1920x1080", "1920 x 1080" or Size.toString(). */
    fun normalizeSize(text: String): String = text.replace(" ", "").lowercase()

    /** "[30,30]" from any of "[30,30]", "[30, 30]" or Range<Int>.toString(). */
    fun normalizeRange(text: String): String = text.replace(" ", "")

    private fun parseSize(text: String): Pair<Int, Int>? {
        val parts = normalizeSize(text).split("x")
        if (parts.size != 2) return null
        val w = parts[0].toIntOrNull() ?: return null
        val h = parts[1].toIntOrNull() ?: return null
        return w to h
    }

    /**
     * Static verdict from the characteristics alone. The stream combination is judged from the guarantee table:
     * even LEGACY guarantees PRIV(PREVIEW) + YUV(PREVIEW) + JPEG(MAXIMUM), but the PREVIEW grade is the smaller
     * of the display resolution and 1080p, so a display below 1080p makes the profile unsupported.
     */
    fun evaluateStatic(profile: BenchmarkProfile, x: Inputs): Compatibility {
        val reasons = ArrayList<String>()
        val preview = normalizeSize(profile.previewSize)
        val yuv = normalizeSize(profile.yuvSize)
        val jpeg = normalizeSize(profile.stillSize)
        if (preview !in x.previewSizes.map(::normalizeSize)) reasons += REASON_PREVIEW_SIZE
        if (yuv !in x.yuvSizes.map(::normalizeSize)) reasons += REASON_YUV_SIZE
        if (jpeg !in x.jpegSizes.map(::normalizeSize)) reasons += REASON_JPEG_SIZE
        if (normalizeRange(profile.fpsRange) !in x.fpsRanges.map(::normalizeRange)) reasons += REASON_FPS_RANGE
        if (!previewGradeCovers(preview, x)) reasons += REASON_STREAM_COMBINATION
        return Compatibility(METHOD_STATIC_TABLE, reasons.isEmpty(), reasons, frameBudgetOk(x))
    }

    /**
     * The PREVIEW size grade is min(display, 1080p) per stream-combination table. A requested preview stream
     * larger than that grade is not covered by any guaranteed combination.
     */
    private fun previewGradeCovers(previewSize: String, x: Inputs): Boolean {
        val (w, h) = parseSize(previewSize) ?: return false
        val dw = x.displayWidth ?: return true      // unknown display: leave the verdict to the size lists
        val dh = x.displayHeight ?: return true
        val gradeLong = minOf(maxOf(dw, dh), 1920)
        val gradeShort = minOf(minOf(dw, dh), 1080)
        return maxOf(w, h) <= gradeLong && minOf(w, h) <= gradeShort
    }

    /** Recorded, never judged: whether 30 fps is even reachable is answered at runtime by CADENCE_NOT_FIXED (3.6). */
    fun frameBudgetOk(x: Inputs): Boolean? {
        val p = x.previewMinFrameDurationNs
        val y = x.yuvMinFrameDurationNs
        if (p == null || y == null) return null
        return p <= FRAME_BUDGET_NS && y <= FRAME_BUDGET_NS
    }
}

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
