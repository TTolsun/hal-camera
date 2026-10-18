package dev.halcamera.cli

import org.junit.Assert.*
import org.junit.Test

class MultiControllerTest {
    private class Sink(command: CliCommand) : CliExecution {
        override var active: CliCommand? = command
        var result: Map<String, Any?>? = null
        var failure: String? = null
        var filed = false
        var completions = 0
        override fun beginHandover() = Unit
        override fun recordingStarted(command: CliCommand, streams: Map<String, Any?>): Boolean { filed = true; return true }
        override fun recording(id: String) = filed
        override fun updateRecord(id: String, fields: Map<String, Any?>) = Unit
        override fun state(id: String, value: String) = true
        override fun fail(id: String, code: String, message: String) { failure = code; active = null }
        override fun complete(id: String, result: Map<String, Any?>, artifacts: List<CliArtifact>, failure: CliFailure?) {
            this.result = result; this.failure = failure?.code; completions++; active = null
        }
    }
    private class Driver : MultiController.Driver {
        val calls = mutableListOf<String>()
        var closed: (() -> Unit)? = null
        override fun busy() = false
        override fun prepare(command: CliCommand) { calls += "prepare" }
        override fun info() = mapOf("ready" to true)
        override fun capture() { calls += "capture" }
        override fun record(audio: Boolean) { calls += "record:$audio" }
        override fun stopRecording() { calls += "stop" }
        override fun close(done: () -> Unit) { calls += "close"; closed = done }
        override fun events(command: CliCommand) = Unit
        override fun cancelEvents() = Unit
    }
    private fun command(name: String = "multi.record") = AdbArguments.command(name, mapOf("cameras" to "1,0", "audio" to false).filterKeys { it != "audio" || name.endsWith("record") })

    @Test fun `preview waits for ready and duplicate ready callbacks submit only once`() {
        val command = command("multi.preview"); val sink = Sink(command); val driver = Driver(); val host = MultiController(sink, driver)
        host.execute(command); assertNull(sink.result)
        host.ready(); host.ready()
        assertEquals(true, sink.result?.get("camera_ready")); assertEquals(1, sink.completions)
        assertEquals(listOf("prepare"), driver.calls)
    }

    @Test fun `stop and cancel while recording is starting wait for the start callback`() {
        for (cancel in listOf(false, true)) {
            val command = command(); val sink = Sink(command); val driver = Driver(); val host = MultiController(sink, driver)
            host.execute(command); host.ready(); host.ready()
            if (cancel) host.cancel(command) else host.stopRecording(command)
            assertEquals(listOf("prepare", "record:false"), driver.calls)
            host.recordingStarted()
            assertEquals(!cancel, sink.filed); assertEquals("stop", driver.calls.last())
            host.media(mapOf("0" to Result.failure(Exception("zero failed")), "1" to Result.failure(Exception("one failed"))))
            assertEquals(if (cancel) "CANCELLED" else "RECORDING_FAILED", sink.failure)
            val rows = sink.result!!["cameras"] as List<*>
            assertEquals(listOf("1", "0"), rows.map { (it as Map<*, *>)["camera_id"] })
            host.recordingStarted(); assertEquals(1, sink.completions)
        }
    }

    @Test fun `cancel before preview ready closes before completion and never captures`() {
        val command = command("multi.capture"); val sink = Sink(command); val driver = Driver(); val host = MultiController(sink, driver)
        host.execute(command); host.cancel(command); host.ready()
        assertNull(sink.failure); assertFalse("capture" in driver.calls)
        driver.closed!!(); assertEquals("CANCELLED", sink.failure)
    }

    @Test fun `foreground loss closes camera and ignores late callbacks`() {
        val command = command(); val sink = Sink(command); val driver = Driver(); val host = MultiController(sink, driver)
        host.execute(command); host.ready(); host.stopped(); driver.closed!!()
        host.recordingStarted(); host.ready()
        assertEquals("APP_NOT_FOREGROUND", sink.failure); assertFalse(sink.filed)
    }

    @Test fun `stop before preview ready closes without starting a recording`() {
        val command = command(); val sink = Sink(command); val driver = Driver(); val host = MultiController(sink, driver)
        host.execute(command); host.stopRecording(command); host.ready(); driver.closed!!()
        assertEquals(listOf("prepare", "close"), driver.calls)
        assertEquals("PREFLIGHT_FAILED", sink.failure)
    }

    @Test fun `navigation waits for close completion before Live may open a camera`() {
        val command = command("multi.preview"); val sink = Sink(command); val driver = Driver(); val host = MultiController(sink, driver)
        var navigated = false
        host.closeForNavigation { navigated = true }
        assertFalse(navigated)
        driver.closed!!(); assertTrue(navigated)
    }

    @Test fun `reverse callbacks preserve file ownership and partial success`() {
        val result = MultiResults(listOf("1", "0", "3"), linkedMapOf(
            "0" to Result.success("rear.jpg"), "1" to Result.failure(Exception("Front failed"))))
        assertEquals(listOf("rear.jpg"), result.artifacts)
        assertEquals(listOf("1", "0", "3"), result.cameras.map { it["camera_id"] })
        assertEquals(listOf(false, true, false), result.cameras.map { it["saved"] })
        assertEquals("Front failed", result.cameras[0]["error"])
        assertEquals("No file saved", result.cameras[2]["error"])
    }
}
