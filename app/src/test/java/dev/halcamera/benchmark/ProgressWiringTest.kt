package dev.halcamera.benchmark

import dev.halcamera.check.CameraEndpoint
import dev.halcamera.check.LensRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives a real [BenchmarkRunner] and feeds its callbacks to [ProgressPresenter] exactly as BenchmarkActivity
 * does. The presenter tests alone cannot catch a wiring mistake: they choose the arguments, while the runner
 * reports `iteration = total = launchIterations` for every phase after the launch cycles, which read literally
 * would show cycle 11/10 and drive the bar to the end of each phase the moment it starts (PR #26 review).
 */
class ProgressWiringTest {

    private data class Shown(val headline: String, val percent: Int)

    private val profile = BenchmarkProfile.CAMERA2_STANDARD_V1
    private val endpoint = CameraEndpoint(
        logicalCameraId = "0", physicalCameraId = null, role = LensRole.MAIN, facing = 1,
        independentlyOpenable = true, selectableByZoom = false, exposedToCameraX = null,
        equivalentFocalMm = 24.0, timestampSource = 1, hardwareLevel = 3, zoomRatioMin = 0.5f, zoomRatioMax = 10f
    )

    private val clock = FakeClock()
    private val scheduler = FakeScheduler(clock)
    private val driver = FakeDriver()
    private val shown = ArrayList<Shown>()
    private val phaseOf = HashMap<BenchmarkRunner.Phase, MutableList<Shown>>()

    private val listener = object : BenchmarkRunner.Listener {
        override fun onProgress(phase: BenchmarkRunner.Phase, step: BenchmarkRunner.Step, iteration: Int, total: Int) {
            val entry = Shown(
                ProgressPresenter.headline(phase, iteration, total),
                ProgressPresenter.percent(phase, iteration, total)
            )
            shown += entry
            phaseOf.getOrPut(phase) { ArrayList() } += entry
        }

        override fun onFinished(result: BenchmarkRunner.Result) = Unit
    }

    private fun runToEnd() {
        val r = BenchmarkRunner(driver, scheduler, { clock.ns }, profile, endpoint, "20260910-120000-000", BenchmarkRunner.Config(), listener)
        r.start()
        repeat(profile.launchIterations) { cycle(r) }
        openOnly(r)
        scheduler.advanceMs(profile.warmupMs)
        scheduler.advanceMs(profile.observeMs)
        repeat(profile.stillCount) { still(r, it) }
        close(r)
    }

    private fun cycle(r: BenchmarkRunner) { openOnly(r); close(r) }

    private fun openOnly(r: BenchmarkRunner) {
        val s = r.currentSession
        clock.advanceMs(100)
        r.signal(s, BenchmarkRunner.Signal.OPENED, clock.ns)
        r.mark(s, "configure_call", clock.ns)
        clock.advanceMs(40)
        r.signal(s, BenchmarkRunner.Signal.CONFIGURED, clock.ns)
        r.mark(s, "repeating_call", clock.ns)
        clock.advanceMs(60)
        r.firstStarted(s, clock.ns)
        r.signal(s, BenchmarkRunner.Signal.FIRST_FRAME, clock.ns)
    }

    private fun close(r: BenchmarkRunner) {
        clock.advanceMs(30)
        r.signal(r.currentSession, BenchmarkRunner.Signal.CLOSED, clock.ns)
    }

    private fun still(r: BenchmarkRunner, index: Int) {
        val s = r.currentSession
        clock.advanceMs(5)
        val tag = "still-$index"
        r.stillSubmitted(s, tag, clock.ns)
        val sensorNs = clock.ns
        clock.advanceMs(120)
        r.stillResult(s, tag, sensorNs, clock.ns)
        clock.advanceMs(60)
        r.stillImage(s, sensorNs, clock.ns)
    }

    @Test fun theLaunchCounterNeverExceedsTheCycleCount() {
        runToEnd()
        val counted = shown.map { it.headline }.filter { it.contains("/${profile.launchIterations}") }
        assertTrue("a launch counter is shown at all", counted.isNotEmpty())
        assertTrue("every counter belongs to Camera Open", counted.all { it.contains("Camera Open") })
        assertFalse("no cycle 11 of 10", shown.any { it.headline.contains("11/${profile.launchIterations}") })
    }

    @Test fun noPhaseAfterTheLaunchCyclesShowsACounter() {
        runToEnd()
        val later = shown.map { it.headline }.filterNot { it.contains("Camera Open") }
        assertTrue("later phases have a plain headline", later.isNotEmpty())
        assertTrue(later.none { it.contains("/") && !it.contains(" / 6") })
    }

    @Test fun eachPhaseStartsAtItsOwnShareOfTheBudget() {
        runToEnd()
        // Without the launch-counter guard these would be 43, 82 and 98: each phase would open already finished.
        assertEquals(27, phaseOf[BenchmarkRunner.Phase.FIRST_PREVIEW]!!.first().percent)
        assertEquals(43, phaseOf[BenchmarkRunner.Phase.PREVIEW_STABILITY]!!.first().percent)
        assertEquals(82, phaseOf[BenchmarkRunner.Phase.STILL_CAPTURE]!!.first().percent)
    }

    @Test fun theBarNeverGoesBackwards() {
        runToEnd()
        val percents = shown.map { it.percent }
        assertEquals(percents.sorted(), percents)
        // The last progress event is the start of the close phase, not 100 %: the runner reports a phase when it
        // begins, and the screen has moved on to the result by the time the close would have finished.
        assertEquals(ProgressPresenter.percent(BenchmarkRunner.Phase.CAMERA_CLOSE, 0, 0), percents.last())
    }

    @Test fun allSixPhasesReachTheScreen() {
        runToEnd()
        assertEquals(BenchmarkRunner.Phase.values().toSet(), phaseOf.keys)
    }
}
