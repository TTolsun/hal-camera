package dev.halcamera.camera

import java.util.Locale
import kotlin.math.roundToInt

/**
 * What the LIVE screen asks the camera for beyond zoom: exposure compensation, AE and AF lock, and flash
 * (issues #169 and #176). These are requests. What the camera applied is read back from the capture results,
 * which the LIVE readout shows next to the requested chips.
 *
 * Pure Kotlin so the rules below are JVM-tested; Camera2Engine maps them onto CaptureRequest keys.
 * The benchmark never uses them: a profile run keeps its fixed request contract.
 */
data class LiveControls(
    /** Exposure compensation in steps of [LiveControlSupport.evStep], not in EV. */
    val evIndex: Int = 0,
    val aeLock: Boolean = false,
    /** Held by an AF trigger in the continuous AF mode; there is no AF lock key in Camera2. */
    val afLock: Boolean = false,
    val flash: FlashMode = FlashMode.OFF,
) {
    /**
     * Drops what the camera or the capture mode cannot do. Auto and always flash exist for stills only: a
     * recording has no precapture to fire them, so video mode keeps just off and torch.
     */
    fun coerce(support: LiveControlSupport, video: Boolean): LiveControls = copy(
        evIndex = support.evRange?.let { evIndex.coerceIn(it) } ?: 0,
        aeLock = aeLock && support.aeLock,
        afLock = afLock && support.afLock,
        flash = if (flash in support.flashModes(video)) flash else FlashMode.OFF,
    )

    /**
     * A flash still needs the AE precapture sequence first so the metering sees the pre-flash. With AE locked the
     * exposure is already fixed and a precapture trigger would re-meter it, so the still fires without one.
     */
    val needsPrecapture: Boolean get() = (flash == FlashMode.AUTO || flash == FlashMode.ON) && !aeLock
}

enum class FlashMode(val label: String) {
    OFF("Flash off"), AUTO("Flash auto"), ON("Flash on"), TORCH("Torch");
}

/** What one camera supports, read from CameraCharacteristics before the controls are offered. */
data class LiveControlSupport(
    /** CONTROL_AE_COMPENSATION_RANGE; null when the range is [0, 0]. */
    val evRange: IntRange?,
    /** CONTROL_AE_COMPENSATION_STEP in EV. */
    val evStep: Double,
    /** CONTROL_AE_LOCK_AVAILABLE. */
    val aeLock: Boolean,
    /** A continuous AF mode with a lens that moves; fixed-focus cameras have nothing to lock. */
    val afLock: Boolean,
    /** FLASH_INFO_AVAILABLE. */
    val flash: Boolean,
    /** CONTROL_AE_MODE_ON_AUTO_FLASH is listed in the available AE modes. */
    val autoFlash: Boolean,
    /** CONTROL_AE_MODE_ON_ALWAYS_FLASH is listed in the available AE modes. */
    val alwaysFlash: Boolean,
) {
    fun flashModes(video: Boolean): List<FlashMode> = when {
        !flash -> listOf(FlashMode.OFF)
        video -> listOf(FlashMode.OFF, FlashMode.TORCH)
        else -> listOfNotNull(FlashMode.OFF, FlashMode.AUTO.takeIf { autoFlash }, FlashMode.ON.takeIf { alwaysFlash }, FlashMode.TORCH)
    }

    fun evLabel(index: Int): String = LiveControlText.ev(index * evStep)

    companion object {
        /** A camera LIVE could not read. Every control is off and the chips say why when tapped. */
        val NONE = LiveControlSupport(null, 0.0, aeLock = false, afLock = false, flash = false, autoFlash = false, alwaysFlash = false)
    }
}

object LiveControlText {
    /** "EV 0", "EV +0.7", "EV −1.3": one decimal, because the common steps are 1/3 and 1/2 EV. */
    fun ev(value: Double): String {
        val tenths = (value * 10).roundToInt()
        if (tenths == 0) return "EV 0"
        val sign = if (tenths > 0) "+" else "−"
        return "EV $sign${String.format(Locale.US, "%.1f", kotlin.math.abs(tenths) / 10.0)}"
    }
}

/**
 * Waits for the AE precapture sequence to finish, following the Camera2 contract for CONTROL_AE_STATE: after the
 * trigger the state passes through PRECAPTURE and leaves it once metering is done. A device without AE state
 * reports null, which counts as done so the still is never held back by a state the device does not report.
 *
 * A settled state before any PRECAPTURE is not trusted at once: some HALs still report the pre-trigger state on
 * the trigger's own result and enter PRECAPTURE a frame or two later, and firing then skips the pre-flash metering.
 * Only [SETTLED_RESULTS] settled results in a row, with no PRECAPTURE among them, count as a device that skips the
 * sequence. The caller adds a timeout for a sequence that never settles.
 */
class PrecaptureWatch {
    private var sawPrecapture = false
    private var settledInRow = 0

    /**
     * Feed the AE state of the trigger's own result and of every result after it; results arrive in frame order,
     * so none of them predates the trigger. Returns true once the still may fire.
     */
    fun onResult(aeState: Int?): Boolean {
        if (aeState == null) return true
        if (aeState == AE_STATE_PRECAPTURE) { sawPrecapture = true; return false }
        if (sawPrecapture) return true
        val settled = aeState == AE_STATE_CONVERGED || aeState == AE_STATE_FLASH_REQUIRED || aeState == AE_STATE_LOCKED
        settledInRow = if (settled) settledInRow + 1 else 0
        return settledInRow >= SETTLED_RESULTS
    }

    companion object {
        /** About 100 ms at 30 fps: long enough for a late PRECAPTURE, short against the 3 s timeout. */
        const val SETTLED_RESULTS = 3
        // CaptureResult.CONTROL_AE_STATE_* values, copied so this class stays free of android imports.
        const val AE_STATE_CONVERGED = 2
        const val AE_STATE_LOCKED = 3
        const val AE_STATE_FLASH_REQUIRED = 4
        const val AE_STATE_PRECAPTURE = 5
    }
}
