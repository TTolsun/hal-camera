package dev.cameradoctor.benchmark

import dev.cameradoctor.check.CameraEndpoint
import dev.cameradoctor.check.LensRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Virtual clock so warm-up and observation windows pass without the test waiting for them. */
private class FakeClock {
    var ns = 1_000_000_000L
    fun advanceMs(ms: Long) { ns += ms * 1_000_000 }
}

private class FakeScheduler(private val clock: FakeClock) : BenchmarkRunner.Scheduler {
    class Task(val dueNs: Long, val action: () -> Unit)

    private val tasks = ArrayList<Task>()

    override fun after(delayMs: Long, action: () -> Unit): Any =
        Task(clock.ns + delayMs * 1_000_000, action).also { tasks += it }

    override fun cancel(token: Any) { tasks.remove(token) }

    /** Runs every task due within [ms], in time order, moving the clock to each task's due time. */
    fun advanceMs(ms: Long) {
        val target = clock.ns + ms * 1_000_000
        while (true) {
            val next = tasks.filter { it.dueNs <= target }.minByOrNull { it.dueNs } ?: break
            tasks.remove(next)
            clock.ns = next.dueNs
            next.action()
        }
        if (clock.ns < target) clock.ns = target
    }

    val pending: Int get() = tasks.size
}

private class FakeDriver : BenchmarkRunner.Driver {
    val opens = ArrayList<String>()
    val closes = ArrayList<String>()
    var stills = 0

    override fun open(endpoint: CameraEndpoint, session: String) { opens += session }
    override fun still(session: String) { stills++ }
    override fun close(session: String) { closes += session }
}

private class RecordingListener : BenchmarkRunner.Listener {
    val phases = ArrayList<BenchmarkRunner.Phase>()
    var result: BenchmarkRunner.Result? = null

    override fun onProgress(phase: BenchmarkRunner.Phase, step: BenchmarkRunner.Step, iteration: Int, total: Int) {
        if (phases.lastOrNull() != phase) phases += phase
    }

    override fun onFinished(result: BenchmarkRunner.Result) { this.result = result }
}

class BenchmarkRunnerTest {

    private val endpoint = CameraEndpoint(
        logicalCameraId = "0", physicalCameraId = null, role = LensRole.MAIN, facing = 1,
        independentlyOpenable = true, selectableByZoom = false, exposedToCameraX = null,
        equivalentFocalMm = 24.0, timestampSource = 1, hardwareLevel = 3, zoomRatioMin = 0.5f, zoomRatioMax = 10f
    )
    private val profile = BenchmarkProfile.CAMERA2_STANDARD_V1

    private val clock = FakeClock()
    private val scheduler = FakeScheduler(clock)
    private val driver = FakeDriver()
    private val listener = RecordingListener()

    private fun runner(config: BenchmarkRunner.Config = BenchmarkRunner.Config()) =
        BenchmarkRunner(driver, scheduler, { clock.ns }, profile, endpoint, "20260910-120000-000", config, listener)

    /** Drives one open through OPENED, CONFIGURED and FIRST_FRAME the way Camera2Engine's events would. */
    private fun completeOpen(r: BenchmarkRunner, openMs: Long = 100, configureMs: Long = 40, firstFrameMs: Long = 60) {
        val s = r.currentSession
        clock.advanceMs(openMs)
        r.signal(s, BenchmarkRunner.Signal.OPENED, clock.ns)
        r.mark(s, "configure_call", clock.ns)
        clock.advanceMs(configureMs)
        r.signal(s, BenchmarkRunner.Signal.CONFIGURED, clock.ns)
        r.mark(s, "repeating_call", clock.ns)
        clock.advanceMs(firstFrameMs)
        r.firstStarted(s, clock.ns)
        r.signal(s, BenchmarkRunner.Signal.FIRST_FRAME, clock.ns)
    }

    private fun completeClose(r: BenchmarkRunner, closeMs: Long = 30) {
        val s = r.currentSession
        clock.advanceMs(closeMs)
        r.signal(s, BenchmarkRunner.Signal.CLOSED, clock.ns)
    }

