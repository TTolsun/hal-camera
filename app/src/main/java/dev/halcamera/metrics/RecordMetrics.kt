package dev.halcamera.metrics

import dev.halcamera.telemetry.Event

/** One capture result of a recording request, reduced to what the cadence metrics read. */
data class RecordFrame(val frame: Long?, val sensorNs: Long, val frameDurationNs: Long?)

/**
 * The cadence of one recording cycle: METRICS.md 3.2, 3.4 and 3.7. The two latencies 3.1 and 3.6 are not here,
 * because they are measured from the moments the engine marks around MediaRecorder.start() and stop() and never
 * from the frames.
 *
 * [comparedIntervals] and [excludedIntervals] are kept because 3.2 is a count whose meaning depends on how many
 * comparisons it had: two anomalies out of 240 intervals and two out of four are not the same observation, and a
 * missing result is a disturbance of the measurement rather than a frame the camera dropped.
 */
data class RecordCadence(
    val frames: Int,
    /** Valid adjacent sensor intervals in ms: consecutive frame numbers, increasing sensor timestamps. */
    val intervals: List<Double>,
    /** Intervals judged against the fixed cadence; never more than [intervals]. */
    val comparedIntervals: Int,
    /** Adjacent results that could not be compared, because a frame number is missing between them. */
    val excludedIntervals: Int,
    /** 3.2. Null when no segment ran at the profile's frame duration, and then [anomalyUnknownReason] says why. */
    val anomalyCount: Int?,
    val anomalyUnknownReason: UnknownReason?,
    /** 3.4, results per complete one-second window of the sensor clock. */
    val windowFpsP50: Double?,
    val windowFpsMin: Double?,
    val windows: Int,
    /** 3.7, population standard deviation (ddof = 0) and nearest-rank p95 of [intervals], in ms. */
    val jitterStdDevMs: Double?,
    val jitterP95Ms: Double?
)

/**
 * Recording cadence from telemetry events (docs/METRICS.md 3, docs/PLAN-Recording-v0.1.md 7). Pure Kotlin with
 * no Android dependency, like the rest of this package: it turns events into numbers and knows nothing about how
 * they are compared or displayed.
 *
 * Everything here is measured on the sensor clock, so it stays valid even where SENSOR_INFO_TIMESTAMP_SOURCE is
 * UNKNOWN and sensor times may not be compared with app times (METRICS.md 0.1).
 */
object RecordMetrics {

    /** 3.4 starts counting windows this long after the first recording frame, skipping the start-up cadence. */
    const val STEADY_SKIP_MS = 3_000L

    /** 3.4 counts results per window of this length on the sensor clock. */
    const val WINDOW_MS = 1_000L

    /** 3.2 counts an interval as an anomaly above this multiple of the reference frame duration. */
    const val ANOMALY_RATIO = 1.5

    /**
     * How far a result's own SENSOR_FRAME_DURATION may sit from the profile's reference and still count as the
     * same fixed cadence. Devices report the exact nanosecond value (33,333,333 ns for 30 fps), so the tolerance
     * only absorbs rounding, never a genuinely different cadence.
     */
    const val CADENCE_TOLERANCE = 0.05

    /**
     * The recording results of one cycle, oldest first. Selected by request tag rather than by time: the tag is
     * set on the recording requests themselves, so a preview frame from the preparation, or a late result of the
     * previous cycle, can never be counted as one of this cycle's frames.
     */
    fun frames(events: List<Event>, session: String, tag: String): List<RecordFrame> =
        events.asSequence()
            .filter { it.session == session && it.kind == "capture_result" && it.values["requestTag"] == tag }
            .mapNotNull { e -> e.sensorNs?.let { RecordFrame(e.frame, it, (e.values["frameDurationNs"] as? Number)?.toLong()) } }
            .sortedBy { it.sensorNs }
            .toList()

    /**
     * [expectedIntervalMs] is the reference interval of the profile's recording frame rate (1000 / fps). Null
     * when the profile pins no single rate, and 3.2 is then not measurable rather than judged against a guess.
     */
    fun cadence(frames: List<RecordFrame>, expectedIntervalMs: Double?): RecordCadence {
        val intervals = ArrayList<Double>(frames.size)
        // An interval is only a cadence observation when nothing is missing between its two results. A gap in
        // the frame numbers means the measurement lost a result, which METRICS.md 3.2 separates from a camera
        // that actually took longer for the frame it did deliver.
        var excluded = 0
        var compared = 0
        var anomalies = 0
        for ((a, b) in frames.zipWithNext()) {
            if (b.sensorNs <= a.sensorNs) { excluded++; continue }
            if (a.frame == null || b.frame == null || b.frame != a.frame + 1) { excluded++; continue }
            val intervalMs = (b.sensorNs - a.sensorNs) / 1e6
            intervals += intervalMs
            // The fixed rule applies only where the frame the interval starts from ran at the reference
            // duration. An AE change that lengthens the frame duration makes its own segment, and that segment
            // is left out of the count instead of reporting every one of its frames as an anomaly [S5].
            val ownMs = a.frameDurationNs?.div(1e6)
            if (expectedIntervalMs == null || ownMs == null) continue
            if (kotlin.math.abs(ownMs - expectedIntervalMs) > expectedIntervalMs * CADENCE_TOLERANCE) continue
            compared++
            if (intervalMs > ANOMALY_RATIO * expectedIntervalMs) anomalies++
        }
        val anomalyReason = when {
            compared > 0 -> null
            intervals.isEmpty() -> UnknownReason.NOT_MEASURABLE
            // Frames arrived, but none of them ran at the profile's frame duration.
            else -> UnknownReason.CADENCE_CHANGED
        }
        val windows = windowCounts(frames)
        return RecordCadence(
            frames = frames.size,
            intervals = intervals,
            comparedIntervals = compared,
            excludedIntervals = excluded,
            anomalyCount = if (anomalyReason == null) anomalies else null,
            anomalyUnknownReason = anomalyReason,
            windowFpsP50 = MetricExtractor.percentile(windows, 0.5),
            windowFpsMin = windows.minOrNull(),
            windows = windows.size,
            jitterStdDevMs = MetricExtractor.stdDev(intervals),
            jitterP95Ms = MetricExtractor.percentile(intervals, 0.95)
        )
    }

    /**
     * 3.4: results per complete one-second window, counted on the sensor clock from [STEADY_SKIP_MS] after the
     * first recording frame. The trailing partial window is dropped, because a window that the recording ended
     * in the middle of would report a frame rate the camera never ran at.
     */
    fun windowCounts(frames: List<RecordFrame>): List<Double> {
        val first = frames.firstOrNull()?.sensorNs ?: return emptyList()
        val last = frames.last().sensorNs
        val base = first + STEADY_SKIP_MS * 1_000_000
        val window = WINDOW_MS * 1_000_000
        if (last < base + window) return emptyList()
        val out = ArrayList<Double>()
        var start = base
        while (start + window <= last) {
            val end = start + window
            out += frames.count { it.sensorNs >= start && it.sensorNs < end }.toDouble()
            start = end
        }
        return out
    }
}
