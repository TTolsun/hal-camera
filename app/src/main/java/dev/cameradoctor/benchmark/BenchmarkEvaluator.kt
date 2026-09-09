package dev.cameradoctor.benchmark

import dev.cameradoctor.diagnosis.MetricExtractor
import dev.cameradoctor.diagnosis.UnknownReason

/** One warm-reopen launch cycle as measured by the runner (M2). Millisecond values are null when the step did not complete. */
data class LaunchCycle(
    val iteration: Int,
    val warmup: Boolean,
    val openMs: Double?,
    val configureMs: Double?,
    val firstStartedMs: Double?,
    val yuvProxyMs: Double?,
    val previewTotalMs: Double?,
    val closeMs: Double?,
    val failed: Boolean = false,
    val timestampsNs: Map<String, Long> = emptyMap()
) {
    fun toJsonMap(): Map<String, Any?> = mapOf(
        "iteration" to iteration, "warmup" to warmup, "open_ms" to openMs, "configure_ms" to configureMs,
        "first_started_ms" to firstStartedMs, "yuv_proxy_ms" to yuvProxyMs, "preview_total_ms" to previewTotalMs,
        "close_ms" to closeMs, "failed" to failed, "timestamps_ns" to timestampsNs.mapValues { it.value.toString() }
    )
}

/** One still capture: submit time and the two callbacks that answer it. */
data class StillSample(
    val index: Int,
    val warmup: Boolean,
    val submitNs: Long,
    val imageNs: Long?,
    val resultNs: Long?
) {
    val latencyMs: Double? get() = imageNs?.let { (it - submitNs) / 1e6 }
    val resultLatencyMs: Double? get() = resultNs?.let { (it - submitNs) / 1e6 }

    fun toJsonMap(): Map<String, Any?> = mapOf(
        "index" to index, "warmup" to warmup, "submit_ns" to submitNs.toString(),
        "image_ns" to imageNs?.toString(), "result_ns" to resultNs?.toString(),
        "latency_ms" to latencyMs, "result_latency_ms" to resultLatencyMs
    )
}

/** Nearest-rank statistics (METRICS.md 0.2). With n = 9 the p95 is the maximum; the UI labels it accordingly. */
data class Stats(val p50: Double?, val p95: Double?, val min: Double?, val max: Double?, val n: Int) {
    companion object {
        fun of(xs: List<Double>) = Stats(
            MetricExtractor.percentile(xs, 0.5), MetricExtractor.percentile(xs, 0.95), xs.minOrNull(), xs.maxOrNull(), xs.size
        )

        /** Population standard deviation (ddof = 0), the definition METRICS.md 3.7 uses for jitter. */
        fun stdDev(xs: List<Double>): Double? {
            if (xs.isEmpty()) return null
            val mean = xs.average()
            return kotlin.math.sqrt(xs.sumOf { (it - mean) * (it - mean) } / xs.size)
        }
    }
}

/** The metric ids a camera2-standard run reports, in display order (docs/PLAN-BenchMarker-v0.3.md chapter 4). */
object BenchmarkMetrics {
    val LAUNCH = listOf("1.1", "1.2", "1.3", "1.8", "1.6", "1.7")
    val PREVIEW = listOf("H.1", "H.2", "H.3", "H.4", "H.10")
    val CAPTURE = listOf("2.2", "2.3", "2.5")
    val STABILITY = listOf("H.5", "H.9", "2.7")
    val THREE_A = listOf("H.6", "H.7", "H.8")
    val ALL: List<String> = LAUNCH + PREVIEW + CAPTURE + STABILITY + THREE_A
}

/**
 * Turns the runner's raw samples into BenchmarkMetric statistics. No judgement, no thresholds: value, p50, p95,
 * min, max and sample count only. Comparison fields stay UNKNOWN(no_baseline) until the RegressionDetector (M4).
 */
class BenchmarkEvaluator(private val profile: BenchmarkProfile) {

    data class Input(
        val cycles: List<LaunchCycle>,
        val stills: List<StillSample>,
        val observation: MetricExtractor.Observation?,
        val callbackFailures: Int,
        /** Set when the endpoint never reached the observation window; H.x then become NOT_RUN. */
        val observed: Boolean = observation != null
    )

