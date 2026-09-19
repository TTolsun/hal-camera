package dev.halcamera.ctsvendor

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.CamcorderProfile
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import androidx.annotation.RequiresApi

/** A gate returns the reason it fails for this camera, or null when it passes. */
private typealias Gate = (id: String, c: CameraCharacteristics) -> String?

/**
 * Why a vendored method had nothing to check on this device, camera by camera, in terms of the
 * CameraCharacteristics keys and media profiles the upstream gate reads. The test's own log line says
 * "does not support HEIC"; this says which key it read and what the key holds instead.
 *
 * Each entry mirrors the `continue` conditions of one upstream method at the vendored commit. A method
 * without an entry gets only the color-output check every method shares. Re-check against the sources when
 * the commit moves.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU) // DynamicRangeProfiles.getSupportedProfiles (33) is the newest key read; the vendored path itself needs 34.
object SkipDiagnosis {
    private const val PKG = "android.hardware.camera2.cts."

    /**
     * One line per failed gate, naming the key and its value: "모든 카메라: …" when every camera fails the same
     * way (device-wide facts such as a missing encoder read the same everywhere), "카메라 N: …" otherwise.
     * Empty when every camera passes the gate.
     */
    fun explain(test: VendoredTest, manager: CameraManager): List<String> = runCatching {
        val checks = gates[test.id] ?: emptyList()
        val ids = manager.cameraIdList.toList()
        val failures = ids.associateWith { id -> (common + checks).mapNotNull { gate -> gate(id, manager.getCameraCharacteristics(id)) } }
        fold(failures)
    }.getOrElse { listOf("진단 실패: ${it.javaClass.simpleName}: ${it.message}") }

    /** Pure: the per-camera failure lists as the lines [explain] returns. */
    fun fold(failures: Map<String, List<String>>): List<String> {
        if (failures.isEmpty()) return emptyList()
        val everywhere = failures.values.map { it.toSet() }.reduce { a, b -> a intersect b }
        val lines = ArrayList<String>()
        failures.values.first().filter { it in everywhere }.forEach { lines += "모든 카메라: $it" }
        failures.forEach { (id, failed) ->
            val own = failed.filter { it !in everywhere }
            if (own.isNotEmpty()) lines += "카메라 $id: " + own.joinToString("; ")
        }
        return lines
    }

    /** The one gate every mapped method shares: the color-output capability. External cameras are a per-method gate. */
    private val common: List<Gate> = listOf(
        { _, c -> if (!capability(c, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) "REQUEST_AVAILABLE_CAPABILITIES에 BACKWARD_COMPATIBLE 없음(컬러 출력 불가)" else null }
    )

    private val notExternal: Gate = { _, c ->
        if (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL) "INFO_SUPPORTED_HARDWARE_LEVEL = EXTERNAL(CamcorderProfile 없음)" else null
    }

    private val gates: Map<String, List<Gate>> = mapOf(
        PKG + "StillCaptureTest#testHeicExif" to listOf(outputFormat("HEIC", ImageFormat.HEIC)),
        PKG + "StillCaptureTest#testHeicUltraHdrCapture" to listOf(outputFormat("HEIC_ULTRAHDR", HEIC_ULTRAHDR)),
        PKG + "StillCaptureTest#testDynamicDepthCapture" to listOf(outputFormat("DEPTH_JPEG", ImageFormat.DEPTH_JPEG)),
        PKG + "StillCaptureTest#testBasicRawCapture" to listOf(rawCapability(), outputFormat("RAW_SENSOR", ImageFormat.RAW_SENSOR)),
        PKG + "StillCaptureTest#testFullRawCapture" to listOf(rawCapability(), outputFormat("RAW_SENSOR", ImageFormat.RAW_SENSOR)),
        PKG + "StillCaptureTest#testBasicRawZslCapture" to listOf(rawCapability(), outputFormat("RAW_SENSOR", ImageFormat.RAW_SENSOR)),
        PKG + "StillCaptureTest#testFullRawZSLCapture" to listOf(rawCapability(), outputFormat("RAW_SENSOR", ImageFormat.RAW_SENSOR)),
        PKG + "BurstCaptureTest#testYuvBurstWithStillBokeh" to listOf(
            { _, c -> if (c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) != true) "CONTROL_AE_LOCK_AVAILABLE = false" else null },
            { _, c -> if (c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) != true) "CONTROL_AWB_LOCK_AVAILABLE = false" else null },
            { _, c -> if (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) "INFO_SUPPORTED_HARDWARE_LEVEL = LEGACY" else null },
            { _, c ->
                val modes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_CAPABILITIES)?.map { it.mode }.orEmpty()
                if (CameraMetadata.CONTROL_EXTENDED_SCENE_MODE_BOKEH_STILL_CAPTURE !in modes)
                    "CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_CAPABILITIES에 BOKEH_STILL_CAPTURE 없음(있는 모드: ${modes.joinToString { extendedSceneMode(it) }.ifEmpty { "없음" }})"
                else null
            }
        ),
        PKG + "RecordingTest#testSlowMotionRecording" to listOf(highSpeedVideo()),
        PKG + "RecordingTest#testConstrainedHighSpeedRecording" to listOf(
            { _, c -> if (!capability(c, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) "REQUEST_AVAILABLE_CAPABILITIES에 CONSTRAINED_HIGH_SPEED_VIDEO 없음" else null }
        ),
        PKG + "RecordingTest#testAbandonedHighSpeedRequest" to listOf(
            { _, c -> if (!capability(c, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) "REQUEST_AVAILABLE_CAPABILITIES에 CONSTRAINED_HIGH_SPEED_VIDEO 없음" else null }
        ),
        PKG + "RecordingTest#testBasic10BitRecordingHEVC" to tenBit(MediaFormat.MIMETYPE_VIDEO_HEVC, listOf(
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 to "HEVCProfileMain10",
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 to "HEVCProfileMain10HDR10",
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus to "HEVCProfileMain10HDR10Plus"
        ), av1 = false),
        PKG + "RecordingTest#testBasic10BitRecordingAV1" to tenBit(MediaFormat.MIMETYPE_VIDEO_AV1, listOf(
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 to "AV1ProfileMain10",
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 to "AV1ProfileMain10HDR10",
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus to "AV1ProfileMain10HDR10Plus"
        ), av1 = true)
    )

    /** ImageFormat.HEIC_ULTRAHDR is a 36 constant; the literal keeps the check meaningful on older SDK builds. */
    private const val HEIC_ULTRAHDR = 0x48455548

    private fun capability(c: CameraCharacteristics, cap: Int): Boolean =
        c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(cap) == true

    private fun outputFormat(name: String, format: Int): Gate = { _, c ->
        val formats = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.outputFormats?.toList().orEmpty()
        if (format !in formats) "SCALER_STREAM_CONFIGURATION_MAP 출력 형식에 ImageFormat.$name(0x${Integer.toHexString(format)}) 없음(있는 형식: ${formats.joinToString { formatName(it) }})"
        else null
    }

    private fun rawCapability(): Gate = { _, c ->
        if (!capability(c, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)) "REQUEST_AVAILABLE_CAPABILITIES에 RAW 없음" else null
    }

    private fun highSpeedVideo(): Gate = { _, c ->
        val modes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)?.toList().orEmpty()
        val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.highSpeedVideoSizes.orEmpty()
        when {
            CameraMetadata.CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO !in modes -> "CONTROL_AVAILABLE_SCENE_MODES에 HIGH_SPEED_VIDEO 없음(있는 모드: ${modes.joinToString { sceneMode(it) }})"
            sizes.isEmpty() -> "SCALER_STREAM_CONFIGURATION_MAP.getHighSpeedVideoSizes()가 비어 있음"
            else -> null
        }
    }

    /**
     * The 10-bit recording gate: a CamcorderProfile the camera has, an encoder for the codec profile, the
     * DYNAMIC_RANGE_TEN_BIT capability, and a dynamic range profile the upstream switch maps the codec profile
     * to. That switch lists HEVC only, so the AV1 method never opens a camera anywhere; the note says so.
     */
    private fun tenBit(mime: String, codecProfiles: List<Pair<Int, String>>, av1: Boolean): List<Gate> = listOf(
        notExternal,
        { id, _ ->
            val cameraId = id.toIntOrNull()
            when {
                cameraId == null -> "카메라 ID '$id'가 정수가 아니어서 CamcorderProfile을 조회할 수 없음"
                CAMCORDER_QUALITIES.none { CamcorderProfile.hasProfile(cameraId, it.first) } -> "CamcorderProfile 없음(QUALITY_HIGH·2160P·1080P·720P·480P·CIF·QCIF·QVGA 모두 hasProfile=false)"
                else -> null
            }
        },
        { _, _ ->
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val missing = codecProfiles.filter { (profile, _) ->
                val format = MediaFormat.createVideoFormat(mime, 1920, 1080).apply { setInteger(MediaFormat.KEY_PROFILE, profile) }
                list.findEncoderForFormat(format) == null
            }
            if (missing.size == codecProfiles.size) "MediaCodecList에 $mime 인코더 없음(프로파일 ${codecProfiles.joinToString { it.second }})" else null
        },
        { _, c ->
            if (!capability(c, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT)) "REQUEST_AVAILABLE_CAPABILITIES에 DYNAMIC_RANGE_TEN_BIT 없음"
            else {
                val profiles = c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles.orEmpty()
                val wanted = listOf(DynamicRangeProfiles.HLG10 to "HLG10", DynamicRangeProfiles.HDR10 to "HDR10", DynamicRangeProfiles.HDR10_PLUS to "HDR10_PLUS")
                if (wanted.none { it.first in profiles }) "REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES에 HLG10·HDR10·HDR10_PLUS 없음(있는 프로파일: ${profiles.joinToString { dynamicRange(it) }})" else null
            }
        },
        { _, _ -> if (av1) "업스트림 RecordingTest.getDynamicRangeProfile()의 switch에 AV1 프로파일 case가 없어(HEVC만 매핑) 이 메서드는 어떤 기기에서도 카메라를 열지 않음" else null }
    )

    private val CAMCORDER_QUALITIES = listOf(
        CamcorderProfile.QUALITY_HIGH to "HIGH", CamcorderProfile.QUALITY_2160P to "2160P", CamcorderProfile.QUALITY_1080P to "1080P",
        CamcorderProfile.QUALITY_720P to "720P", CamcorderProfile.QUALITY_480P to "480P", CamcorderProfile.QUALITY_CIF to "CIF",
        CamcorderProfile.QUALITY_QCIF to "QCIF", CamcorderProfile.QUALITY_QVGA to "QVGA"
    )

    private fun formatName(format: Int): String = when (format) {
        ImageFormat.JPEG -> "JPEG"; ImageFormat.YUV_420_888 -> "YUV_420_888"; ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
        ImageFormat.RAW10 -> "RAW10"; ImageFormat.RAW12 -> "RAW12"; ImageFormat.RAW_PRIVATE -> "RAW_PRIVATE"; ImageFormat.PRIVATE -> "PRIVATE"
        ImageFormat.HEIC -> "HEIC"; ImageFormat.DEPTH16 -> "DEPTH16"; ImageFormat.DEPTH_JPEG -> "DEPTH_JPEG"; ImageFormat.Y8 -> "Y8"
        ImageFormat.YCBCR_P010 -> "YCBCR_P010"; ImageFormat.JPEG_R -> "JPEG_R"; HEIC_ULTRAHDR -> "HEIC_ULTRAHDR"
        else -> "0x" + Integer.toHexString(format)
    }

    private fun sceneMode(mode: Int): String = when (mode) {
        CameraMetadata.CONTROL_SCENE_MODE_DISABLED -> "DISABLED"; CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY -> "FACE_PRIORITY"
        CameraMetadata.CONTROL_SCENE_MODE_ACTION -> "ACTION"; CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT -> "PORTRAIT"
        CameraMetadata.CONTROL_SCENE_MODE_LANDSCAPE -> "LANDSCAPE"; CameraMetadata.CONTROL_SCENE_MODE_NIGHT -> "NIGHT"
        CameraMetadata.CONTROL_SCENE_MODE_HDR -> "HDR"; CameraMetadata.CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO -> "HIGH_SPEED_VIDEO"
        else -> mode.toString()
    }

    private fun extendedSceneMode(mode: Int): String = when (mode) {
        CameraMetadata.CONTROL_EXTENDED_SCENE_MODE_DISABLED -> "DISABLED"
        CameraMetadata.CONTROL_EXTENDED_SCENE_MODE_BOKEH_STILL_CAPTURE -> "BOKEH_STILL_CAPTURE"
        CameraMetadata.CONTROL_EXTENDED_SCENE_MODE_BOKEH_CONTINUOUS -> "BOKEH_CONTINUOUS"
        else -> mode.toString()
    }

    private fun dynamicRange(profile: Long): String = when (profile) {
        DynamicRangeProfiles.STANDARD -> "STANDARD"; DynamicRangeProfiles.HLG10 -> "HLG10"; DynamicRangeProfiles.HDR10 -> "HDR10"
        DynamicRangeProfiles.HDR10_PLUS -> "HDR10_PLUS"; DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF -> "DOLBY_VISION_10B_HDR_REF"
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM -> "DOLBY_VISION_10B_HDR_OEM"; DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF -> "DOLBY_VISION_8B_HDR_REF"
        else -> "0x" + java.lang.Long.toHexString(profile)
    }
}
