package dev.cameradoctor.diagnosis

import dev.cameradoctor.telemetry.Event

/**
 * Computes the observation metrics H.1 to H.9 (docs/PRODUCT-v0.2.md 4.2) from Telemetry events of one session
 * inside a time window. Pure Kotlin, no Android dependency.
 *
 * Aggregation is chosen by the caller: Auto Check uses p50/p95 over a 10 s window; the live health strip uses the
 * window maximum so a single late frame is visible (spike detection). Both go through the same thresholds.
 */
class MetricExtractor(private val minSamples: Int = 15) {

    enum class Aggregation { PERCENTILE, MAX }

    data class FrameObservation(
        val frame: Long?,
        val startedAtNs: Long?,
        val resultAtNs: Long,
        val sensorNs: Long?,
        val intervalMs: Double?,
        val ownDurationMs: Double?,
        val partialMs: Double?,
        val bufferMs: Double?,
        val ae: Int?, val af: Int?, val awb: Int?,
        val iso: Double?, val exposureNs: Double?
    ) {
        /** Stall against own SENSOR_FRAME_DURATION when present; otherwise against the baseline interval only. */
        fun stalled(baselineIntervalMs: Double?): Boolean {
            val i = intervalMs ?: return false
            val own = ownDurationMs
            return if (own != null) i > 1.5 * own && (baselineIntervalMs == null || i > 1.5 * baselineIntervalMs)
            else baselineIntervalMs != null && i > 1.5 * baselineIntervalMs
        }
    }

    data class Observation(
        val frames: List<FrameObservation>,
        val samples: List<MetricSample>,
        val intervalP50: Double?,
        val durationP50: Double?,
        val partialP50: Double?,
        val exposureLoadP50: Double?,
        val stallCount: Int,
        val worstStall: FrameObservation?,
        val worstPartial: FrameObservation?,
        val threeAStable: Boolean,
        val afSupported: Boolean,
        val last: FrameObservation?
    ) {
        val n: Int get() = frames.size
    }

    fun frames(events: List<Event>, session: String, fromNs: Long, toNs: Long): List<FrameObservation> {
        val mine = events.filter { it.session == session }
        val started = mine.filter { it.kind == "capture_started" && it.frame != null }.associateBy { it.frame!! }
        val images = mine.filter { it.kind == "image_available" && it.sensorNs != null }.groupBy { it.sensorNs!! }
        val results = mine.filter { it.kind == "capture_result" && it.atNs in fromNs..toNs }
        var previousSensor: Long? = null
        // Interval needs the frame before the window too, so seed from the last result before fromNs.
        mine.lastOrNull { it.kind == "capture_result" && it.atNs < fromNs }?.let { previousSensor = it.sensorNs }
        val out = ArrayList<FrameObservation>(results.size)
        for (r in results) {
            val start = r.frame?.let { started[it] }
            // Telemetry's FrameTracker already derives intervalMs from consecutive sensor timestamps; trust it when present
            // and only fall back to our own sensor difference for events recorded without it.
            val interval = num(r, "intervalMs") ?: when {
                r.sensorNs != null && previousSensor != null -> (r.sensorNs - previousSensor!!) / 1e6
                else -> null
            }
            val buffer = r.sensorNs?.let { s -> images[s]?.firstOrNull() }?.let { img -> start?.let { (img.atNs - it.atNs) / 1e6 } }
            out += FrameObservation(
                frame = r.frame, startedAtNs = start?.atNs, resultAtNs = r.atNs, sensorNs = r.sensorNs,
                intervalMs = interval, ownDurationMs = num(r, "frameDurationNs")?.div(1e6),
                partialMs = start?.let { (r.atNs - it.atNs) / 1e6 }, bufferMs = buffer,
                ae = int(r, "ae"), af = int(r, "af"), awb = int(r, "awb"),
                iso = num(r, "iso"), exposureNs = num(r, "exposureNs")
            )
            if (r.sensorNs != null) previousSensor = r.sensorNs
        }
        return out
    }

    fun observe(events: List<Event>, session: String, fromNs: Long, toNs: Long,
                baselineIntervalMs: Double? = null, aggregation: Aggregation = Aggregation.PERCENTILE,
                fixedFpsExpectedMs: Double? = null, warmupFrames: Int = 0): Observation =
        observe(frames(events, session, fromNs, toNs), baselineIntervalMs, aggregation, fixedFpsExpectedMs, warmupFrames)

