package dev.halcamera.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.util.Size

internal fun LiveSize.androidSize() = Size(width, height)

/** CameraX Recorder exposes quality resolutions and chooses the SDR codec itself. */
@androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])
fun cameraXStreamSupport(context: android.content.Context, id: String, hardware: LiveStreamSupport): LiveStreamSupport {
    val provider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context).get()
    val info = provider.availableCameraInfos.first { androidx.camera.camera2.interop.Camera2CameraInfo.from(it).cameraId == id }
    val resolutions = androidx.camera.video.QualitySelector.getSupportedQualities(info).mapNotNull {
        androidx.camera.video.QualitySelector.getResolution(info, it)?.let { size -> LiveSize(size.width, size.height) }
    }
    val videos = hardware.videos.filter { it.size in resolutions }.map { it.copy(codec = "Auto") }.distinct()
    val default = videos.filter { it.fps == 30 && it.size.width.toLong() * it.size.height <= 1920L * 1080 }
        .maxByOrNull { it.size.width.toLong() * it.size.height } ?: videos.firstOrNull()
    return hardware.copy(videos = videos, defaultVideo = default,
        stabilization = listOf(LiveStabilization.AUTO),
        stabilizationNotice = "Manual stabilization requires Camera2. Switch to Camera2 to select a mode.")
}

/** Individual sizes and encoder limits are checked here; combinations still need a real session check. */
fun liveStreamSupport(c: CameraCharacteristics): LiveStreamSupport {
    val map = requireNotNull(c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP])
    fun sizes(values: Array<Size>?) = values.orEmpty().map { LiveSize(it.width, it.height) }
        .distinct().sortedBy { it.width.toLong() * it.height }
    val fps = c[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES].orEmpty()
        .filter { it.upper <= 60 }.map { LiveFps(it.lower, it.upper) }.distinct()
    val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
    val videoSizes = sizes(map.getOutputSizes(MediaRecorder::class.java)).filter { it.width >= it.height }
    val videos = listOf("H264" to "video/avc", "HEVC" to "video/hevc").flatMap { (label, mime) ->
        val caps = encoders.filter { mime in it.supportedTypes }.mapNotNull { info ->
            runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
        }.filter { MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in it.colorFormats }
        videoSizes.flatMap { size ->
            listOf(24, 25, 30, 60).filter { rate ->
                fps.any { it.min == rate && it.max == rate } &&
                    (map.getOutputMinFrameDuration(MediaRecorder::class.java, size.androidSize()).let { it == 0L || it <= 1_000_000_000L / rate + 1 }) &&
                    caps.any { it.videoCapabilities?.let { v ->
                        v.areSizeAndRateSupported(size.width, size.height, rate.toDouble()) && v.bitrateRange.contains(10_000_000)
                    } == true }
            }.map { LiveVideo(size, it, label) }
        }
    }
    return LiveStreamSupport(sizes(map.getOutputSizes(SurfaceTexture::class.java)), sizes(map.getOutputSizes(ImageFormat.YUV_420_888)),
        sizes(map.getOutputSizes(ImageFormat.JPEG)), fps, videos, defaultLiveVideo(videoSizes),
        LiveStabilization.supported(
            c[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]?.toList().orEmpty()
                .takeIf { android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE in c.availableCaptureRequestKeys }.orEmpty(),
            c[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES]?.toList().orEmpty()
                .takeIf { android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE in c.availableCaptureRequestKeys }.orEmpty(),
            android.os.Build.VERSION.SDK_INT),
        "OIS and EIS (Video) are exclusive. Availability depends on size and FPS; check the reported result. " +
            "Auto restores camera defaults.")
}
