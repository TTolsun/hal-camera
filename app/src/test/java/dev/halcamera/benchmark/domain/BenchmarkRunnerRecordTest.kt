package dev.halcamera.benchmark.domain

import dev.halcamera.camera.CameraEndpoint
import dev.halcamera.camera.LensRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RECORD stage of the runner (docs/PLAN-Recording-v0.1.md 5, 6 and 9). The fakes come from
 * BenchmarkRunnerTest; what is new here is that the profile records, so the stage runs between the stills and
 * the close.
 *
 * The central rule these tests pin down: a recording failure is the stage's own failure. It never sets
 * hardFailure or aborted, because by then the launch, preview and capture samples are all collected and
 * throwing the run away over a recorder that would not start would lose measurements that are perfectly good.
 */
class BenchmarkRunnerRecordTest {

    private val endpoint = CameraEndpoint(
        logicalCameraId = "0", physicalCameraId = null, role = LensRole.MAIN, facing = 1,
        independentlyOpenable = true, selectableByZoom = false, exposedToCameraX = null,
        equivalentFocalMm = 24.0, timestampSource = 1, hardwareLevel = 3, zoomRatioMin = 0.5f, zoomRatioMax = 10f
    )
    private val profile = BenchmarkProfile.CAMERA2_STANDARD_V2
    private val recordIterations = profile.recordIterations!!
    private val recordDurationMs = profile.recordDurationMs!!

    private val clock = FakeClock()
    private val scheduler = FakeScheduler(clock)
    private val driver = FakeDriver()
    private val listener = object : BenchmarkRunner.Listener {
        val phases = ArrayList<BenchmarkRunner.Phase>()
        var result: BenchmarkRunner.Result? = null
        override fun onProgress(phase: BenchmarkRunner.Phase, step: BenchmarkRunner.Step, iteration: Int, total: Int) {
            if (phases.lastOrNull() != phase) phases += phase
        }
        override fun onFinished(result: BenchmarkRunner.Result) { this.result = result }
    }

    private fun runner(p: BenchmarkProfile = profile) =
        BenchmarkRunner(driver, scheduler, { clock.ns }, p, endpoint, "20260924-120000-000", BenchmarkRunner.Config(), listener)

    // ---- driving the camera side ----

