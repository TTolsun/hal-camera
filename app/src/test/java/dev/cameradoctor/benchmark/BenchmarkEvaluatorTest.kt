package dev.cameradoctor.benchmark

import dev.cameradoctor.diagnosis.MetricExtractor
import dev.cameradoctor.diagnosis.UnknownReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkEvaluatorTest {
    private val profile = BenchmarkProfile.CAMERA2_STANDARD_V1
    private val evaluator = BenchmarkEvaluator(profile)

    /** 10 cycles, the first a warm-up. Open latency 141..149 ms for the valid ones. */
    private fun cycles(): List<LaunchCycle> = (0 until 10).map { i ->
        val warm = i == 0
        LaunchCycle(i, warm, if (warm) 200.0 else 140.0 + i, 40.0 + i, 90.0 + i, 120.0 + i, 400.0 + i, 80.0 + i)
    }

    /** 10 stills every 400 ms; image latency 150 + 10 i ms, result latency 140 + 10 i ms. */
    private fun stills(): List<StillSample> = (0 until 10).map { i ->
        val submit = i * 400_000_000L
        StillSample(i, i == 0, submit, submit + (150 + 10 * i) * 1_000_000L, submit + (140 + 10 * i) * 1_000_000L)
    }

    private fun frames(n: Int, ae: Int = 2, intervals: (Int) -> Double): List<MetricExtractor.FrameObservation> = (0 until n).map { i ->
        MetricExtractor.FrameObservation(
            frame = i.toLong(), startedAtNs = i * 33_333_333L, resultAtNs = i * 33_333_333L + 55_000_000L, sensorNs = i * 33_333_333L,
            intervalMs = if (i == 0) null else intervals(i), ownDurationMs = 33.3, partialMs = 55.0, bufferMs = 60.0,
            ae = ae, af = 2, awb = 2, iso = 100.0, exposureNs = 8_000_000.0, afMode = 4
        )
    }

    private fun observation(n: Int = 300, ae: Int = 2, intervals: (Int) -> Double = { 33.3 }) =
        MetricExtractor().observe(frames(n, ae, intervals), warmupFrames = 5)

    private fun evaluate(obs: MetricExtractor.Observation?, failures: Int = 0) =
        evaluator.evaluate(BenchmarkEvaluator.Input(cycles(), stills(), obs, failures)).associateBy { it.id }

    @Test fun launchStatisticsExcludeWarmupAndP95IsMaxAtNineSamples() {
        val open = evaluate(observation())["1.1"]!!
        assertEquals(9, open.sampleCount)
        assertEquals(145.0, open.p50!!, 1e-9)
        assertEquals(open.max!!, open.p95!!, 1e-9)
        assertEquals(149.0, open.max!!, 1e-9)
        assertEquals(141.0, open.min!!, 1e-9)
        assertEquals(listOf(200.0), open.excludedWarmup)
        assertEquals(9, open.samples!!.size)
        assertEquals(Category.LAUNCH, open.category)
        assertEquals("ms", open.unit)
        assertEquals(RegressionState.UNKNOWN, open.regression)
        assertEquals(UnknownReason.NO_BASELINE, open.unknownReason)
    }

    @Test fun stillStatisticsAndShotToShotIntervals() {
        val m = evaluate(observation())
        val capture = m["2.2"]!!
        assertEquals(9, capture.sampleCount)
        assertEquals(200.0, capture.p50!!, 1e-6)
        assertEquals(listOf(150.0), capture.excludedWarmup)
        assertEquals(190.0, m["2.3"]!!.p50!!, 1e-6)
        val s2s = m["2.5"]!!
        assertEquals(8, s2s.sampleCount)
        assertEquals(400.0, s2s.p50!!, 1e-6)
        assertEquals(listOf(400.0), s2s.excludedWarmup)
        assertEquals(Category.CAPTURE, s2s.category)
    }

    @Test fun previewMetricsUseSteadyFramesAndDoNotStoreSamples() {
        val m = evaluate(observation(300) { i -> if (i % 2 == 0) 30.0 else 36.0 })
        val h1 = m["H.1"]!!
        assertEquals(295, h1.sampleCount)
        assertNull(h1.samples)
        assertNull(h1.excludedWarmup)
        // 147 intervals of 30 ms and 148 of 36 ms: nearest-rank p50 is the 148th sorted value, 36 ms.
        assertEquals(36.0, h1.value!!, 1e-9)
        assertEquals(30.0, h1.min!!, 1e-9)
        assertEquals(36.0, m["H.2"]!!.value!!, 1e-9)
        assertEquals(55.0, m["H.3"]!!.value!!, 1e-9)
        assertEquals(60.0, m["H.4"]!!.value!!, 1e-9)
        val jitter = m["H.10"]!!
        assertEquals(3.0, jitter.value!!, 1e-3)
        assertEquals(295, jitter.sampleCount)
        assertEquals(Category.PREVIEW, jitter.category)
        assertEquals(UnknownReason.NO_BASELINE, jitter.unknownReason)
    }

    @Test fun withoutObservationTheWindowMetricsAreNotRun() {
        val m = evaluator.evaluate(BenchmarkEvaluator.Input(cycles(), emptyList(), null, 0)).associateBy { it.id }
        for (id in BenchmarkMetrics.PREVIEW + listOf("H.5") + BenchmarkMetrics.THREE_A) {
            assertNull(id, m[id]!!.value)
            assertEquals(id, UnknownReason.NOT_RUN, m[id]!!.unknownReason)
            assertEquals(id, 0, m[id]!!.sampleCount)
        }
        assertEquals(UnknownReason.NOT_RUN, m["2.2"]!!.unknownReason)
        assertEquals(UnknownReason.NOT_RUN, m["2.7"]!!.unknownReason)
        assertEquals(9, m["1.1"]!!.sampleCount)
    }

    @Test fun threeAConvergenceCarriesTimeout() {
        val ae = evaluate(observation(ae = 1))["H.6"]!!
        assertTrue(ae.timeout)
        assertEquals(1, ae.sampleCount)
        assertEquals(listOf(ae.value!!), ae.samples)
        assertEquals(Category.THREE_A, ae.category)
        val converged = evaluate(observation())["H.6"]!!
        assertTrue(!converged.timeout)
        assertEquals(0.0, converged.value!!, 0.0)
    }

    @Test fun orderAndCategoriesMatchTheCatalog() {
        val ms = evaluator.evaluate(BenchmarkEvaluator.Input(cycles(), stills(), observation(), 2))
        assertEquals(BenchmarkMetrics.ALL, ms.map { it.id })
        // RESOURCE is the fallback for an id missing from MetricCatalog; every benchmark id must be catalogued.
        assertTrue(ms.none { it.category == Category.RESOURCE })
        val h9 = ms.first { it.id == "H.9" }
        assertEquals(2.0, h9.value!!, 0.0)
        assertEquals("count", h9.unit)
        assertEquals(295 + 10, h9.sampleCount)
        val h5 = ms.first { it.id == "H.5" }
        assertEquals(0.0, h5.value!!, 0.0)
        assertEquals(295, h5.sampleCount)
        assertEquals(Category.STABILITY, h5.category)
    }

    @Test fun unobservedWindowMarksCountsNotRun() {
        val ms = evaluator.evaluate(BenchmarkEvaluator.Input(cycles(), stills(), null, 0, observed = false))
        val h9 = ms.first { it.id == "H.9" }
        assertNull(h9.value)
        assertEquals(UnknownReason.NOT_RUN, h9.unknownReason)
        assertEquals(0, h9.sampleCount)
    }

    @Test fun insufficientWindowFramesNullTheValueButKeepStats() {
        val h1 = evaluate(observation(n = 10))["H.1"]!!
        assertNull(h1.value)
        assertEquals(UnknownReason.INSUFFICIENT_SAMPLES, h1.unknownReason)
        assertEquals(5, h1.sampleCount)
        assertNotNull(h1.p50)
    }
}
