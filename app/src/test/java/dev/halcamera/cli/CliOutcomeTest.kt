package dev.halcamera.cli

import dev.halcamera.cli.CliOutcome.CancelStep
import org.junit.Assert.*
import org.junit.Test

class CliOutcomeTest {
    private val timeout = CliOutcome.TIMEOUT
    private val cancelled = CliOutcome.CANCELLED

    @Test fun `a finished request succeeds unless a cancel or the deadline took effect`() {
        assertEquals(CliOutcome("succeeded", null, null), CliOutcome.completed(null, null, resultCancelled = false))
        val late = CliOutcome.completed(cancelled, null, resultCancelled = false)
        assertEquals("succeeded", late.state)
        assertNull(late.error)
        assertEquals(false, late.cancelEffective)
    }

    @Test fun `the deadline outranks a successful result and a reported failure`() {
        listOf(null, CliFailure("CAPTURE_FAILED", "x"), CliFailure(cancelled, "x")).forEach { failure ->
            val outcome = CliOutcome.completed(timeout, failure, resultCancelled = true)
            assertEquals("failed", outcome.state)
            assertEquals(timeout, outcome.error!!.code)
            assertEquals(true, outcome.cancelEffective)
        }
    }

    @Test fun `a cancel takes effect when the screen stopped or reported the cancellation`() {
        val stopped = CliOutcome.completed(cancelled, null, resultCancelled = true)
        assertEquals("cancelled", stopped.state)
        assertEquals(cancelled, stopped.error!!.code)
        assertEquals(true, stopped.cancelEffective)
        assertEquals("cancelled", CliOutcome.completed(null, CliFailure(cancelled, "Stopped"), resultCancelled = false).state)
        val failed = CliOutcome.completed(cancelled, CliFailure("CAPTURE_FAILED", "Lost"), resultCancelled = false)
        assertEquals("failed", failed.state)
        assertEquals("CAPTURE_FAILED", failed.error!!.code)
    }

    @Test fun `a timeout is never replaced by a later cancel`() {
        assertEquals(timeout, CliOutcome.cancellation(timeout, cancelled))
        assertEquals(cancelled, CliOutcome.cancellation(null, cancelled))
        assertEquals(timeout, CliOutcome.cancellation(cancelled, timeout))
        assertEquals(timeout, CliOutcome.failureCode("CAPTURE_FAILED", timedOut = true))
        assertEquals("CAPTURE_FAILED", CliOutcome.failureCode("CAPTURE_FAILED", timedOut = false))
        assertEquals("cancelled", CliOutcome.failureState(cancelled))
        assertEquals("failed", CliOutcome.failureState(timeout))
    }

    @Test fun `cancel steps follow the stored state and where the command runs`() {
        val capture = CliCommands.get("capture")
        listOf(null, "saving", "succeeded", "failed", "cancelled", "interrupted").forEach {
            assertEquals(it.toString(), CancelStep.IGNORE, CliOutcome.cancelStep(it, capture))
        }
        assertEquals(CancelStep.STOP_SCREEN_AND_FAIL, CliOutcome.cancelStep("accepted", capture))
        assertEquals(CancelStep.STOP_SCREEN_AND_FAIL, CliOutcome.cancelStep("preparing", CliCommands.get("cts.run")))
        assertEquals(CancelStep.FAIL, CliOutcome.cancelStep("preparing", CliCommands.get("streams")))
        assertEquals(CancelStep.FAIL, CliOutcome.cancelStep("accepted", CliCommands.get("pip.sources")))
        assertEquals(CancelStep.MARK_CANCELLING, CliOutcome.cancelStep("running", CliCommands.get("results.export")))
        assertEquals(CancelStep.MARK_CANCELLING_AND_STOP_SCREEN, CliOutcome.cancelStep("running", capture))
        assertEquals(CancelStep.MARK_CANCELLING_AND_STOP_SCREEN, CliOutcome.cancelStep("cancelling", CliCommands.get("record.start")))
    }

    @Test fun `a screen leaving abandons only requests it had not started and did not hand over`() {
        assertTrue(CliOutcome.abandonedOnDetach(handover = false, state = "accepted"))
        assertTrue(CliOutcome.abandonedOnDetach(handover = false, state = "preparing"))
        assertFalse(CliOutcome.abandonedOnDetach(handover = true, state = "preparing"))
        assertFalse(CliOutcome.abandonedOnDetach(handover = false, state = "running"))
        assertFalse(CliOutcome.abandonedOnDetach(handover = false, state = null))
    }
}
