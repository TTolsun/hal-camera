package dev.halcamera.benchmark.domain

import dev.halcamera.benchmark.domain.BenchmarkRunFixture.metric
import dev.halcamera.benchmark.domain.BenchmarkRunFixture.run
import dev.halcamera.metrics.UnknownReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** 1.9 and 1.10 (#165), read off what 1.1 stores. */
class LaunchTailTest {

    /** 1.1 as the evaluator writes it: the median of the measured cycles, with the warm-up cycle kept apart. */
    private val open = metric("1.1", 10.0).copy(
        samples = listOf(9.0, 10.0, 11.0, 290.0), max = 290.0, sampleCount = 4, excludedWarmup = listOf(254.0)
    )

    @Test fun firstOpenIsTheWarmUpCycleAndOpenMaxTheSlowestMeasuredCycle() {
        val (first, max) = LaunchTail.metrics(open)
        assertEquals("1.9", first.id)
        assertEquals(254.0, first.value!!, 0.0)
        assertEquals(1, first.sampleCount)
        assertEquals("1.10", max.id)
        assertEquals(290.0, max.value!!, 0.0)
        assertEquals(4, max.sampleCount)
        // 1.1 already stores the cycles; a second copy could only disagree with it.
        assertNull(max.samples)
        assertEquals(Category.LAUNCH, max.category)
    }

    @Test fun aProfileWithoutAWarmUpCycleHasNoFirstOpen() {
        val (first, max) = LaunchTail.metrics(open.copy(excludedWarmup = emptyList()))
        assertNull(first.value)
        assertEquals(UnknownReason.NOT_RUN, first.unknownReason)
        assertEquals(290.0, max.value!!, 0.0)
    }

    @Test fun anOpenWithoutSamplesKeepsItsReason() {
        val (_, max) = LaunchTail.metrics(metric("1.1", null, unknownReason = UnknownReason.NOT_MEASURABLE))
        assertNull(max.value)
        assertEquals(UnknownReason.NOT_MEASURABLE, max.unknownReason)
    }

    @Test fun aStoredRunWithoutThemGetsThemRightAfterOpen() {
        val old = run(metrics = listOf(open, metric("1.2", 170.0)))
        val read = LaunchTail.backfill(old)
        assertEquals(listOf("1.1", "1.9", "1.10", "1.2"), read.metrics.map { it.id })
        assertEquals(254.0, read.metric("1.9")!!.value!!, 0.0)
        // A run that already has them, or has no launch stage, is returned as it is.
        assertSame(read, LaunchTail.backfill(read))
        val noLaunch = run(metrics = listOf(metric("2.2", 280.0)))
        assertSame(noLaunch, LaunchTail.backfill(noLaunch))
    }

    @Test fun theyAreComparedLikeOpenItself() {
        // Seen in #123: a camera held by another app costs its first open 198–275 ms; normal runs stay near 10 ms.
        val base = LaunchTail.backfill(run(runId = "20260910-100000-000", metrics = listOf(open.copy(excludedWarmup = listOf(12.9)))))
        val held = LaunchTail.backfill(run(runId = "20260910-110000-000", metrics = listOf(open)))
        val c = RegressionDetector.compare(base, held)
        assertEquals(RegressionState.REGRESSED, c.metric("1.9")!!.state)
        assertEquals(RegressionState.STABLE, c.metric("1.10")!!.state)
    }
}