    private fun completeOpen(r: BenchmarkRunner) {
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

    private fun completeClose(r: BenchmarkRunner) {
        clock.advanceMs(30)
        r.signal(r.currentSession, BenchmarkRunner.Signal.CLOSED, clock.ns)
    }

    private fun completeStill(r: BenchmarkRunner, index: Int) {
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

    /** Everything up to the first RECORD_PREPARE: ten launch cycles, the observation session and the stills. */
    private fun runToRecordStage(r: BenchmarkRunner) {
        r.start()
        repeat(profile.launchIterations) { completeOpen(r); completeClose(r) }
        completeOpen(r)
        scheduler.advanceMs(profile.warmupMs)
        scheduler.advanceMs(profile.observeMs)
        repeat(profile.stillCount) { completeStill(r, it) }
    }

    /** The engine side of one recording cycle: prepare, start, the first recording capture, then stop. */
    private fun completeRecord(
        r: BenchmarkRunner, index: Int,
        prepareMs: Long = 200, startCallMs: Long = 5, startedMs: Long = 20,
        firstStartedMs: Long = 35, stopMs: Long = 300
    ) {
        val s = r.currentSession
        clock.advanceMs(prepareMs)
        r.signal(s, BenchmarkRunner.Signal.RECORD_READY, clock.ns)
        clock.advanceMs(startCallMs)
        r.recordMark(s, RecordCycle.START_CALL_MARK, clock.ns)
        clock.advanceMs(startedMs)
        r.signal(s, BenchmarkRunner.Signal.RECORD_STARTED, clock.ns)
        clock.advanceMs(firstStartedMs)
        r.recordFrameStarted(s, RecordCycle.tag(index), clock.ns)
        scheduler.advanceMs(recordDurationMs)     // the run timer moves the cycle to RECORD_STOP
        r.recordMark(s, RecordCycle.STOP_CALL_MARK, clock.ns)
        clock.advanceMs(stopMs)
        r.signal(s, BenchmarkRunner.Signal.RECORD_STOPPED, clock.ns)
    }

    // ---- tests ----

    @Test
    fun `the record stage runs after the stills and before the close`() {
        val r = runner()
        runToRecordStage(r)
        repeat(recordIterations) { completeRecord(r, it) }
        completeClose(r)

        val result = listener.result!!
        assertEquals(BenchmarkRunner.Step.DONE, r.step)
        assertEquals((0 until recordIterations).toList(), driver.recordPrepares)
        assertEquals(recordIterations, driver.recordStarts)
        assertEquals(recordIterations, driver.recordStops)
        assertEquals("no cycle failed, so nothing had to be released", 0, driver.recordAborts)
        assertEquals(recordIterations, result.records.size)
        assertFalse(result.recordUnsupported)
        assertNull(result.hardFailure)
        assertNull(result.aborted)
        // The warm-up cycle is excluded, leaving exactly what the profile promises for 3.1 and 3.6.
        assertEquals(1, result.records.count { it.warmup })
        assertEquals(profile.expectedRecordSamples, result.validRecordSamples)
        assertEquals(0, scheduler.pending)
        // Recording is its own phase, and it comes between the stills and the close.
        assertEquals(
            listOf(BenchmarkRunner.Phase.STILL_CAPTURE, BenchmarkRunner.Phase.RECORDING, BenchmarkRunner.Phase.CAMERA_CLOSE),
            listener.phases.takeLast(3)
        )
    }

    @Test
    fun `recording latencies come from the marks the engine reports`() {
        val r = runner()
        runToRecordStage(r)
        repeat(recordIterations) { completeRecord(r, it) }
        completeClose(r)

        val cycle = listener.result!!.records.first()
        // 3.1 runs from just before MediaRecorder.start() to the first capture of a recording request: the 20 ms
        // the call itself took plus the 35 ms until the camera reported the capture.
        assertEquals(55.0, cycle.startToCallbackMs!!, 0.001)
        // 3.6 is the stop() call alone.
        assertEquals(300.0, cycle.stopLatencyMs!!, 0.001)
        assertEquals("record-0", cycle.requestTag)
        assertNotNull(cycle.startedNs)
        assertNotNull(cycle.stoppedNs)
        assertTrue(cycle.stoppedNs!! > cycle.startedNs!!)
    }

    @Test
    fun `a capture from another cycle or from before the start never answers 3 point 1`() {
        val r = runner()
        runToRecordStage(r)
        val s = r.currentSession
        clock.advanceMs(200)
        r.signal(s, BenchmarkRunner.Signal.RECORD_READY, clock.ns)
        // A preview capture submitted while the recorder was being prepared, and one tagged for a later cycle.
        r.recordFrameStarted(s, RecordCycle.tag(0), clock.ns)
        clock.advanceMs(5)
        r.recordMark(s, RecordCycle.START_CALL_MARK, clock.ns)
        clock.advanceMs(20)
        r.signal(s, BenchmarkRunner.Signal.RECORD_STARTED, clock.ns)
        clock.advanceMs(35)
        r.recordFrameStarted(s, RecordCycle.tag(3), clock.ns)
        clock.advanceMs(10)
        r.recordFrameStarted(s, RecordCycle.tag(0), clock.ns)
        scheduler.advanceMs(recordDurationMs)
        r.recordMark(s, RecordCycle.STOP_CALL_MARK, clock.ns)
        clock.advanceMs(300)
        r.signal(s, BenchmarkRunner.Signal.RECORD_STOPPED, clock.ns)
        repeat(recordIterations - 1) { completeRecord(r, it + 1) }
        completeClose(r)

        // Only the capture that carried this cycle's tag and arrived after the start call counts: 20 + 35 + 10.
        assertEquals(65.0, listener.result!!.records.first().startToCallbackMs!!, 0.001)
    }

    @Test
    fun `a profile without a record stage closes straight after the stills`() {
        val v1 = BenchmarkProfile.CAMERA2_STANDARD_V1
        val r = runner(v1)
        r.start()
        repeat(v1.launchIterations) { completeOpen(r); completeClose(r) }
        completeOpen(r)
        scheduler.advanceMs(v1.warmupMs + v1.observeMs)
        repeat(v1.stillCount) { completeStill(r, it) }
        completeClose(r)

        val result = listener.result!!
        assertEquals(BenchmarkRunner.Step.DONE, r.step)
        assertEquals(emptyList<Int>(), driver.recordPrepares)
        assertEquals(emptyList<RecordCycle>(), result.records)
        assertEquals(0, result.validRecordSamples)
        assertFalse(listener.phases.contains(BenchmarkRunner.Phase.RECORDING))
    }

    @Test
    fun `a refused recording combination skips the remaining cycles instead of retrying them`() {
        val r = runner()
        runToRecordStage(r)
        clock.advanceMs(120)
        r.signal(r.currentSession, BenchmarkRunner.Signal.RECORD_UNSUPPORTED, clock.ns, "record_configure_failed")
        completeClose(r)

        val result = listener.result!!
        // The same combination would be refused four more times, so it is asked for exactly once.
        assertEquals(listOf(0), driver.recordPrepares)
        assertEquals(1, result.records.size)
        assertTrue(result.recordUnsupported)
        assertEquals("record_configure_failed", result.records.first().failureReason)
        assertEquals(0, result.validRecordSamples)
        // Everything measured before the recording is still a complete, valid run.
        assertNull("a refused recorder must not mark the run hard-failed", result.hardFailure)
        assertNull(result.aborted)
        assertEquals(profile.expectedLaunchSamples, result.validLaunchSamples)
        assertEquals(profile.expectedStillSamples, result.validStillSamples)
        assertEquals(BenchmarkRunner.Step.DONE, r.step)
    }

    @Test
    fun `a cycle whose recording never starts is failed and the recorder is stopped anyway`() {
        val r = runner()
        runToRecordStage(r)
        val s = r.currentSession
        clock.advanceMs(200)
        r.signal(s, BenchmarkRunner.Signal.RECORD_READY, clock.ns)
        clock.advanceMs(5)
        r.recordMark(s, RecordCycle.START_CALL_MARK, clock.ns)
        clock.advanceMs(20)
        r.signal(s, BenchmarkRunner.Signal.RECORD_STARTED, clock.ns)
        // No recording capture ever starts.
        scheduler.advanceMs(recordDurationMs)
        r.recordMark(s, RecordCycle.STOP_CALL_MARK, clock.ns)
        clock.advanceMs(300)
        r.signal(s, BenchmarkRunner.Signal.RECORD_STOPPED, clock.ns)
        repeat(recordIterations - 1) { completeRecord(r, it + 1) }
        completeClose(r)

        val result = listener.result!!
        val first = result.records.first()
        assertTrue(first.failed)
        assertEquals("record_first_frame_missing", first.failureReason)
        assertNull(first.startToCallbackMs)
        assertNull("a failed cycle reports no stop latency either", first.stopLatencyMs)
        assertEquals("the recorder is stopped even when the cycle is already failed", recordIterations, driver.recordStops)
        assertEquals(1, driver.recordAborts)
        // The four cycles that worked are still there, and the run itself is not hard-failed.
        assertEquals(recordIterations, result.records.size)
        assertEquals(4, result.records.count { !it.failed })
        assertNull(result.hardFailure)
    }

    @Test
    fun `a stop that never returns fails its cycle and the next one starts`() {
        val r = runner()
        runToRecordStage(r)
        val s = r.currentSession
        clock.advanceMs(200)
        r.signal(s, BenchmarkRunner.Signal.RECORD_READY, clock.ns)
        clock.advanceMs(5)
        r.recordMark(s, RecordCycle.START_CALL_MARK, clock.ns)
        clock.advanceMs(20)
        r.signal(s, BenchmarkRunner.Signal.RECORD_STARTED, clock.ns)
        clock.advanceMs(35)
        r.recordFrameStarted(s, RecordCycle.tag(0), clock.ns)
        scheduler.advanceMs(recordDurationMs)
        r.recordMark(s, RecordCycle.STOP_CALL_MARK, clock.ns)
        scheduler.advanceMs(BenchmarkRunner.Config().recordStopTimeoutMs)
        repeat(recordIterations - 1) { completeRecord(r, it + 1) }
        completeClose(r)

        val result = listener.result!!
        assertEquals("RECORD_STOP_timeout", result.records.first().failureReason)
        assertNull(result.records.first().stopLatencyMs)
        assertEquals(1, driver.recordAborts)
        assertEquals(recordIterations, result.records.size)
        assertNull(result.hardFailure)
        assertEquals(BenchmarkRunner.Step.DONE, r.step)
    }

    @Test
    fun `three failing cycles in a row end the stage without ending the run`() {
        val r = runner()
        runToRecordStage(r)
        repeat(3) {
            val s = r.currentSession
            clock.advanceMs(120)
            r.signal(s, BenchmarkRunner.Signal.ERROR, clock.ns, "record_prepare_failed")
        }
        completeClose(r)

        val result = listener.result!!
        assertEquals(listOf(0, 1, 2), driver.recordPrepares)
        assertEquals(3, result.records.size)
        assertTrue(result.records.all { it.failed })
        assertEquals(3, driver.recordAborts)
        assertNull(result.hardFailure)
        assertNull(result.aborted)
        assertEquals(BenchmarkRunner.Step.DONE, r.step)
    }

    @Test
    fun `a recorder error that arrives after the stage is over does not touch the run`() {
        val r = runner()
        runToRecordStage(r)
        repeat(recordIterations) { completeRecord(r, it) }
        // The MediaRecorder of the last cycle reports its error only now, while the camera is closing.
        r.recordFailed(r.currentSession, recordIterations - 1, "record_failed:recorder_error", clock.ns)
        completeClose(r)

        val result = listener.result!!
        assertNull("a late recorder error must not mark the run hard-failed", result.hardFailure)
        assertNull(result.aborted)
        assertEquals(BenchmarkRunner.Step.DONE, r.step)
        // The run stays complete: every cycle kept its values and nothing was cut short.
        assertEquals(profile.expectedRecordSamples, result.validRecordSamples)
        assertEquals(recordIterations, driver.recordStops)
    }

    @Test
    fun `a recorder error naming an earlier cycle does not fail the one in flight`() {
        val r = runner()
        runToRecordStage(r)
        completeRecord(r, 0)
        // Cycle 1 is preparing when cycle 0's recorder finally reports its error.
        r.recordFailed(r.currentSession, 0, "record_failed:recorder_error", clock.ns)
        repeat(recordIterations - 1) { completeRecord(r, it + 1) }
        completeClose(r)

        val result = listener.result!!
        assertTrue("no cycle was failed by another cycle's error", result.records.none { it.failed })
        assertEquals(0, driver.recordAborts)
        assertEquals(profile.expectedRecordSamples, result.validRecordSamples)
    }

    @Test
    fun `an abort during recording keeps the cycles that finished`() {
        val r = runner()
        runToRecordStage(r)
        repeat(2) { completeRecord(r, it) }
        // Third cycle is in flight when the user aborts.
        clock.advanceMs(200)
        r.signal(r.currentSession, BenchmarkRunner.Signal.RECORD_READY, clock.ns)
        r.abort("user")
        completeClose(r)

        val result = listener.result!!
        assertEquals("user", result.aborted)
        assertEquals(BenchmarkRunner.Step.ABORTED, r.step)
        assertEquals(3, result.records.size)
        assertEquals("aborted", result.records.last().failureReason)
        assertEquals(1, driver.recordAborts)
        // The two finished cycles keep their numbers; the warm-up one is still excluded.
        assertEquals(2, result.records.count { !it.failed })
        assertEquals(1, result.validRecordSamples)
        assertEquals(300.0, result.records.first().stopLatencyMs!!, 0.001)
    }
}
