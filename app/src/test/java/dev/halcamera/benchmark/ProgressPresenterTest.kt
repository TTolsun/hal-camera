package dev.halcamera.benchmark

import dev.halcamera.benchmark.BenchmarkRunner.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The progress screen contract of 8.3: the six phases, a time-weighted bar and the live preview numbers. */
class ProgressPresenterTest {

    // ---- headline ----

    @Test fun theHeadlineNumbersThePhaseOutOfSix() {
        assertEquals("2 / 6  First Preview", ProgressPresenter.headline(Phase.FIRST_PREVIEW, 0, 0))
        assertEquals("6 / 6  Camera Close", ProgressPresenter.headline(Phase.CAMERA_CLOSE, 0, 0))
    }

    @Test fun theLaunchCycleCounterAppearsOnlyWhereTheRunnerReportsOne() {
        assertEquals("1 / 6  Camera Open  3/10", ProgressPresenter.headline(Phase.CAMERA_OPEN, 2, 10))
        assertEquals("5 / 6  Still Capture", ProgressPresenter.headline(Phase.STILL_CAPTURE, 0, 0))
    }

    // ---- bar ----

    @Test fun theBarIsWeightedByTheTimeBudgetNotByPhaseCount() {
        // 3.3: the launch cycles are 7 s of a 25.5 s run, so entering the second phase is 27 %, not a sixth.
        assertEquals(0, ProgressPresenter.percent(Phase.CAMERA_OPEN, 0, 10))
        assertEquals(27, ProgressPresenter.percent(Phase.FIRST_PREVIEW, 0, 0))
        assertEquals(43, ProgressPresenter.percent(Phase.PREVIEW_STABILITY, 0, 0))
        assertEquals(98, ProgressPresenter.percent(Phase.CAMERA_CLOSE, 0, 0))
    }

    @Test fun theLaunchPhaseAdvancesWithItsCycles() {
        assertTrue(ProgressPresenter.percent(Phase.CAMERA_OPEN, 5, 10) > ProgressPresenter.percent(Phase.CAMERA_OPEN, 1, 10))
        assertTrue(ProgressPresenter.percent(Phase.CAMERA_OPEN, 9, 10) < ProgressPresenter.percent(Phase.FIRST_PREVIEW, 0, 0))
    }

    @Test fun theThreeAStepCostsNoTime() {
        // 3.2: 3A convergence is measured inside the observation window, so the fourth step only marks the
        // moment its value settles. A weight there would make the bar claim progress that never happened.
        assertEquals(ProgressPresenter.percent(Phase.THREE_A, 0, 0), ProgressPresenter.percent(Phase.STILL_CAPTURE, 0, 0))
    }

    @Test fun theBarIsAlwaysItsFullWidth() {
        assertEquals(ProgressPresenter.BAR_WIDTH, ProgressPresenter.bar(0).length)
        assertEquals(ProgressPresenter.BAR_WIDTH, ProgressPresenter.bar(100).length)
        assertEquals("█".repeat(ProgressPresenter.BAR_WIDTH), ProgressPresenter.bar(100))
        assertEquals("░".repeat(ProgressPresenter.BAR_WIDTH), ProgressPresenter.bar(0))
    }

    // ---- live numbers ----

    @Test fun aFrameWithoutASensorTimestampStillCounts() {
        val stats = LiveFrameStats()
        stats.frame(null)
        stats.frame(null)
        val snapshot = stats.snapshot()
        assertEquals(2, snapshot.frames)
        assertNull(snapshot.intervalP50Ms)
        assertEquals(0, snapshot.stalls)
    }

    @Test fun intervalsAndStallsComeFromTheSensorTimestamps() {
        val stats = LiveFrameStats()
        val base = 1_000_000_000L
        listOf(0L, 33_000_000L, 66_000_000L, 166_000_000L).forEach { stats.frame(base + it) }
        val snapshot = stats.snapshot()
        assertEquals(4, snapshot.frames)
        assertEquals(33.0, snapshot.intervalP50Ms!!, 0.001)
        // 100 ms is more than 1.5 x the 33 ms median, the same rule MetricExtractor applies to H.5.
        assertEquals(1, snapshot.stalls)
    }

    @Test fun resetDropsTheEarlierSession() {
        val stats = LiveFrameStats()
        stats.frame(1_000_000_000L)
        stats.frame(1_500_000_000L)
        stats.reset()
        stats.frame(2_000_000_000L)
        val snapshot = stats.snapshot()
        assertEquals(1, snapshot.frames)
        assertNull(snapshot.intervalP50Ms)
    }

    @Test fun aTimestampThatDoesNotAdvanceProducesNoInterval() {
        val stats = LiveFrameStats()
        stats.frame(1_000_000_000L)
        stats.frame(1_000_000_000L)
        assertNull(stats.snapshot().intervalP50Ms)
    }

    @Test fun theStatBlockShowsADashForANumberItCannotReadYet() {
        val lines = ProgressPresenter.statLines(LiveStats(0, null, 0), null).lines()
        assertTrue(lines[0].startsWith("interval p50"))
        assertTrue(lines[0].endsWith("—"))
        assertTrue(lines[3].endsWith("—"))
        assertEquals(4, lines.size)
    }

    @Test fun theStatBlockKeepsTheTenthOfAMillisecondThatSeparates333From334() {
        val lines = ProgressPresenter.statLines(LiveStats(214, 33.34, 0), 1).lines()
        assertTrue(lines[0].endsWith("33.3 ms"))
        assertTrue(lines[2].endsWith("214"))
        assertTrue(lines[3].endsWith("1"))
    }
}
