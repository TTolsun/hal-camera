package dev.halcamera.benchmark.domain

import dev.halcamera.metrics.UnknownReason

/**
 * 1.9 and 1.10 (docs/METRICS.md 1, #165): the two ends of the open latency that 1.1 leaves out.
 *
 * 1.1 is the median of the measured cycles and drops the first cycle as warm-up. The S25+ stress runs of #123
 * showed that both ends carry the signal the median hides: a camera held by another app costs 200–275 ms in the
 * first open only, and a warm device spikes one or two of nine cycles to 260–296 ms while the median stays at
 * 10 ms. 1.9 is that first open; 1.10 is the slowest measured open.
 *
 * Both are read from what 1.1 already stores (its warm-up samples and its max), so a run written before the two
 * existed gets them when it is read, and a new run's baseline set can be made of older runs.
 */
object LaunchTail {
    const val FIRST_OPEN = "1.9"
    const val OPEN_MAX = "1.10"
    val IDS = listOf(FIRST_OPEN, OPEN_MAX)

    /** The two metrics derived from 1.1. [open] null (a run without launch cycles) gives two NOT_RUN entries. */
    fun metrics(open: BenchmarkMetric?): List<BenchmarkMetric> {
        val first = open?.excludedWarmup?.firstOrNull()
        val max = open?.max
        val missing = open?.unknownReason?.takeIf { it != UnknownReason.NO_BASELINE } ?: UnknownReason.NOT_RUN
        return listOf(
            BenchmarkMetric(
                id = FIRST_OPEN, category = Category.LAUNCH, unit = "ms",
                value = first, p50 = first, p95 = first, min = first, max = first,
                sampleCount = if (first == null) 0 else 1,
                samples = first?.let { listOf(it) }, excludedWarmup = emptyList(),
                unknownReason = if (first == null) UnknownReason.NOT_RUN else UnknownReason.NO_BASELINE
            ),
            BenchmarkMetric(
                id = OPEN_MAX, category = Category.LAUNCH, unit = "ms",
                value = max, p50 = max, p95 = max, min = max, max = max,
                sampleCount = if (max == null) 0 else open.sampleCount,
                // The cycles are 1.1's samples already; storing them twice would only let the two copies disagree.
                samples = null, excludedWarmup = null,
                unknownReason = if (max == null) missing else UnknownReason.NO_BASELINE
            )
        )
    }

    /**
     * [metrics] with 1.9 and 1.10 inserted after 1.1 when they are absent. A run that has no 1.1 entry at all is
     * returned unchanged: it was written by a profile without a launch stage, and inventing two NOT_RUN rows for
     * it would change what its file says.
     */
    fun withTail(metrics: List<BenchmarkMetric>): List<BenchmarkMetric> {
        if (metrics.any { it.id in IDS }) return metrics
        val at = metrics.indexOfFirst { it.id == "1.1" }
        if (at < 0) return metrics
        return metrics.take(at + 1) + metrics(metrics[at]) + metrics.drop(at + 1)
    }

    /**
     * A stored run as the app reads it: the file boundary applies this after decoding, so the codec itself still
     * returns exactly what the file says and a decoded run encodes back to the same JSON.
     */
    fun backfill(run: BenchmarkRun): BenchmarkRun =
        withTail(run.metrics).let { if (it === run.metrics) run else run.copy(metrics = it) }
}