    /**
     * [warmupFrames]: the first frames of a freshly started stream carry a start-up cadence artefact (on Galaxy S25+
     * frame #1 arrives 66.7 ms after frame #0 with a 33.3 ms duration, on every camera). Those frames are excluded from
     * the interval, partial, buffer and stall metrics (H.1 to H.5) but still count for 3A convergence (H.6 to H.8),
     * which is measured from the first result. Same idea as the warm-up exclusion in METRICS.md 0.2.
     */
    fun observe(frames: List<FrameObservation>, baselineIntervalMs: Double? = null,
                aggregation: Aggregation = Aggregation.PERCENTILE, fixedFpsExpectedMs: Double? = null,
                warmupFrames: Int = 0): Observation {
        val steady = if (frames.size > warmupFrames) frames.drop(warmupFrames) else frames
        val intervals = steady.mapNotNull { it.intervalMs }
        val durations = steady.mapNotNull { it.ownDurationMs }
        val partials = steady.mapNotNull { it.partialMs }
        val buffers = steady.mapNotNull { it.bufferMs }
        val loads = steady.mapNotNull { f -> f.iso?.let { i -> f.exposureNs?.let { e -> i * e / 1e6 } } }
        val intervalP50 = percentile(intervals, 0.5)
        val durationP50 = percentile(durations, 0.5)
        val partialP50 = percentile(partials, 0.5)
        val stalledFrames = steady.filter { it.stalled(baselineIntervalMs) }
        val worstStall = stalledFrames.maxByOrNull { it.intervalMs ?: 0.0 }
        val worstPartial = steady.filter { it.partialMs != null }.maxByOrNull { it.partialMs!! }
        val last = frames.lastOrNull()
        val afSupported = frames.any { it.af != null }
        val aeStable = last?.ae.let { it == 2 || it == 3 || it == 4 }
        val afStable = last?.af.let { it == null || it == 0 || it == 2 || it == 4 }
        val awbStable = last?.awb.let { it == 2 || it == 3 }
        val insufficient = frames.size < minSamples

        fun rep(xs: List<Double>): Double? = when (aggregation) {
            Aggregation.PERCENTILE -> percentile(xs, 0.5)
            Aggregation.MAX -> xs.maxOrNull()
        }
        fun sample(id: String, xs: List<Double>, expected: Double? = null, fixed: Boolean = false, unsupported: Boolean = false) = MetricSample(
            id = id, value = if (insufficient || xs.isEmpty()) null else rep(xs), p95 = percentile(xs, 0.95), n = xs.size,
            unknownReason = when {
                unsupported -> UnknownReason.UNSUPPORTED
                insufficient -> UnknownReason.INSUFFICIENT_SAMPLES
                xs.isEmpty() -> UnknownReason.NOT_MEASURABLE
                else -> null
            },
            expectedMs = expected, fixedCadence = fixed
        )
        // H.1 cadence bound: fixed fps uses 1e9/fps; variable fps uses the frame's own duration (physics).
        val h1Expected = fixedFpsExpectedMs ?: durationP50
        val samples = listOf(
            sample("H.1", intervals, expected = h1Expected, fixed = fixedFpsExpectedMs != null),
            sample("H.2", intervals).let { it.copy(value = if (it.value == null) null else percentile(intervals, 0.95)) },
            sample("H.3", partials),
            sample("H.4", buffers),
            MetricSample("H.5", if (insufficient) null else stalledFrames.size.toDouble(), n = frames.size,
                unknownReason = if (insufficient) UnknownReason.INSUFFICIENT_SAMPLES else null),
            convergence("H.6", frames, insufficient) { it.ae == 2 || it.ae == 3 || it.ae == 4 },
            if (afSupported) convergence("H.7", frames, insufficient) { it.af == 2 || it.af == 4 }
            else MetricSample("H.7", null, n = frames.size, unknownReason = UnknownReason.UNSUPPORTED),
            convergence("H.8", frames, insufficient) { it.awb == 2 || it.awb == 3 }
        )
        return Observation(frames, samples, intervalP50, durationP50, partialP50, percentile(loads, 0.5),
            stalledFrames.size, worstStall, worstPartial, aeStable && afStable && awbStable, afSupported, last)
    }

    /** H.9 needs failure events, which are not frame observations; count them separately. */
    fun failureSample(events: List<Event>, session: String, fromNs: Long, toNs: Long): MetricSample {
        val count = events.count { it.session == session && (it.kind == "capture_failed" || it.kind == "buffer_lost") && it.atNs in fromNs..toNs }
        return MetricSample("H.9", count.toDouble(), n = count)
    }

    private fun convergence(id: String, frames: List<FrameObservation>, insufficient: Boolean, stable: (FrameObservation) -> Boolean): MetricSample {
        if (insufficient || frames.isEmpty()) return MetricSample(id, null, n = frames.size, unknownReason = UnknownReason.INSUFFICIENT_SAMPLES)
        val first = frames.first().resultAtNs
        val converged = frames.firstOrNull(stable)
        return if (converged == null) MetricSample(id, (frames.last().resultAtNs - first) / 1e6, n = frames.size, timeout = true)
        else MetricSample(id, (converged.resultAtNs - first) / 1e6, n = frames.size)
    }

    companion object {
        /** Nearest-rank percentile as defined in METRICS.md 0.2: ceil(p × n)-th of the sorted values. */
        fun percentile(xs: List<Double>, p: Double): Double? {
            if (xs.isEmpty()) return null
            val sorted = xs.sorted()
            val rank = kotlin.math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
        private fun num(e: Event, key: String) = (e.values[key] as? Number)?.toDouble()
        private fun int(e: Event, key: String) = (e.values[key] as? Number)?.toInt()
    }
}
