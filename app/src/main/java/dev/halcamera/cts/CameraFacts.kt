package dev.halcamera.cts

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaRecorder
import android.util.Size
import android.view.SurfaceHolder
import dev.halcamera.cts.recording.BasicRecordingRules

/** The CameraCharacteristics reads the cases share, in the shapes the pure rules take. */
object CameraFacts {
    fun hasColorOutput(chars: CameraCharacteristics): Boolean =
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE in (chars[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.toSet() ?: emptySet())

    fun isLegacy(chars: CameraCharacteristics): Boolean =
        chars[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL] == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY

    fun isExternal(chars: CameraCharacteristics): Boolean =
        chars[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL] == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL

    /** SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME: the sensor clock is elapsedRealtimeNanos, comparable with the app clock. */
    fun realtimeTimestamps(chars: CameraCharacteristics): Boolean =
        chars[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE] == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

    /** Every SurfaceHolder output size, largest area first. */
    fun previewSizes(chars: CameraCharacteristics): List<Dim> =
        chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]?.getOutputSizes(SurfaceHolder::class.java).orEmpty().map(::dim)
            .sortedWith(compareByDescending<Dim> { it.area }.thenByDescending { it.width })

    /** CameraTestUtils mOrderedPreviewSizes: SurfaceHolder sizes within the 1080p-or-window bound, largest first. */
    fun orderedPreviewSizes(chars: CameraCharacteristics, windowWidth: Int, windowHeight: Int): List<Dim> =
        BasicRecordingRules.boundedDescending(previewSizes(chars), BasicRecordingRules.previewSizeBound(windowWidth, windowHeight))

    /** CameraTestUtils mOrderedStillSizes: every JPEG output size, largest first. */
    fun orderedStillSizes(chars: CameraCharacteristics): List<Dim> =
        chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]?.getOutputSizes(ImageFormat.JPEG).orEmpty().map(::dim)
            .sortedWith(compareByDescending<Dim> { it.area }.thenByDescending { it.width })

    /** What testBasicRecording's StaticMetadata and helpers read for one camera, given the CamcorderProfile [qualities] it has. */
    fun recordingInfo(cameraId: String, chars: CameraCharacteristics, qualities: Set<Int>, windowWidth: Int, windowHeight: Int): BasicRecordingRules.CameraInfo {
        val map = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        val previewBound = BasicRecordingRules.previewSizeBound(windowWidth, windowHeight)
        val videoSizes = map?.getOutputSizes(MediaRecorder::class.java).orEmpty().map(::dim)
        val privateDurations = HashMap<Dim, Long>()
        map?.getOutputSizes(ImageFormat.PRIVATE).orEmpty().forEach { s ->
            runCatching { map?.getOutputMinFrameDuration(ImageFormat.PRIVATE, s) }.getOrNull()?.let { privateDurations[dim(s)] = it }
        }
        return BasicRecordingRules.CameraInfo(
            cameraId = cameraId,
            hasColorOutput = hasColorOutput(chars),
            isExternal = isExternal(chars),
            isLegacy = isLegacy(chars),
            orderedPreviewSizes = BasicRecordingRules.boundedDescending(previewSizes(chars), previewBound),
            supportedVideoSizes = BasicRecordingRules.boundedDescending(videoSizes, BasicRecordingRules.videoSizeBound(qualities)),
            fpsRanges = chars[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES].orEmpty().map { it.lower to it.upper },
            privateMinFrameDurationNs = privateDurations
        )
    }

    fun dim(size: Size) = Dim(size.width, size.height)
}
