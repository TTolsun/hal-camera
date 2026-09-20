package dev.halcamera.cli

import org.junit.Assert.*
import org.junit.Test

class CliCommandTest {
    private val id = "b616d5cc-7706-4983-b49e-4e4d5c0ef816"
    private fun reject(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (error: CliFailure) { assertEquals(code, error.code) }
    }

    @Test fun `unknown commands and inappropriate parameters are rejected`() {
        reject("INVALID_ARGUMENT") { CliCommand(id, "delete", null, null, 30_000) }
        reject("INVALID_ARGUMENT") { CliCommand(id, "capture", null, null, 30_000) }
        reject("INVALID_ARGUMENT") { CliCommand(id, "cameras", "0", null, 30_000) }
        reject("INVALID_ARGUMENT") { CliCommand(id, "capture", "0", "anything", 30_000) }
    }
    @Test fun `benchmark is excluded and recording parameters stay scoped`() {
        reject("INVALID_ARGUMENT") { CliCommand(id, "benchmark.run", "0", "camera2-standard-v1", 180_000) }
        reject("INVALID_ARGUMENT") { CliCommand(id, "capture", "0", null, 30_000, audio = true) }
        assertFalse(CliCommand(id, "record.start", "0", null, 30_000, audio = false).audio!!)
        assertNull(CliCommand(id, "preview.stop", null, null, 30_000).camera)
    }
    @Test fun `probe and cts commands take no camera and cts run needs suite keys`() {
        assertNull(CliCommand(id, "probe", null, null, 30_000).camera)
        assertNull(CliCommand(id, "cts.cases", null, null, 30_000).cases)
        reject("INVALID_ARGUMENT") { CliCommand(id, "probe", "0", null, 30_000) }
        reject("INVALID_ARGUMENT") { CliCommand(id, "probe", null, null, 30_000, listOf("custom:fast_on_off")) }
        reject("INVALID_ARGUMENT") { CliCommand(id, "cts.run", null, null, 30_000) }
        reject("INVALID_ARGUMENT") { CliCommand(id, "cts.run", null, null, 30_000, emptyList()) }
        reject("INVALID_ARGUMENT") { CliCommand(id, "cts.run", null, null, 30_000, listOf("fast_on_off")) }
        reject("INVALID_ARGUMENT") { CliCommand(id, "cts.run", null, null, 30_000, listOf("custom:a", "custom:a")) }
        reject("INVALID_ARGUMENT") { CliCommand(id, "cts.run", null, null, 30_000, List(CliCommand.MAX_CASES + 1) { "custom:$it" }) }
        reject("INVALID_ARGUMENT") { CliCommand(id, "cts.run", "0", null, 30_000, listOf("custom:fast_on_off")) }
        reject("INVALID_ARGUMENT") { CliCommand(id, "capture", "0", null, 30_000, listOf("custom:fast_on_off")) }
        val run = CliCommand(id, "cts.run", null, null, 1_800_000, listOf("custom:fast_on_off", "vendored:android.hardware.camera2.cts.RecordingTest#testBasicRecording"))
        assertEquals(2, run.cases!!.size)
    }
    @Test fun `hello lists every command the coordinator dispatches`() {
        assertEquals(listOf("cameras", "preview", "preview.stop", "capture", "record.start", "probe", "cts.cases", "cts.run"), CliCommand.COMMANDS)
    }
    @Test fun `ids must have canonical shape and cannot form paths`() {
        listOf("../foo", "1-1-1-1-1", id.uppercase(), "", "$id/extra").forEach { bad ->
            reject("INVALID_ARGUMENT") { CliCommand.validateId(bad) }
        }
        CliCommand.validateId(id)
    }
    @Test fun `execution deadlines are bounded`() {
        listOf(-1L, 0L, 3_600_001L, Long.MAX_VALUE).forEach { timeout ->
            reject("INVALID_ARGUMENT") { CliCommand(id, "capture", "0", null, timeout) }
        }
    }
    @Test fun `completed requests cannot restart or change terminal outcome`() {
        for (state in CliStates.terminal) {
            assertFalse(CliStates.allows(state, "running"))
            assertFalse(CliStates.allows(state, "accepted"))
            for (other in CliStates.terminal - state) assertFalse(CliStates.allows(state, other))
        }
    }
    @Test fun `success requires save stage but late cancellation can still save`() {
        assertFalse(CliStates.allows("running", "succeeded"))
        assertTrue(CliStates.allows("running", "saving"))
        assertTrue(CliStates.allows("saving", "succeeded"))
        assertTrue(CliStates.allows("running", "cancelling"))
        assertTrue(CliStates.allows("cancelling", "saving"))
        assertTrue(CliStates.allows("saving", "cancelled"))
    }
}
