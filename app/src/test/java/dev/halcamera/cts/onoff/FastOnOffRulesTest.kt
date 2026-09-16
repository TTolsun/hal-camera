package dev.halcamera.cts.onoff

import dev.halcamera.cts.OpenCycle
import dev.halcamera.cts.Verdict
import dev.halcamera.cts.onoff.FastOnOffRules.Cycle
import dev.halcamera.cts.onoff.FastOnOffRules.FrameMeta
import dev.halcamera.cts.onoff.FastOnOffRules.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FastOnOffRulesTest {
    private val opened = 1_000_000_000L
    private val completed = 1_400_000_000L
    private fun frame(ts: Long? = 1_200_000_000L, frameNumber: Long = 0, realtime: Boolean = true) =
        FrameMeta(ts, frameNumber, realtime, opened, completed)

    private fun pass(firstFrame: Double? = 120.0, error: String? = null, closedInTime: Boolean = true) =
        OpenCycle(openMs = 40.0, configureMs = 30.0, firstFrameMs = firstFrame, closeMs = 20.0, closedInTime = closedInTime, error = error)

    private fun standard(firstFrame: Double = 120.0, frame: FrameMeta? = frame(), error: String? = null, closedInTime: Boolean = true) =
        Cycle(Kind.STANDARD, pass(firstFrame, error, closedInTime), frame)

    private fun fast(firstFrame: Double = 130.0, frame: FrameMeta? = frame(), fastError: String? = null, fastClosedInTime: Boolean = true) =
        Cycle(Kind.FAST, pass(firstFrame), frame, fastOpenMs = 42.0, fastCloseMs = 9.0, fastClosedInTime = fastClosedInTime, fastError = fastError)

    @Test
    fun `a sound first frame passes and the row shows every timing`() {
        val (verdict, details) = FastOnOffRules.judge(standard())
        assertEquals(Verdict.PASS, verdict)
        assertEquals(listOf("open 40.0 ms · configure 30.0 ms · first frame 120.0 ms · close 20.0 ms"), details)
        val fast = FastOnOffRules.judge(fast())
        assertEquals(Verdict.PASS, fast.first)
        assertEquals("fast open 42.0 ms · fast close 9.0 ms · reopen 40.0 ms · configure 30.0 ms · first frame 130.0 ms · close 20.0 ms", fast.second.single())
    }

    @Test
    fun `metadata failures name the missing timestamp, the negative frame number and the clock window`() {
        assertEquals(listOf("SENSOR_TIMESTAMP must be present and positive in the first result (got null)"), FastOnOffRules.metadataFailures(frame(ts = null)))
        assertEquals(listOf("SENSOR_TIMESTAMP must be present and positive in the first result (got 0)"), FastOnOffRules.metadataFailures(frame(ts = 0)))
        assertEquals(listOf("Frame number must not be negative (got -1)"), FastOnOffRules.metadataFailures(frame(frameNumber = -1)))
        val early = FastOnOffRules.metadataFailures(frame(ts = opened - 1))
        assertEquals(1, early.size)
        assertTrue(early[0], early[0].startsWith("SENSOR_TIMESTAMP ${opened - 1} ns is outside the open..completed window"))
        // A sensor on its own clock is not held to the app clock.
        assertTrue(FastOnOffRules.metadataFailures(frame(ts = opened - 1, realtime = false)).isEmpty())
    }

    @Test
    fun `a bad timestamp fails the cycle that carried it`() {
        val (verdict, details) = FastOnOffRules.judge(standard(frame = frame(ts = null)))
        assertEquals(Verdict.FAIL, verdict)
        assertEquals("SENSOR_TIMESTAMP must be present and positive in the first result (got null)", details[1])
    }

    @Test
    fun `an error fails the cycle and keeps whatever was measured ahead of it`() {
        val (verdict, details) = FastOnOffRules.judge(Cycle(Kind.STANDARD, OpenCycle(openMs = 40.0, error = "IllegalStateException: Timeout waiting for the session to configure")))
        assertEquals(Verdict.FAIL, verdict)
        assertEquals(listOf("open 40.0 ms", "IllegalStateException: Timeout waiting for the session to configure"), details)
        // A fault in the immediate open/close stops the cycle before the reopen; nothing else is reported.
        val immediate = FastOnOffRules.judge(Cycle(Kind.FAST, fastOpenMs = 42.0, fastError = "CameraAccessException: x"))
        assertEquals(listOf("fast open 42.0 ms", "CameraAccessException: x"), immediate.second)
    }

    @Test
    fun `a slow close or a missing frame fails without an exception`() {
        assertEquals(Verdict.FAIL, FastOnOffRules.judge(standard(closedInTime = false)).first)
        assertTrue(FastOnOffRules.judge(standard(closedInTime = false)).second.contains("Timeout waiting for the camera to close"))
        assertTrue(FastOnOffRules.judge(fast(fastClosedInTime = false)).second.contains("Timeout waiting for the camera to close after the immediate close"))
        val noFrame = FastOnOffRules.judge(Cycle(Kind.STANDARD, OpenCycle(openMs = 40.0, configureMs = 30.0, closeMs = 5.0)))
        assertEquals(Verdict.FAIL, noFrame.first)
        assertTrue(noFrame.second.contains("No capture result was completed"))
    }

    @Test
    fun `compare reports the medians and their delta`() {
        val cycles = listOf(standard(100.0), fast(150.0), standard(120.0), fast(130.0), standard(110.0), fast(170.0))
        val (verdict, details) = FastOnOffRules.compare(cycles)
        assertEquals(Verdict.PASS, verdict)
        assertEquals("first frame median · standard 110.0 ms (3) · fast reopen 150.0 ms (3) · delta +40.0 ms", details.single())
    }

    @Test
    fun `compare fails when one side never reached a frame`() {
        val (verdict, details) = FastOnOffRules.compare(listOf(standard(), fast(fastError = "boom"), fast(fastError = "boom")))
        assertEquals(Verdict.FAIL, verdict)
        assertEquals("Nothing to compare: standard 1 of 1, fast 0 of 2 cycles reached a first frame", details.single())
    }

    @Test
    fun `step ids number the cycles by kind`() {
        assertEquals("standard_1", FastOnOffRules.stepId(Kind.STANDARD, 1))
        assertEquals("fast_5", FastOnOffRules.stepId(Kind.FAST, 5))
    }
}
