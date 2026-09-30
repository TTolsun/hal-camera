package dev.halcamera.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.util.Rational

fun manualSupport(c: CameraCharacteristics, fps: Int): ManualSupport {
    val caps = c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES] ?: intArrayOf()
    val keys = c.availableCaptureRequestKeys.orEmpty()
    val sensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps &&
        listOf(CaptureRequest.SENSOR_SENSITIVITY, CaptureRequest.SENSOR_EXPOSURE_TIME, CaptureRequest.SENSOR_FRAME_DURATION).all { it in keys }
    val post = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in caps &&
        listOf(CaptureRequest.COLOR_CORRECTION_GAINS, CaptureRequest.COLOR_CORRECTION_TRANSFORM).all { it in keys }
    val awb = c[CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES] ?: intArrayOf()
    val af = c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
    return ManualSupport(
        iso = c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]?.takeIf { sensor }?.let { it.lower..it.upper },
        exposureNs = c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]?.takeIf { sensor }?.let { it.lower..it.upper },
        maxFocus = (c[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f).takeIf {
            CaptureRequest.CONTROL_AF_MODE_OFF in af && CaptureRequest.LENS_FOCUS_DISTANCE in keys } ?: 0f,
        whiteBalances = WhiteBalance.entries.filter { it.key in awb && (it != WhiteBalance.CUSTOM || post && sensor) },
        frameNs = (1_000_000_000L / fps.coerceAtLeast(1)).coerceAtMost(c[CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION] ?: Long.MAX_VALUE),
    )
}

/** Called after touch controls so a stale touched AF region can never override manual focus. */
fun CaptureRequest.Builder.applyManualControls(manual: ManualControls, frameNs: Long) {
    manual.exposure?.let {
        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        set(CaptureRequest.CONTROL_AE_LOCK, false)
        set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
        set(CaptureRequest.SENSOR_SENSITIVITY, it.iso)
        set(CaptureRequest.SENSOR_EXPOSURE_TIME, it.timeNs)
        set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs.coerceAtLeast(it.timeNs))
    }
    manual.focusDiopters?.let {
        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        set(CaptureRequest.LENS_FOCUS_DISTANCE, it)
    }
    set(CaptureRequest.CONTROL_AWB_MODE, manual.wb.key)
    if (manual.wb == WhiteBalance.CUSTOM) {
        val g = manual.color.gains
        set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(g[0], g[1], g[2], g[3]))
        set(CaptureRequest.COLOR_CORRECTION_TRANSFORM,
            ColorSpaceTransform(manual.color.transform.map { Rational((it * 10000).toInt(), 10000) }.toTypedArray()))
    }
}
