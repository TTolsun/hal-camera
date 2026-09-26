package dev.halcamera.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest

/**
 * The Camera2 side of [LiveControls]: which controls a camera offers, and the request keys that carry them.
 * Camera2Engine calls [applyLiveControls] on every LIVE request it builds, so preview, still and recording
 * requests agree and switching between them never silently drops a lock, an EV step or the torch.
 */
fun liveControlSupport(manager: CameraManager, id: String): LiveControlSupport =
    try { liveControlSupport(manager.getCameraCharacteristics(id)) } catch (_: Exception) { LiveControlSupport.NONE }

fun liveControlSupport(c: CameraCharacteristics): LiveControlSupport {
    val ev = c[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE]
    val step = c[CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP]?.toDouble() ?: 0.0
    val aeModes = c[CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES] ?: intArrayOf()
    val afModes = c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
    val minFocus = c[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f
    return LiveControlSupport(
        evRange = ev?.takeIf { it.lower < it.upper && step > 0 }?.let { it.lower..it.upper },
        evStep = step,
        aeLock = c[CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE] == true,
        afLock = minFocus > 0f && CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes,
        flash = c[CameraCharacteristics.FLASH_INFO_AVAILABLE] == true,
        autoFlash = CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH in aeModes,
        alwaysFlash = CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH in aeModes,
    )
}

/**
 * Sets EV, AE lock, the AE mode that carries the flash choice, and the torch. AF lock is not a key: it is the
 * AF trigger Camera2Engine sends once, and the continuous AF mode these requests keep holds the lock until a
 * cancel trigger.
 *
 * A camera without a flash unit keeps CONTROL_AE_MODE_ON: the flash AE modes are not listed for it and the
 * request would be rejected.
 */
fun CaptureRequest.Builder.applyLiveControls(controls: LiveControls) {
    set(CaptureRequest.CONTROL_AE_MODE, when (controls.flash) {
        FlashMode.AUTO -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
        FlashMode.ON -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
        FlashMode.OFF, FlashMode.TORCH -> CaptureRequest.CONTROL_AE_MODE_ON
    })
    set(CaptureRequest.FLASH_MODE, if (controls.flash == FlashMode.TORCH) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, controls.evIndex)
    // Camera2 applies exposure compensation even while AE is locked (CONTROL_AE_EXPOSURE_COMPENSATION), so a
    // locked exposure can still be stepped brighter or darker. The chips allow it for that reason.
    set(CaptureRequest.CONTROL_AE_LOCK, controls.aeLock)
}