    /** The full happy path: launchIterations cycles, then the observation session, window, stills and close. */
    private fun runToEnd(r: BenchmarkRunner) {
        r.start()
        repeat(profile.launchIterations) {
            completeOpen(r)
            completeClose(r)
        }
        completeOpen(r)                       // the observation session stays open
        scheduler.advanceMs(profile.warmupMs)
        scheduler.advanceMs(profile.observeMs)
        repeat(profile.stillCount) {
            clock.advanceMs(150)
            r.stillResult(r.currentSession, clock.ns)
            r.signal(r.currentSession, BenchmarkRunner.Signal.STILL_RECEIVED, clock.ns)
        }
        completeClose(r)
    }

    @Test
    fun `full run produces one extra open and the promised sample counts`() {
        val r = runner()
        runToEnd(r)
        assertNotNull("the run finished", listener.result)
        val result = listener.result!!

        // 10 launch cycles plus the observation session.
        assertEquals(profile.launchIterations + 1, driver.opens.size)
        assertEquals(profile.launchIterations, result.cycles.size)
        assertEquals(profile.stillCount, result.stills.size)
        assertEquals(BenchmarkRunner.Step.DONE, r.step)
        assertNull(result.aborted)
        assertNull(result.hardFailure)

        // The warm-up cycle and the warm-up still are excluded, leaving exactly what the profile promises.
        assertEquals(1, result.cycles.count { it.warmup })
        assertEquals(profile.expectedLaunchSamples, result.validLaunchSamples)
        assertEquals(profile.expectedStillSamples, result.validStillSamples)
        assertEquals(0, scheduler.pending)
    }

    @Test
    fun `launch timings come from the marks the driver reports`() {
        val r = runner()
        runToEnd(r)
        val cycle = listener.result!!.cycles.first()
        assertEquals(100.0, cycle.openMs!!, 0.001)
        assertEquals(40.0, cycle.configureMs!!, 0.001)
        assertEquals(60.0, cycle.firstStartedMs!!, 0.001)
        assertEquals(60.0, cycle.yuvProxyMs!!, 0.001)
        assertEquals(200.0, cycle.previewTotalMs!!, 0.001)
        assertEquals(30.0, cycle.closeMs!!, 0.001)
    }

    @Test
    fun `observation window brackets the frames used for the cadence metrics`() {
        val r = runner()
        runToEnd(r)
        val result = listener.result!!
        assertTrue(result.observed)
        assertEquals(profile.warmupMs * 1_000_000, result.observeStartNs!! - result.observeFirstFrameNs!!)
        assertEquals(profile.observeMs * 1_000_000, result.observeEndNs!! - result.observeStartNs!!)
        assertEquals(driver.opens.last(), result.observeSession)
    }

    @Test
    fun `one failed cycle is recorded and the run continues`() {
        val r = runner()
        r.start()
        // First cycle: the camera never reports OPENED, so the open timeout fires.
        scheduler.advanceMs(BenchmarkRunner.Config().openTimeoutMs)
        completeClose(r)
        repeat(profile.launchIterations - 1) { completeOpen(r); completeClose(r) }
        completeOpen(r)
        scheduler.advanceMs(profile.warmupMs + profile.observeMs)
        repeat(profile.stillCount) {
            clock.advanceMs(150)
            r.signal(r.currentSession, BenchmarkRunner.Signal.STILL_RECEIVED, clock.ns)
        }
        completeClose(r)

        val result = listener.result!!
        assertEquals(profile.launchIterations, result.cycles.size)
        assertTrue(result.cycles.first().failed)
        assertNull("a failed cycle keeps no close latency", result.cycles.first().closeMs)
        assertEquals("OPEN_timeout", result.hardFailure)
        assertNull("a single failed cycle does not abort the run", result.aborted)
        // The cycle that failed is the warm-up one, which is excluded anyway, so every comparable sample survived.
        assertEquals(profile.expectedLaunchSamples, result.validLaunchSamples)
    }

    @Test
    fun `three consecutive failures abort the run`() {
        val r = runner()
        r.start()
        repeat(3) {
            scheduler.advanceMs(BenchmarkRunner.Config().openTimeoutMs)
            completeClose(r)
        }
        val result = listener.result!!
        assertEquals("repeated_failure", result.aborted)
        assertEquals(3, result.cycles.size)
        assertEquals(BenchmarkRunner.Step.ABORTED, r.step)
    }

