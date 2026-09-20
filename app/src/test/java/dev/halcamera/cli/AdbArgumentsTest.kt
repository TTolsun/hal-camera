package dev.halcamera.cli

import org.junit.Assert.*
import org.junit.Test

class AdbArgumentsTest {
    @Test fun `simple commands create unique ids and supply camera defaults`() {
        val first = AdbArguments.command("capture", emptyMap())
        val second = AdbArguments.command("capture", emptyMap())
        CliCommand.validateId(first.id)
        assertNotEquals(first.id, second.id)
        assertEquals("0", first.camera)
        assertEquals(30_000L, first.timeoutMs)
        assertEquals("preview", AdbArguments.command("preview.start", emptyMap()).command)
    }

    @Test fun `recording accepts typed shell extras and replay id`() {
        val id = "b616d5cc-7706-4983-b49e-4e4d5c0ef816"
        val command = AdbArguments.command("record.start", mapOf("camera" to "1", "audio" to false, "request_id" to id, "timeout_ms" to 90_000L))
        assertEquals(id, command.id)
        assertEquals("1", command.camera)
        assertEquals(false, command.audio)
        assertEquals(90_000L, command.timeoutMs)
        assertEquals(3_600_000L, AdbArguments.command("record.start", emptyMap()).timeoutMs)
    }

    @Test fun `bad flags and types cannot silently change requested operation`() {
        val invalid = listOf(
            "capture" to mapOf("audio" to false), "capture" to mapOf("camera" to 0),
            "capture" to mapOf("timeout_ms" to "30000"), "record.start" to mapOf("audio" to "false"),
            "capture" to mapOf("timeout_ms" to 0), "preview.stop" to mapOf("camera" to "0"),
            "benchmark.run" to emptyMap(), "cts.run" to emptyMap()
        )
        invalid.forEach { (name, values) ->
            try { AdbArguments.command(name, values); fail("Accepted $name $values") }
            catch (e: CliFailure) { assertEquals("INVALID_ARGUMENT", e.code) }
        }
    }

    @Test fun `cts keys retain their prefix and method delimiter`() {
        val keys = "custom:fast_on_off,vendored:android.hardware.camera2.cts.RecordingTest#testBasicRecording"
        assertEquals(keys.split(","), AdbArguments.command("cts.run", mapOf("cases" to keys)).cases)
    }
}
