package dev.cameradoctor.diagnosis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** H.10 frame_interval_jitter and the steady-frame population exposed for the BenchMarker evaluator. */
class MetricExtractorJitterTest {
    private fun frames(n: Int, intervals: (Int) -> Double?): List<MetricExtractor.FrameObservation> = (0 until n).map { i ->
        MetricExtractor.FrameObservation(
            frame = i.toLong(), startedAtNs = i * 33_333_333L, resultAtNs = i * 33_333_333L + 55_000_000L, sensorNs = i * 33_333_333L,
            intervalMs = if (i == 0) null else intervals(i), ownDurationMs = 33.3, partialMs = 55.0, bufferMs = 60.0,
            ae = 2, af = 2, awb = 2, iso = 100.0, exposureNs = 8_000_000.0, afMode = 4
        )
    }

    @Test fun constantIntervalsHaveZeroJitter() {
        val o = MetricExtractor().observe(frames(50) { 33.3 }, warmupFrames = 5)
        assertEquals(0.0, o.intervalJitterMs!!, 1e-9)
        assertEquals(45, o.steadyFrames.size)
        assertEquals(50, o.frames.size)
    }

    @Test fun alternatingIntervalsGiveTheirPopulationStandardDeviation() {
        val o = MetricExtractor().observe(frames(300) { i -> if (i % 2 == 0) 30.0 else 36.0 }, warmupFrames = 5)
        assertEquals(3.0, o.intervalJitterMs!!, 1e-3)
    }

    @Test fun noIntervalsMeansNoJitter() {
        val o = MetricExtractor().observe(frames(20) { null })
        assertNull(o.intervalJitterMs)
        assertEquals(20, o.steadyFrames.size)
    }
}