    @Test
    fun `a failing observation session ends the run instead of retrying`() {
        val r = runner()
        r.start()
        repeat(profile.launchIterations) { completeOpen(r); completeClose(r) }
        // The observation open never reports a first frame.
        completeOpenWithoutFirstFrame(r)
        scheduler.advanceMs(BenchmarkRunner.Config().firstFrameTimeoutMs)
        completeClose(r)

        val result = listener.result!!
        assertEquals("observation_failed", result.aborted)
        assertFalse(result.observed)
        assertEquals(profile.launchIterations, result.cycles.size)
        assertEquals(0, result.stills.size)
    }

    private fun completeOpenWithoutFirstFrame(r: BenchmarkRunner) {
        val s = r.currentSession
        clock.advanceMs(100)
        r.signal(s, BenchmarkRunner.Signal.OPENED, clock.ns)
        r.mark(s, "configure_call", clock.ns)
        clock.advanceMs(40)
        r.signal(s, BenchmarkRunner.Signal.CONFIGURED, clock.ns)
    }

    @Test
    fun `a still that never arrives is recorded as missing and the next one is submitted`() {
        val r = runner()
        r.start()
        repeat(profile.launchIterations) { completeOpen(r); completeClose(r) }
        completeOpen(r)
        scheduler.advanceMs(profile.warmupMs + profile.observeMs)
        // First still times out, the rest arrive.
        scheduler.advanceMs(BenchmarkRunner.Config().stillTimeoutMs)
        repeat(profile.stillCount - 1) {
            clock.advanceMs(150)
            r.signal(r.currentSession, BenchmarkRunner.Signal.STILL_RECEIVED, clock.ns)
        }
        completeClose(r)

        val result = listener.result!!
        assertEquals(profile.stillCount, result.stills.size)
        assertNull(result.stills.first().imageNs)
        assertEquals("STILL_timeout", result.hardFailure)
        // The lost still is the warm-up one, so every comparable sample survived.
        assertEquals(profile.expectedStillSamples, result.validStillSamples)
    }

    @Test
    fun `abort during the observation window closes the camera and finishes once`() {
        val r = runner()
        r.start()
        repeat(profile.launchIterations) { completeOpen(r); completeClose(r) }
        completeOpen(r)
        scheduler.advanceMs(profile.warmupMs)
        r.abort("background")
        assertEquals(1, driver.closes.count { it == r.currentSession })
        completeClose(r)

        val result = listener.result!!
        assertEquals("background", result.aborted)
        assertEquals(BenchmarkRunner.Step.ABORTED, r.step)
        assertEquals(0, scheduler.pending)
    }

    @Test
    fun `a close that never reports back does not hang the run`() {
        val r = runner()
        r.start()
        repeat(profile.launchIterations) { completeOpen(r); completeClose(r) }
        completeOpen(r)
        scheduler.advanceMs(profile.warmupMs + profile.observeMs)
        repeat(profile.stillCount) {
            clock.advanceMs(150)
            r.signal(r.currentSession, BenchmarkRunner.Signal.STILL_RECEIVED, clock.ns)
        }
        scheduler.advanceMs(BenchmarkRunner.Config().closeTimeoutMs)

        val result = listener.result!!
        assertEquals("CLOSE_timeout", result.hardFailure)
        assertEquals(BenchmarkRunner.Step.DONE, r.step)
    }

    @Test
    fun `the screen sees the six phases in order`() {
        val r = runner()
        runToEnd(r)
        assertEquals(
            listOf(
                BenchmarkRunner.Phase.CAMERA_OPEN, BenchmarkRunner.Phase.FIRST_PREVIEW,
                BenchmarkRunner.Phase.PREVIEW_STABILITY, BenchmarkRunner.Phase.THREE_A,
                BenchmarkRunner.Phase.STILL_CAPTURE, BenchmarkRunner.Phase.CAMERA_CLOSE
            ),
            listener.phases
        )
    }

    @Test
    fun `signals for an earlier session are ignored`() {
        val r = runner()
        r.start()
        val first = r.currentSession
        completeOpen(r)
        completeClose(r)
        val second = r.currentSession
        // A late CLOSED from the previous cycle must not close the cycle that just started.
        r.signal(first, BenchmarkRunner.Signal.CLOSED, clock.ns)
        assertEquals(second, r.currentSession)
        assertEquals(BenchmarkRunner.Step.OPEN, r.step)
    }
}
