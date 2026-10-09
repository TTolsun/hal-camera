package dev.halcamera.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build
import androidx.annotation.RequiresApi

/** Exposure/focus controls target the main physical camera; zoom deliberately targets both outputs. */
class DualMainControls @RequiresApi(28) constructor(val physicalId: String, logical: CameraCharacteristics, val characteristics: CameraCharacteristics) {
    @android.annotation.SuppressLint("NewApi") // Constructor is gated at API 28; getters contain no platform calls.
    private val keys = logical.availablePhysicalCameraRequestKeys.orEmpty().toSet()
    fun supports(key: CaptureRequest.Key<*>) = key in keys
    val support = liveControlSupport(characteristics).let { it.copy(
        evRange = it.evRange.takeIf { supports(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION) },
        aeLock = it.aeLock && supports(CaptureRequest.CONTROL_AE_LOCK),
        afLock = it.afLock && supports(CaptureRequest.CONTROL_AF_MODE) && supports(CaptureRequest.CONTROL_AF_TRIGGER),
        // A shared flash illuminates both sensors; it cannot be a main-only control.
        flash = false, autoFlash = false, alwaysFlash = false,
    ) }
    val manual = manualSupport(characteristics, 30).let { it.copy(
        iso = it.iso.takeIf { listOf(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.SENSOR_SENSITIVITY,
            CaptureRequest.SENSOR_EXPOSURE_TIME, CaptureRequest.SENSOR_FRAME_DURATION).all(::supports) },
        exposureNs = it.exposureNs.takeIf { listOf(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.SENSOR_SENSITIVITY,
            CaptureRequest.SENSOR_EXPOSURE_TIME, CaptureRequest.SENSOR_FRAME_DURATION).all(::supports) },
        maxFocus = if (supports(CaptureRequest.CONTROL_AF_MODE) && supports(CaptureRequest.LENS_FOCUS_DISTANCE)) it.maxFocus else 0f,
        whiteBalances = if (supports(CaptureRequest.CONTROL_AWB_MODE)) it.whiteBalances.filter { wb -> wb != WhiteBalance.CUSTOM }
            else listOf(WhiteBalance.AUTO),
    ) }
    private val logicalCharacteristics = logical
    val maxZoom = if (Build.VERSION.SDK_INT >= 30) logical[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]?.upper
        ?: logical[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM] ?: 1f
        else logical[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM] ?: 1f
    var zoom = 1f
    var controls = LiveControls()
    var afRegion: MeteringRectangle? = null
    var aeRegion: MeteringRectangle? = null
    val usesZoomRatio get() = Build.VERSION.SDK_INT >= 30 &&
        logicalCharacteristics[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE] != null

    fun crop(): Rect {
        val a = characteristics[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE] ?: Rect(0, 0, 1, 1)
        val z = zoom.coerceIn(1f, maxZoom.coerceAtLeast(1f))
        val w = (a.width() / z).toInt().coerceAtLeast(1)
        val h = (a.height() / z).toInt().coerceAtLeast(1)
        return Rect(a.centerX() - w / 2, a.centerY() - h / 2, a.centerX() - w / 2 + w, a.centerY() - h / 2 + h)
    }

    @RequiresApi(28)
    fun apply(camera: CameraDevice, target: CaptureRequest.Builder, video: Boolean, trigger: Int? = null) {
        val values = camera.createCaptureRequest(if (video) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
        val desired = controls.copy(manual = controls.manual.normalized(manual)).coerce(support, video)
        values.applyLiveControls(desired)
        val modes = characteristics[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
        val mode = if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in modes)
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE else CaptureRequest.CONTROL_AF_MODE_OFF
        values.set(CaptureRequest.CONTROL_AF_MODE, if (afRegion != null) CaptureRequest.CONTROL_AF_MODE_AUTO else mode)
        values.applyManualControls(desired.manual, manual.frameNs)
        if (Build.VERSION.SDK_INT >= 30 && usesZoomRatio) {
            target.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom.coerceIn(1f, maxZoom.coerceAtLeast(1f)))
        } else {
            val a = logicalCharacteristics[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]
            if (a != null) {
                val z = zoom.coerceIn(1f, maxZoom.coerceAtLeast(1f))
                val w = (a.width() / z).toInt().coerceAtLeast(1)
                val h = (a.height() / z).toInt().coerceAtLeast(1)
                target.set(CaptureRequest.SCALER_CROP_REGION, Rect(a.centerX() - w / 2, a.centerY() - h / 2,
                    a.centerX() - w / 2 + w, a.centerY() - h / 2 + h))
            }
        }
        afRegion?.let { values.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it)) }
        aeRegion?.let { values.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it)) }
        trigger?.let { values.set(CaptureRequest.CONTROL_AF_TRIGGER, it) }
        val request = values.build()
        fun <T> copy(key: CaptureRequest.Key<T>) {
            if (supports(key)) request[key]?.let { target.setPhysicalCameraKey(key, it, physicalId) }
        }
        listOf(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
            CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.SENSOR_SENSITIVITY).forEach(::copy)
        copy(CaptureRequest.CONTROL_AE_LOCK)
        copy(CaptureRequest.SENSOR_EXPOSURE_TIME); copy(CaptureRequest.SENSOR_FRAME_DURATION)
        copy(CaptureRequest.LENS_FOCUS_DISTANCE)
        copy(CaptureRequest.CONTROL_AF_REGIONS); copy(CaptureRequest.CONTROL_AE_REGIONS)
        if (trigger != null) copy(CaptureRequest.CONTROL_AF_TRIGGER)
    }
}
