package dev.cameradoctor.check

import dev.cameradoctor.check.AutoCheckRunner.Signal
import dev.cameradoctor.check.AutoCheckRunner.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCheckRunnerTest {
    /** Deterministic virtual time: scheduled actions fire when the clock is advanced past them. */
    private class FakeScheduler(private val clock: () -> Long) : AutoCheckRunner.Scheduler {
        private var seq = 0
        val pending = sortedMapOf<Long, MutableList<Pair<Int, () -> Unit>>>()
        override fun after(delayMs: Long, action: () -> Unit): Any {
            val id = seq++
            pending.getOrPut(clock() + delayMs * 1_000_000) { mutableListOf() } += id to action
            return id
        }
        override fun cancel(token: Any) { pending.values.forEach { l -> l.removeAll { it.first == token } } }
        fun fireDue(now: Long) {
            while (pending.isNotEmpty() && pending.firstKey() <= now) {
                val k = pending.firstKey(); val l = pending.remove(k)!!
                l.forEach { it.second() }
            }
        }
    }

    private class Fake : AutoCheckRunner.Driver {
        val calls = mutableListOf<String>()
        override fun open(endpoint: CameraEndpoint, session: String) { calls += "open:${endpoint.key}" }
        override fun still(session: String) { calls += "still" }
        override fun close(session: String) { calls += "close" }
    }

    private class Log : AutoCheckRunner.Listener {
        val steps = mutableListOf<String>()
        val done = mutableListOf<AutoCheckRunner.EndpointResult>()
        var finished: List<AutoCheckRunner.EndpointResult>? = null
        var aborted: String? = "unset"
        override fun onStep(endpoint: CameraEndpoint, index: Int, total: Int, step: Step) { steps += "${endpoint.key}:$step" }
        override fun onEndpointDone(result: AutoCheckRunner.EndpointResult) { done += result }
        override fun onFinished(results: List<AutoCheckRunner.EndpointResult>, aborted: String?) { finished = results; this.aborted = aborted }
    }

    private fun ep(id: String, role: LensRole = LensRole.MAIN, openable: Boolean = true) =
        CameraEndpoint(id, null, role, 1, openable, false, null, 24.0, 1, 3, 1f, 10f)

    private class Rig {
        var now = 1_000_000_000L
        val sched = FakeScheduler { now }
        val driver = Fake()
        val log = Log()
        val runner = AutoCheckRunner(driver, sched, { now }, AutoCheckRunner.Config(observeMs = 10_000, stillCount = 3), log)
        fun advance(ms: Long) { now += ms * 1_000_000; sched.fireDue(now) }
        fun sig(s: Signal, afterMs: Long = 10) { advance(afterMs); runner.signal(runner.currentSession, s) }
    }

    private fun Rig.happyEndpoint() {
        sig(Signal.OPENED, 120)
        runner.mark(runner.currentSession, "configure_call"); sig(Signal.CONFIGURED, 80)
        runner.mark(runner.currentSession, "repeating_call"); advance(10); runner.firstStarted(runner.currentSession)
        sig(Signal.FIRST_FRAME, 150)
        advance(10_000)                      // observe window ends, first still submitted
        repeat(3) { advance(300); runner.stillResult(runner.currentSession); sig(Signal.STILL_RECEIVED, 100) }
        sig(Signal.CLOSED, 50)
    }

    @Test fun happyPathTwoEndpointsProducesLatencies() {
        val r = Rig()
        r.runner.start(listOf(ep("0"), ep("1", LensRole.FRONT)))
        r.happyEndpoint(); r.happyEndpoint()
        assertEquals(Step.DONE, r.runner.step)
        assertNull(r.log.aborted)
        assertEquals(2, r.log.finished!!.size)
        val first = r.log.finished!![0]
        assertNull(first.hardFailure)
        assertEquals(120.0, first.openMs!!, 0.01)
        assertEquals(80.0, first.configureMs!!, 0.01)
        assertEquals(10.0, first.firstStartedMs!!, 0.01)
        assertEquals(160.0, first.yuvProxyMs!!, 0.01)      // repeating_call -> first yuv
        assertEquals(360.0, first.previewTotalMs!!, 0.01)  // 120 + 80 + 160
        assertEquals(3, first.stillLatenciesMs.size)
        assertEquals(400.0, first.stillLatenciesMs[0], 0.01)
        assertEquals(300.0, first.stillResultLatenciesMs[0], 0.01)
        assertEquals(2, first.shotToShotMs.size)
        assertEquals(400.0, first.shotToShotMs[0], 0.01)
        assertEquals(50.0, first.closeMs!!, 0.01)
        assertEquals(listOf("open:0", "still", "still", "still", "close", "open:1", "still", "still", "still", "close"), r.driver.calls)
        val samples = first.launchSamples().associateBy { it.id }
        assertEquals(360.0, samples["1.6"]!!.value!!, 0.01)
        assertEquals(400.0, samples["2.2"]!!.value!!, 0.01)
        assertEquals(dev.cameradoctor.diagnosis.UnknownReason.INSUFFICIENT_SAMPLES, samples["2.5"]!!.unknownReason)
    }

    @Test fun openTimeoutIsHardFailureAndNextEndpointStillRuns() {
        val r = Rig()
        r.runner.start(listOf(ep("0"), ep("1", LensRole.FRONT)))
        r.advance(3001)                                   // open timeout -> CLOSE
        assertEquals(Step.CLOSE, r.runner.step)
        r.sig(Signal.CLOSED)
        assertEquals("OPEN", r.log.done[0].hardFailure)
        assertTrue(r.log.done[0].launchSamples().first { it.id == "1.1" }.hardFailure)
        assertEquals(dev.cameradoctor.diagnosis.UnknownReason.NOT_RUN, r.log.done[0].launchSamples().first { it.id == "1.2" }.unknownReason)
        assertEquals(Step.OPEN, r.runner.step)            // second endpoint started
        r.happyEndpoint()
        assertEquals(Step.DONE, r.runner.step)
        assertNull(r.log.finished!![1].hardFailure)
    }

    @Test fun closeTimeoutStillFinishesEndpoint() {
        val r = Rig()
        r.runner.start(listOf(ep("0")))
        r.sig(Signal.OPENED); r.sig(Signal.CONFIGURED); r.sig(Signal.FIRST_FRAME)
        r.advance(10_000)
        repeat(3) { r.sig(Signal.STILL_RECEIVED) }
        assertEquals(Step.CLOSE, r.runner.step)
        r.advance(3001)
        assertEquals(Step.DONE, r.runner.step)
        assertEquals("CLOSE", r.log.finished!![0].hardFailure)
    }

    @Test fun errorDuringObserveGoesToClose() {
        val r = Rig()
        r.runner.start(listOf(ep("0")))
        r.sig(Signal.OPENED); r.sig(Signal.CONFIGURED); r.sig(Signal.FIRST_FRAME)
        r.advance(2000)
        r.runner.signal(r.runner.currentSession, Signal.ERROR)
        assertEquals(Step.CLOSE, r.runner.step)
        r.sig(Signal.CLOSED)
        assertEquals("OBSERVE", r.log.finished!![0].hardFailure)
        // The observe timer must have been cancelled: advancing past it must not re-enter STILL.
        r.advance(20_000)
        assertEquals(Step.DONE, r.runner.step)
        assertEquals(0, r.driver.calls.count { it == "still" })
    }

    @Test fun signalsFromOtherSessionsAreIgnored() {
        val r = Rig()
        r.runner.start(listOf(ep("0")))
        r.runner.signal("someone-else", Signal.OPENED)
        assertEquals(Step.OPEN, r.runner.step)
    }

    @Test fun abortClosesCurrentAndSkipsRest() {
        val r = Rig()
        r.runner.start(listOf(ep("0"), ep("1", LensRole.FRONT)))
        r.sig(Signal.OPENED)
        r.runner.abort("background")
        assertEquals(Step.CLOSE, r.runner.step)
        r.sig(Signal.CLOSED)
        assertEquals(Step.ABORTED, r.runner.step)
        assertEquals("background", r.log.aborted)
        assertEquals(1, r.log.finished!!.size)
        assertEquals("CONFIGURE", r.log.finished!![0].hardFailure)
    }

    @Test fun onlyOpenableEndpointsUpToMaxAreChecked() {
        val r = Rig()
        val eps = listOf(ep("0"), ep("0.2", openable = false), ep("1", LensRole.FRONT), ep("2", LensRole.ULTRA_WIDE), ep("3", LensRole.TELE), ep("4", LensRole.UNKNOWN))
        r.runner.start(LensRoles.checkOrder(eps))
        assertEquals("open:0", r.driver.calls.first())
        repeat(4) { r.happyEndpoint() }
        assertEquals(Step.DONE, r.runner.step)
        assertEquals(listOf("0", "1", "2", "3"), r.log.finished!!.map { it.endpoint.key })
    }

    @Test fun noCameraAbortsImmediately() {
        val r = Rig()
        r.runner.start(listOf(ep("0.1", openable = false)))
        assertEquals(Step.ABORTED, r.runner.step); assertEquals("no_camera", r.log.aborted)
    }

    @Test fun lensRoleInference() {
        // Galaxy-like: 24 mm main, 13 mm ultra wide, 67 mm tele; a second 26 mm rear becomes UNKNOWN.
        val roles = LensRoles.dedupeMain(listOf(24.0, 13.0, 67.0, 26.0).map { LensRoles.roleFor(it) })
        assertEquals(listOf(LensRole.MAIN, LensRole.ULTRA_WIDE, LensRole.TELE, LensRole.UNKNOWN), roles)
        assertEquals(LensRole.UNKNOWN, LensRoles.roleFor(null))
        // 5.4 mm focal on a 9.8 x 7.3 mm sensor is about 19 mm equivalent.
        val eq = LensRoles.equivalentFocalMm(5.4f, 9.8f, 7.3f)!!
        assertEquals(19.1, eq, 0.2)
    }
}