    fun evaluate(input: Input): List<BenchmarkMetric> {
        val out = ArrayList<BenchmarkMetric>()
        val valid = input.cycles.filter { !it.warmup && !it.failed }
        val warm = input.cycles.filter { it.warmup }
        fun cycle(id: String, pick: (LaunchCycle) -> Double?) =
            bounded(id, valid.mapNotNull(pick), warm.mapNotNull(pick))
        out += cycle("1.1") { it.openMs }
        out += cycle("1.2") { it.configureMs }
        out += cycle("1.3") { it.firstStartedMs }
        out += cycle("1.8") { it.yuvProxyMs }
        out += cycle("1.6") { it.previewTotalMs }
        out += cycle("1.7") { it.closeMs }

        val obs = input.observation
        val steady = obs?.steadyFrames ?: emptyList()
        val intervals = steady.mapNotNull { it.intervalMs }
        val partials = steady.mapNotNull { it.partialMs }
        val buffers = steady.mapNotNull { it.bufferMs }
        out += windowed("H.1", intervals, input.observed) { MetricExtractor.percentile(it, 0.5) }
        out += windowed("H.2", intervals, input.observed) { MetricExtractor.percentile(it, 0.95) }
        out += windowed("H.3", partials, input.observed) { MetricExtractor.percentile(it, 0.5) }
        out += windowed("H.4", buffers, input.observed) { MetricExtractor.percentile(it, 0.5) }
        out += windowed("H.10", intervals, input.observed) { Stats.stdDev(it) }

        val stillsValid = input.stills.filter { !it.warmup }
        val stillsWarm = input.stills.filter { it.warmup }
        out += bounded("2.2", stillsValid.mapNotNull { it.latencyMs }, stillsWarm.mapNotNull { it.latencyMs })
        out += bounded("2.3", stillsValid.mapNotNull { it.resultLatencyMs }, stillsWarm.mapNotNull { it.resultLatencyMs })
        val gaps = input.stills.sortedBy { it.index }.zipWithNext { a, b -> (b.submitNs - a.submitNs) / 1e6 }
        val gapWarm = if (profile.excludeFirst) gaps.take(1) else emptyList()
        out += bounded("2.5", gaps.drop(gapWarm.size), gapWarm)

        out += if (!input.observed || obs == null) notRun("H.5")
        else count("H.5", obs.stallCount, intervals.size)
        out += if (!input.observed) notRun("H.9")
        else count("H.9", input.callbackFailures, steady.size + input.stills.size)
        out += notRun("2.7")

        for (id in BenchmarkMetrics.THREE_A) out += threeA(id, obs, input.observed)
        return out
    }

    // Display metadata comes from the benchmark package's own catalog so diagnosis and benchmark do not depend on each other.
    private fun category(id: String) = BenchmarkMetricCatalog.info(id)?.category ?: Category.RESOURCE
    private fun unit(id: String) = BenchmarkMetricCatalog.info(id)?.unit ?: "ms"

    /** Metrics with a profile-bounded sample count: samples are stored, value is p50. */
    private fun bounded(id: String, xs: List<Double>, warm: List<Double>): BenchmarkMetric {
        val s = Stats.of(xs)
        return BenchmarkMetric(
            id = id, category = category(id), unit = unit(id),
            value = s.p50, p50 = s.p50, p95 = s.p95, min = s.min, max = s.max, sampleCount = s.n,
            samples = xs, excludedWarmup = warm,
            unknownReason = if (xs.isEmpty()) UnknownReason.NOT_RUN else UnknownReason.NO_BASELINE
        )
    }

    /** Observation-window metrics: samples are not duplicated into the metric; events keep the raw data. */
    private fun windowed(id: String, xs: List<Double>, observed: Boolean, aggregate: (List<Double>) -> Double?): BenchmarkMetric {
        if (!observed) return notRun(id)
        val s = Stats.of(xs)
        // Same rule as MetricExtractor.sample(): below the minimum frame count the value is unknown, the stats stay.
        val v = if (xs.size < ValidityFlags.MIN_OBSERVED_FRAMES) null else aggregate(xs)
        return BenchmarkMetric(
            id = id, category = category(id), unit = unit(id),
            value = v, p50 = s.p50, p95 = s.p95, min = s.min, max = s.max, sampleCount = s.n,
            samples = null, excludedWarmup = null,
            unknownReason = when {
                xs.isEmpty() -> UnknownReason.NOT_MEASURABLE
                xs.size < ValidityFlags.MIN_OBSERVED_FRAMES -> UnknownReason.INSUFFICIENT_SAMPLES
                else -> UnknownReason.NO_BASELINE
            }
        )
    }

    private fun count(id: String, n: Int, sampleCount: Int) = BenchmarkMetric(
        id = id, category = category(id), unit = "count",
        value = n.toDouble(), p50 = null, p95 = null, min = null, max = null, sampleCount = sampleCount,
        samples = null, excludedWarmup = null, unknownReason = UnknownReason.NO_BASELINE
    )

    private fun notRun(id: String) = BenchmarkMetric(
        id = id, category = category(id), unit = unit(id),
        value = null, p50 = null, p95 = null, min = null, max = null, sampleCount = 0,
        samples = null, excludedWarmup = null, unknownReason = UnknownReason.NOT_RUN
    )

    /** 3A convergence comes from MetricExtractor's samples: a single duration from the first result, or a timeout. */
    private fun threeA(id: String, obs: MetricExtractor.Observation?, observed: Boolean): BenchmarkMetric {
        if (!observed || obs == null) return notRun(id)
        val s = obs.samples.firstOrNull { it.id == id } ?: return notRun(id)
        val v = s.value
        return BenchmarkMetric(
            id = id, category = Category.THREE_A, unit = "ms",
            value = v, p50 = v, p95 = v, min = v, max = v, sampleCount = if (v == null) 0 else 1,
            samples = if (v == null) emptyList() else listOf(v), excludedWarmup = emptyList(),
            timeout = s.timeout,
            unknownReason = s.unknownReason ?: UnknownReason.NO_BASELINE
        )
    }
}
