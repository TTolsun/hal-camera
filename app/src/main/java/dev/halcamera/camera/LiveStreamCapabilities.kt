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
    return hardware.copy(videos = videos, defaultVideo = default, yuvSaveFormats = listOf(YuvSaveFormat.JPEG), raw = emptyList(),
        stabilization = cameraXStabilizationModes(info, hardware.stabilization),
        stabilizationNotice = "Stabilization may be unavailable at some resolutions or frame rates.")
}

internal fun cameraXStabilizationModes(info: androidx.camera.core.CameraInfo, hardware: List<LiveStabilization>) =
    LiveStabilization.supportedCameraX(hardware,
        androidx.camera.video.Recorder.getVideoCapabilities(info).isStabilizationSupported,
        androidx.camera.core.Preview.getPreviewCapabilities(info).isStabilizationSupported)

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
        hardwareStabilizationModes(c),
        "Stabilization may be unavailable at some resolutions or frame rates.",
        yuvSaveFormats = YuvSaveFormat.entries, raw = rawSizes(c),
        rawUnavailableReason = if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW !in
            (c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES] ?: IntArray(0))) "RAW capability 없음"
            else "RAW_SENSOR 지원 크기 없음")
}

/** RAW_SENSOR sizes for DNG (#177), only when the camera advertises the RAW capability that DngCreator needs. */
internal fun rawSizes(c: CameraCharacteristics): List<LiveSize> {
    val raw = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in (c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES] ?: IntArray(0))
    if (!raw) return emptyList()
    return c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]?.getOutputSizes(ImageFormat.RAW_SENSOR).orEmpty()
        .map { LiveSize(it.width, it.height) }.distinct().sortedBy { it.width.toLong() * it.height }
}

internal fun hardwareStabilizationModes(c: CameraCharacteristics) = LiveStabilization.supported(
    c[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]?.toList().orEmpty()
        .takeIf { android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE in c.availableCaptureRequestKeys }.orEmpty(),
    c[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES]?.toList().orEmpty()
        .takeIf { android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE in c.availableCaptureRequestKeys }.orEmpty(),
    android.os.Build.VERSION.SDK_INT)
