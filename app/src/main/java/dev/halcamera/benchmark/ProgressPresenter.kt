package dev.halcamera.benchmark

import dev.halcamera.metrics.MetricExtractor
import java.util.Locale
import kotlin.math.roundToInt

/** The live preview numbers of the progress screen (docs/PLAN-BenchMarker-v0.3.md 8.3). */
data class LiveStats(val frames: Int, val intervalP50Ms: Double?, val stalls: Int)

/**
 * Running preview statistics while a run is in progress.
 *
 * These numbers are an in-progress readout, never a stored metric: H.1 and H.5 are recomputed by [RunAssembler]
 * from the observation window alone, against each frame's own SENSOR_FRAME_DURATION and after the warm-up drop,
 * so the live stall count can differ from the one in the run JSON. Keeping the live path separate is what lets
 * it stay cheap enough to update on the main thread while the camera is running.
 */
class LiveFrameStats {
    private val intervals = ArrayList<Double>()
    private var lastSensorNs: Long? = null
    private var frames = 0

    /** A frame whose interval is [STALL_RATIO] times the median counts as a stall, as in MetricExtractor. */
    fun frame(sensorNs: Long?) {
        frames++
        if (sensorNs == null || sensorNs <= 0L) return
        val previous = lastSensorNs
        if (previous != null && sensorNs > previous) intervals += (sensorNs - previous) / 1e6
        lastSensorNs = sensorNs
    }

    fun reset() {
        intervals.clear()
        lastSensorNs = null
        frames = 0
    }

    /**
     * Sorting on every frame would be wasted work, so the statistics are computed here instead and the screen
     * calls this on a timer rather than once per frame.
     */
    fun snapshot(): LiveStats {
        val p50 = MetricExtractor.percentile(intervals, 0.5)
        val stalls = if (p50 == null) 0 else intervals.count { it > STALL_RATIO * p50 }
        return LiveStats(frames, p50, stalls)
    }

    companion object {
        const val STALL_RATIO = 1.5
    }
}

/** The six-phase progress screen of 8.3. Pure, so the phase weights and the bar are testable without a device. */
object ProgressPresenter {

    const val PHASE_COUNT = 6

    /**
     * The bar is deliberately short. U+2588 is absent from Roboto Mono, so every cell is drawn by a fallback
     * font whose advance is wider than the monospace one and grows with the user's display size setting. At the
     * twenty cells this started with, the line wrapped on a Galaxy S25+ and the percentage ended up on a line of
     * its own, which reads as a broken layout rather than as progress.
     */
    const val BAR_WIDTH = 14

    /**
     * Seconds per phase from the time budget of 3.3. The bar is weighted by them rather than by phase count
     * because the six phases are nothing like equal: the observation window alone is longer than the ten launch
     * cycles, and a bar that jumped a sixth at its start would read as stalled for the next ten seconds.
     *
     * THREE_A weighs nothing on purpose. 3A convergence is measured inside the observation window (3.2), so the
     * fourth step marks the moment its value is settled and costs no extra time.
     */
    val PHASE_SECONDS: Map<BenchmarkRunner.Phase, Double> = mapOf(
        BenchmarkRunner.Phase.CAMERA_OPEN to 7.0,
        BenchmarkRunner.Phase.FIRST_PREVIEW to 4.0,
        BenchmarkRunner.Phase.PREVIEW_STABILITY to 10.0,
        BenchmarkRunner.Phase.THREE_A to 0.0,
        BenchmarkRunner.Phase.STILL_CAPTURE to 4.0,
        BenchmarkRunner.Phase.CAMERA_CLOSE to 0.5
    )

    private val TOTAL_SECONDS = PHASE_SECONDS.values.sum()

    /** "1 / 6  Camera Open  3/10" — the launch cycle counter only appears while the launch cycles are running. */
    fun headline(phase: BenchmarkRunner.Phase, iteration: Int, total: Int): String {
        val step = phase.ordinal + 1
        val suffix = if (countsLaunchCycles(phase, total)) "  ${iteration + 1}/$total" else ""
        return "$step / $PHASE_COUNT  ${phaseText(phase)}$suffix"
    }

    fun percent(phase: BenchmarkRunner.Phase, iteration: Int, total: Int): Int {
        val before = BenchmarkRunner.Phase.values().takeWhile { it != phase }.sumOf { PHASE_SECONDS[it] ?: 0.0 }
        val elapsed = before + (PHASE_SECONDS[phase] ?: 0.0) * launchFraction(phase, iteration, total)
        return ((elapsed / TOTAL_SECONDS) * 100).roundToInt().coerceIn(0, 100)
    }

    /**
     * [BenchmarkRunner.progress] reports `iteration = total = launchIterations` for every phase after the launch
     * cycles, because the observation session is the eleventh open. Taken at face value that reads as cycle
     * 11/10 and drives the bar to the end of each phase the moment it starts, so the counter is confined here to
     * the one phase it describes rather than changing the runner's contract, which other callers already read.
     */
    private fun countsLaunchCycles(phase: BenchmarkRunner.Phase, total: Int) =
        phase == BenchmarkRunner.Phase.CAMERA_OPEN && total > 0

    private fun launchFraction(phase: BenchmarkRunner.Phase, iteration: Int, total: Int): Double =
        if (countsLaunchCycles(phase, total)) (iteration.toDouble() / total).coerceIn(0.0, 1.0) else 0.0

    /**
     * The whole progress line. The percentage is placed first on purpose, because it is the part that has to
     * survive: if the line ever does outgrow the card, what gets cut off is a cell of the bar and not the number.
     */
    fun barLine(percent: Int, width: Int = BAR_WIDTH): String =
        String.format(Locale.US, "%3d%%  ", percent.coerceIn(0, 100)) + bar(percent, width)

    fun bar(percent: Int, width: Int = BAR_WIDTH): String {
        val filled = ((percent.coerceIn(0, 100) / 100.0) * width).roundToInt()
        return "█".repeat(filled) + "░".repeat(width - filled)
    }

    /** The four live numbers of 8.3. A number that cannot be read yet is a dash, never a zero. */
    fun statLines(stats: LiveStats, thermal: Int?): String = listOf(
        "interval p50" to (stats.intervalP50Ms?.let { String.format(Locale.US, "%.1f ms", it) } ?: "—"),
        "stalls" to stats.stalls.toString(),
        "frames" to stats.frames.toString(),
        "thermal" to (thermal?.toString() ?: "—")
    ).joinToString("\n") { (label, value) -> label.padEnd(LABEL_WIDTH) + value }

    private const val LABEL_WIDTH = 15

    fun phaseText(phase: BenchmarkRunner.Phase): String = when (phase) {
        BenchmarkRunner.Phase.CAMERA_OPEN -> "Camera Open"
        BenchmarkRunner.Phase.FIRST_PREVIEW -> "First Preview"
        BenchmarkRunner.Phase.PREVIEW_STABILITY -> "Preview Stability"
        BenchmarkRunner.Phase.THREE_A -> "3A Response"
        BenchmarkRunner.Phase.STILL_CAPTURE -> "Still Capture"
        BenchmarkRunner.Phase.CAMERA_CLOSE -> "Camera Close"
    }
}
