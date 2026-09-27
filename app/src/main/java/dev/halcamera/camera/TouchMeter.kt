package dev.halcamera.camera

import kotlin.math.min
import kotlin.math.roundToInt

/** A rectangle in camera pixel coordinates, free of android.graphics so the mapping below is JVM-tested. */
data class MeterRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Tap-to-focus and metering on the Live preview (#168): where a tap lands on the sensor, and which AF result
 * belongs to which tap.
 *
 * The engine first undoes the preview view's own transform, which leaves a point normalized to the upright
 * picture in the device's natural orientation. [toSensor] turns that into a point normalized to the sensor
 * readout, and [region] into the metering rectangle the request carries.
 */
object TouchMeter {
    /** A tap's AF scan that has reported nothing after this long counts as failed. */
    const val SCAN_TIMEOUT_MS = 3000L
    /** How long a tapped point stays before AF and AE go back to the whole frame. */
    const val HOLD_MS = 5000L
    /** Side of the metering square as a share of the visible picture's shorter side. */
    const val REGION_SHARE = 1.0 / 6

    /**
     * The preview shows the sensor readout rotated clockwise by SENSOR_ORIENTATION and, for a front camera,
     * mirrored left to right after that. This undoes both: mirror first, then rotate counter-clockwise.
     */
    fun toSensor(u: Double, v: Double, sensorOrientation: Int, front: Boolean): Pair<Double, Double> {
        var x = if (front) 1 - u else u
        var y = v
        repeat(((sensorOrientation % 360 + 360) % 360) / 90) { val nx = y; y = 1 - x; x = nx }
        return x to y
    }

    /**
     * The metering square around a sensor point. [base] is the rectangle the whole field of view maps to: the
     * active array (post-zoom when CONTROL_ZOOM_RATIO is in use) or the crop region below API 30. The preview
     * stream shows only a centre crop of it at [streamAspect] (width / height in sensor orientation), so the
     * point is placed in that crop. The square is clamped inside the crop, so it never meters what the preview
     * does not show.
     */
    fun region(sensorU: Double, sensorV: Double, base: MeterRect, streamAspect: Double): MeterRect {
        val baseAspect = base.width.toDouble() / base.height
        val visibleW = if (streamAspect > baseAspect) base.width.toDouble() else base.height * streamAspect
        val visibleH = if (streamAspect > baseAspect) base.width / streamAspect else base.height.toDouble()
        val visibleLeft = base.left + (base.width - visibleW) / 2
        val visibleTop = base.top + (base.height - visibleH) / 2
        val side = min(visibleW, visibleH) * REGION_SHARE
        val left = (visibleLeft + sensorU.coerceIn(0.0, 1.0) * visibleW - side / 2).coerceIn(visibleLeft, visibleLeft + visibleW - side)
        val top = (visibleTop + sensorV.coerceIn(0.0, 1.0) * visibleH - side / 2).coerceIn(visibleTop, visibleTop + visibleH - side)
        return MeterRect(left.roundToInt(), top.roundToInt(), (left + side).roundToInt(), (top + side).roundToInt())
    }
}

/**
 * When AE has metered a long-pressed point (#168 follow-up): the same rule as [AeRelock], two settled AE states in
 * a row, because the first result after the new region can still carry the old one's CONVERGED. A newer press gets
 * a new generation, so an older press never reports.
 */
class TouchExposureWatch {
    var generation = 0
        private set
    private var pending = false
    private var settledInRow = 0

    fun press(): Int { generation++; pending = true; settledInRow = 0; return generation }

    fun cancel() { generation++; pending = false }

    /** Every result after the press; true once, when AE has settled on the new region. */
    fun onResult(aeState: Int?): Boolean {
        if (!pending) return false
        val settled = aeState == null || aeState == AeRelock.AE_STATE_CONVERGED || aeState == AeRelock.AE_STATE_FLASH_REQUIRED
        settledInRow = if (settled) settledInRow + 1 else 0
        if (aeState != null && settledInRow < AeRelock.SETTLED_RESULTS) return false
        pending = false
        return true
    }

    /** AE did not settle in time; returns whether press [gen] still waited and should be taken as metered. */
    fun timedOut(gen: Int): Boolean {
        if (gen != generation || !pending) return false
        pending = false
        return true
    }
}

/** What the focus ring shows for one tap. */
enum class TouchPhase { SCANNING, FOCUSED, FAILED, METERED, DONE }

/**
 * Which AF result belongs to the latest tap. Every tap gets a new generation; a trigger result or a timeout
 * carrying an older one is ignored, so a quick second tap is never overwritten by the first tap's outcome.
 * Results are only read after the tap's own trigger capture has completed: repeating results still in flight
 * from before the tap can report FOCUSED_LOCKED from an earlier lock.
 */
class TouchFocusWatch {
    var generation = 0
        private set
    /** The latest tap has no outcome yet. */
    private var pending = false
    /** Its trigger capture has completed, so the results that follow are its own. */
    private var watching = false

    fun tap(): Int { generation++; pending = true; watching = false; return generation }

    /** Drops the current tap, for a camera switch, a session rebuild or the hold running out. */
    fun cancel() { generation++; pending = false; watching = false }

    /** The trigger capture of tap [gen] completed with [afState]. */
    fun triggerCompleted(gen: Int, afState: Int?): TouchPhase? {
        if (gen != generation || !pending) return null
        watching = true
        return onResult(afState)
    }

    /** Every repeating result after the trigger; null while AF is still scanning or nothing is being watched. */
    fun onResult(afState: Int?): TouchPhase? {
        if (!watching) return null
        val phase = when (afState) {
            null -> TouchPhase.FOCUSED // no AF state reported: nothing to wait for
            AF_STATE_FOCUSED_LOCKED -> TouchPhase.FOCUSED
            AF_STATE_NOT_FOCUSED_LOCKED -> TouchPhase.FAILED
            else -> return null
        }
        pending = false; watching = false
        return phase
    }

    /** The scan timeout of tap [gen] ran out. Returns whether it still counts as a failure. */
    fun timedOut(gen: Int): Boolean {
        if (gen != generation || !pending) return false
        pending = false; watching = false
        return true
    }

    companion object {
        // CaptureResult.CONTROL_AF_STATE_* values, copied so this class stays free of android imports.
        const val AF_STATE_FOCUSED_LOCKED = 4
        const val AF_STATE_NOT_FOCUSED_LOCKED = 5
    }
}
