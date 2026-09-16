package dev.halcamera.cts.onoff

import dev.halcamera.cts.Verdict
import java.util.Locale

/**
 * The decisions of the FastOnOff case: a camera is opened and closed two ways, [ITERATIONS] times each, and
 * every cycle must deliver a first preview frame whose metadata is sound.
 *
 * STANDARD: open → preview session → first frame → close. The ordinary CTS open, with the CameraTestUtils
 * timeouts on every step.
 * FAST: open → close the moment onOpened arrives, no session → open again → preview session → first frame →
 * close. This is the "fast on/off" a user produces by leaving the camera app at once; the HAL must survive it
 * and the reopen must behave like a standard open.
 *
 * Pure Kotlin: [FastOnOffRunner] measures the cycles on the device and this object judges them. Not CTS; the
 * wording follows CameraTestUtils where a check has a counterpart there.
 */
object FastOnOffRules {
    const val SOURCE = "custom#FastOnOff"
    const val ITERATIONS = 5

    enum class Kind { STANDARD, FAST }

    /** The first completed result of a cycle, as the rules need it. Clocks are elapsedRealtimeNanos. */
    data class FrameMeta(
        val sensorTimestampNs: Long?,
        val frameNumber: Long,
        /** SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME, so the sensor clock is comparable with the app clock. */
        val realtimeSource: Boolean,
        val openedAtNs: Long,
        val completedAtNs: Long
    )

    /**
     * What the runner measured in one cycle, in ms. A null phase was never reached; [error] carries the
     * exception that stopped the cycle. For FAST, [fastOpenMs] and [fastCloseMs] are the immediate pair and
     * [openMs] is the reopen that follows.
     */
    data class Cycle(
        val kind: Kind,
        val openMs: Double? = null,
        val configureMs: Double? = null,
        val firstFrameMs: Double? = null,
        val closeMs: Double? = null,
        val closedInTime: Boolean = true,
        val fastOpenMs: Double? = null,
        val fastCloseMs: Double? = null,
        val fastClosedInTime: Boolean = true,
        val frame: FrameMeta? = null,
        val error: String? = null
    )

    fun stepId(kind: Kind, iteration: Int): String = "${kind.name.lowercase(Locale.US)}_$iteration"

    /** The metadata checks on the first result; empty when it is sound. */
    fun metadataFailures(frame: FrameMeta): List<String> {
        val out = ArrayList<String>()
        val ts = frame.sensorTimestampNs
        if (ts == null || ts <= 0) out += "SENSOR_TIMESTAMP must be present and positive in the first result (got $ts)"
        if (frame.frameNumber < 0) out += "Frame number must not be negative (got ${frame.frameNumber})"
        if (ts != null && ts > 0 && frame.realtimeSource && (ts < frame.openedAtNs || ts > frame.completedAtNs))
            out += "SENSOR_TIMESTAMP $ts ns is outside the open..completed window [${frame.openedAtNs}, ${frame.completedAtNs}] ns"
        return out
    }

    /** One cycle's verdict and detail lines; the first line is the timing summary when anything was measured. */
    fun judge(cycle: Cycle): Pair<Verdict, List<String>> {
        val failures = ArrayList<String>()
        cycle.error?.let { failures += it }
        if (cycle.kind == Kind.FAST && !cycle.fastClosedInTime) failures += "Timeout waiting for the camera to close after the immediate close"
        if (cycle.error == null) {
            val frame = cycle.frame
            if (frame == null) failures += "No capture result was completed" else failures += metadataFailures(frame)
        }
        if (!cycle.closedInTime) failures += "Timeout waiting for the camera to close"
        val details = listOfNotNull(summary(cycle)) + failures
        return (if (failures.isEmpty()) Verdict.PASS else Verdict.FAIL) to details
    }

    /** The timings a row should still show, or null when nothing was measured. */
    fun summary(cycle: Cycle): String? {
        val parts = ArrayList<String>()
        if (cycle.kind == Kind.FAST) {
            cycle.fastOpenMs?.let { parts += "fast open ${ms(it)}" }
            cycle.fastCloseMs?.let { parts += "fast close ${ms(it)}" }
            cycle.openMs?.let { parts += "reopen ${ms(it)}" }
        } else {
            cycle.openMs?.let { parts += "open ${ms(it)}" }
        }
        cycle.configureMs?.let { parts += "configure ${ms(it)}" }
        cycle.firstFrameMs?.let { parts += "first frame ${ms(it)}" }
        cycle.closeMs?.let { parts += "close ${ms(it)}" }
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    /**
     * The closing comparison of a camera: the median first-frame time after a standard open against the
     * median after a fast reopen. FAIL only when one side never reached a frame, so there is nothing to compare.
     */
    fun compare(cycles: List<Cycle>): Pair<Verdict, List<String>> {
        val standard = cycles.filter { it.kind == Kind.STANDARD && it.error == null }.mapNotNull { it.firstFrameMs }
        val fast = cycles.filter { it.kind == Kind.FAST && it.error == null }.mapNotNull { it.firstFrameMs }
        if (standard.isEmpty() || fast.isEmpty())
            return Verdict.FAIL to listOf("Nothing to compare: standard ${standard.size} of ${cycles.count { it.kind == Kind.STANDARD }}, fast ${fast.size} of ${cycles.count { it.kind == Kind.FAST }} cycles reached a first frame")
        val s = median(standard); val f = median(fast)
        return Verdict.PASS to listOf("first frame median · standard ${ms(s)} (${standard.size}) · fast reopen ${ms(f)} (${fast.size}) · delta ${signed(f - s)}")
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private fun ms(v: Double) = String.format(Locale.US, "%.1f ms", v)
    private fun signed(v: Double) = String.format(Locale.US, "%+.1f ms", v)
}
