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
    val manual: ManualControls = ManualControls(),
    /**
     * Exposure bracketing (#178): the shutter takes three stills at EV steps around [evIndex]. It is a request to
     * the screen, not to the camera; no capture request carries it.
     */
    val bracket: Boolean = false,
) {
    /**
     * Drops what the camera or the capture mode cannot do. Auto and always flash exist for stills only: a
     * recording has no precapture to fire them, so video mode keeps just off and torch.
     */
    fun coerce(support: LiveControlSupport, video: Boolean, composited: Boolean = false): LiveControls = copy(
        evIndex = if (manual.exposure != null) 0 else support.evRange?.let { evIndex.coerceIn(it) } ?: 0,
        aeLock = aeLock && support.aeLock && manual.exposure == null,
        afLock = afLock && support.afLock && manual.focusDiopters == null,
        flash = if (flash in support.flashModes(video || composited || manual.exposure != null)) flash else FlashMode.OFF,
        bracket = bracket && !video && !composited && support.evRange != null && manual.exposure == null,
    )

    /**
     * A flash still needs the AE precapture sequence first so the metering sees the pre-flash. With AE locked the
     * exposure is already fixed and a precapture trigger would re-meter it, so the still fires without one.
     */
    val needsPrecapture: Boolean get() = (flash == FlashMode.AUTO || flash == FlashMode.ON) && !aeLock && manual.exposure == null
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

    /** The notice after [AeRelock] locked a rebuilt session again; null while the difference is within tolerance. */
    fun relockNotice(deltaEv: Double?): String? {
        if (deltaEv == null || kotlin.math.abs(deltaEv) <= AeRelock.TOLERANCE_EV) return null
        val amount = String.format(Locale.US, "%.1f", kotlin.math.abs(deltaEv))
        return "Exposure locked again · $amount EV ${if (deltaEv > 0) "brighter" else "darker"}"
    }
}

/**
 * AE lock across a rebuilt capture session (#184). CONTROL_AE_LOCK holds whatever exposure AE has now, and
 * Camera2 has no key that carries a locked exposure into a new session. A lock on a new session's first request
 * therefore froze that session's initial, unconverged exposure: on the S25+ a dark scene went from ISO 4274 to
 * ISO 2990 when a recording started.
 *
 * So a rebuilt session starts unlocked ([waiting]), AE meters the same scene again, and the lock goes back on after
 * [SETTLED_RESULTS] settled results in a row, or when the caller's timeout gives up on AE settling. One settled
 * result is not trusted: a HAL may carry the old session's CONVERGED into the new one's first frames before it
 * meters the new streams, as PrecaptureWatch notes for the precapture trigger. The exposure the lock held
 * (exposure time × ISO) is remembered from LOCKED results, and the first LOCKED result after the relock is compared
 * with it. The two may still differ: the recording frame rate caps the exposure time at the frame duration, and
 * AE may meter a new stream set differently. [LiveControlText.relockNotice] tells the user when it does.
 *
 * Carrying the old values over with AE off (SENSOR_EXPOSURE_TIME and SENSOR_SENSITIVITY) would keep the exposure
 * exactly, but would stop EV steps from working while locked. The explicit Manual mode is a separate choice.
 */
class AeRelock {
    data class Exposure(val timeNs: Long, val iso: Int)

    sealed interface Step {
        /** Nothing to send. */
        data object None : Step
        /** AE settled on the rebuilt session: send the repeating request again with the lock on. */
        data object Relock : Step
        /** The relock took effect. [deltaEv] is log2(after / before), null without an exposure from before. */
        data class Relocked(val before: Exposure?, val after: Exposure, val deltaEv: Double?) : Step
    }

    private var held: Exposure? = null
    private var comparing = false
    private var settledInRow = 0

    /** Bumped per rebuilt session; the caller's timeout carries it so an older session's timeout does nothing. */
    var generation = 0
        private set

    /** True while a rebuilt session must run with AE unlocked; the requests then leave CONTROL_AE_LOCK off. */
    var waiting = false
        private set

    /** A new capture session was configured. With the lock on it runs unlocked until [onResult] relocks it. */
    fun sessionRebuilt(locked: Boolean): Int {
        waiting = locked
        comparing = false
        settledInRow = 0
        return ++generation
    }

    /** The relock request could not be sent; wait for settled results again rather than stay silently unlocked. */
    fun relockNotSent() {
        waiting = true
        comparing = false
        settledInRow = 0
    }

    /** The user turned the lock on or off. On locks at once, at the exposure the user sees, so nothing waits. */
    fun lockChanged(locked: Boolean) {
        waiting = false
        comparing = false
        if (!locked) held = null
    }

    /** Feed every LIVE repeating result in frame order. */
    fun onResult(aeState: Int?, exposure: Exposure?): Step {
        if (waiting) {
            if (aeState == null) { relock(); return Step.Relock } // no AE state reported: nothing to wait for
            val settled = aeState == AE_STATE_CONVERGED || aeState == AE_STATE_FLASH_REQUIRED || aeState == AE_STATE_LOCKED
            settledInRow = if (settled) settledInRow + 1 else 0
            return if (settledInRow >= SETTLED_RESULTS) { relock(); Step.Relock } else Step.None
        }
        // A device without AE state or without exposure values cannot be compared; the lock itself still applies.
        if (aeState != AE_STATE_LOCKED || exposure == null) return Step.None
        val before = held
        held = exposure
        if (!comparing) return Step.None
        comparing = false
        return Step.Relocked(before, exposure, before?.let { deltaEv(it, exposure) })
    }

    /** The timeout ran out while AE had not settled. Returns whether the caller should send the lock anyway. */
    fun timedOut(gen: Int): Boolean {
        if (gen != generation || !waiting) return false
        relock()
        return true
    }

    private fun relock() {
        waiting = false
        comparing = true
    }

    companion object {
        /** One third of a stop: the usual EV step, below which two exposures look the same. */
        const val TOLERANCE_EV = 1.0 / 3
        /** Dark scenes take AE a second or more; past this the lock goes on at whatever AE has. */
        const val TIMEOUT_MS = 2000L
        /** Two frames, about 67 ms at 30 fps: past a stale first result, short against the timeout. */
        const val SETTLED_RESULTS = 2
        // CaptureResult.CONTROL_AE_STATE_* values, as in PrecaptureWatch.
        const val AE_STATE_CONVERGED = 2
        const val AE_STATE_LOCKED = 3
        const val AE_STATE_FLASH_REQUIRED = 4

        fun deltaEv(before: Exposure, after: Exposure): Double? {
            val a = before.timeNs.toDouble() * before.iso
            val b = after.timeNs.toDouble() * after.iso
            return if (a > 0 && b > 0) kotlin.math.ln(b / a) / kotlin.math.ln(2.0) else null
        }
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

/**
 * The EV of each bracket shot (#178): the requested EV first, then darker, then brighter, [SPAN_EV] apart and kept
 * inside what the camera supports. A step the range cuts short stays in the plan at the range's end, so the files
 * still come in threes and the request id says which EV each one asked for.
 */
object BracketPlan {
    const val SPAN_EV = 2.0

    fun evIndices(base: Int, range: IntRange, step: Double): List<Int> {
        val steps = if (step > 0) kotlin.math.max(1, kotlin.math.round(SPAN_EV / step).toInt()) else 0
        return listOf(base, base - steps, base + steps).map { it.coerceIn(range) }
    }

    /** "ev+0.0", "ev-2.0": ASCII for the request id, which ends up in file metadata. */
    fun tag(index: Int, step: Double): String = String.format(Locale.US, "ev%+.1f", index * step)
}
