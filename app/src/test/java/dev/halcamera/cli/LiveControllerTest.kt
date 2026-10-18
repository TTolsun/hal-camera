package dev.halcamera.cli

import android.net.Uri
import dev.halcamera.camera.PhotoResult
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * The Live request flow with a fake screen and request record. Paths that need a real Uri (a saved photo or
 * video) stay in the instrumented CliFlowChecks; everything else about ordering and cancellation runs here.
 */
class LiveControllerTest {
    private class Execution(command: CliCommand) : CliExecution {
        override var active: CliCommand? = command
        var state = "preparing"
        var error: String? = null
        var result: Map<String, Any?>? = null
        var failure: CliFailure? = null
        var handedOver = false
        var recordingFiled = false
        val fields = mutableMapOf<String, Any?>()
        override fun beginHandover() { handedOver = true }
        override fun state(id: String, value: String): Boolean { state = value; return true }
        override fun fail(id: String, code: String, message: String) { error = code; active = null }
        override fun complete(id: String, result: Map<String, Any?>, artifacts: List<CliArtifact>, failure: CliFailure?) {
            this.result = result; this.failure = failure; active = null
        }
        override fun recordingStarted(command: CliCommand, streams: Map<String, Any?>): Boolean { recordingFiled = true; return true }
        override fun recording(id: String) = recordingFiled
        override fun updateRecord(id: String, fields: Map<String, Any?>) {
            fields.forEach { (key, value) -> if (value == null) this.fields.remove(key) else this.fields[key] = value }
        }
    }

    private class Driver : LiveController.Driver {
        var sceneReady: (() -> Unit)? = null
        var snapshotDone: ((Result<PhotoResult>) -> Unit)? = null
        var recordDone: ((Result<Uri>) -> Unit)? = null
        var captureFailure = false
        val calls = mutableListOf<String>()
        override fun prepare(command: CliCommand, ready: () -> Unit) { calls += "prepare"; ready() }
        override fun prepareScene(command: CliCommand, ready: () -> Unit) { sceneReady = ready }
        override fun tune(options: CliOptions?, reset: Boolean) = mapOf("zoom" to 1f)
        override fun sequence(command: CliCommand, done: (Map<String, Any?>, List<CliArtifact>, CliFailure?) -> Unit) { calls += "sequence"; done(mapOf("saved" to 3), emptyList(), null) }
        override fun cancelSequence() { calls += "cancelSequence" }
        override fun cancelEvents() { calls += "cancelEvents" }
        override fun inspect(command: CliCommand) { calls += "inspect:${command.command}" }
        override fun dual(command: CliCommand) { calls += "dual" }
        override fun snapshot(done: (Result<PhotoResult>) -> Unit) { snapshotDone = done }
        override fun streamInfo() = mapOf<String, Any?>("preview" to "1920x1080")
        override fun photoLabels() = listOf("JPEG")
        override fun capture(id: String, done: (Result<PhotoResult>) -> Unit) {
            if (captureFailure) throw IllegalStateException("Capture unavailable")
            calls += "capture"
        }
        override fun record(audio: Boolean, started: () -> Unit, done: (Result<Uri>) -> Unit) { calls += "record:$audio"; recordDone = done; started() }
        override fun stopRecording() { calls += "stopRecording" }
        override fun stopPreview(done: () -> Unit) { calls += "stopPreview"; done() }
        override fun cts(command: CliCommand) { calls += "cts" }
        override fun benchmark(command: CliCommand) { calls += "benchmark" }
        override fun stopPreparing() { calls += "stopPreparing" }
        override fun busy() = false
    }

    private fun command(method: String, values: Map<String, Any?> = emptyMap(), arg: String? = null) = AdbArguments.command(method, values, arg)
    private fun live(command: CliCommand, driver: Driver = Driver()): Triple<Execution, Driver, LiveController> {
        val sink = Execution(command)
        return Triple(sink, driver, LiveController(sink, driver))
    }

    @Test fun `capture runs once and only after the scene is ready`() {
        val capture = command("capture")
        val (sink, driver, live) = live(capture)
        live.execute(capture); live.previewReady(); live.previewReady()
        assertEquals(listOf("prepare"), driver.calls)
        assertEquals("preparing", sink.state)
        driver.sceneReady!!(); driver.sceneReady!!()
        assertEquals(listOf("prepare", "capture"), driver.calls)
        assertEquals("running", sink.state)
    }

    @Test fun `a capture that throws fails the request`() {
        val capture = command("capture")
        val (sink, driver, live) = live(capture, Driver().apply { captureFailure = true })
        live.execute(capture); live.previewReady(); driver.sceneReady!!()
        assertEquals("EXECUTION_FAILED", sink.error)
    }

    @Test fun `a cancel before the scene is ready stops preparing and never captures`() {
        val capture = command("capture")
        val (sink, driver, live) = live(capture)
        live.execute(capture); live.previewReady(); live.cancel(capture); sink.active = null
        driver.sceneReady!!()
        assertEquals(listOf("prepare", "stopPreparing"), driver.calls)
    }

    @Test fun `preview completes with the requested mode and negotiated streams`() {
        val preview = command("preview", mapOf("mode" to "Video"))
        val (sink, driver, live) = live(preview)
        live.execute(preview); live.previewReady(); driver.sceneReady!!()
        assertEquals(mapOf("camera_id" to "0", "camera_ready" to true, "engine" to "Camera2", "mode" to "Video",
            "streams" to mapOf("preview" to "1920x1080")), sink.result)
    }

    @Test fun `a sequence completes with the driver result`() {
        val burst = command("burst", mapOf("count" to "3"))
        val (sink, driver, live) = live(burst)
        live.execute(burst); live.previewReady(); driver.sceneReady!!()
        assertEquals(mapOf("saved" to 3), sink.result)
        live.cancel(burst)
        assertTrue("cancelSequence" in driver.calls)
    }

    @Test fun `stopping a recording before the preview is ready fails it without recording`() {
        val recording = command("record.start")
        val (sink, driver, live) = live(recording)
        live.execute(recording); live.previewReady(); live.stopRecording(recording); driver.sceneReady!!()
        assertEquals(listOf("prepare", "stopPreparing"), driver.calls)
        assertEquals("NOT_RECORDING", sink.error)
    }

    @Test fun `a stop waits for a pending snapshot and the record shows the snapshot state`() {
        val recording = command("record.start", mapOf("audio" to false))
        val (sink, driver, live) = live(recording)
        live.execute(recording); live.previewReady(); driver.sceneReady!!()
        assertTrue(sink.recordingFiled)
        assertTrue("record:false" in driver.calls)
        live.snapshot(recording)
        assertEquals(true, sink.fields["snapshot_pending"])
        try { live.snapshot(recording); fail() } catch (e: CliFailure) { assertEquals("BUSY", e.code) }
        live.stopRecording(recording)
        assertFalse("stopRecording" in driver.calls)
        driver.snapshotDone!!(Result.success(PhotoResult(recording.id, "snapshot", 1L, emptyList())))
        assertTrue("stopRecording" in driver.calls)
        assertEquals(mapOf("snapshot_pending" to false, "snapshot_count" to 0), sink.fields)
    }

    @Test fun `a snapshot before recording started is refused`() {
        val recording = command("record.start")
        val (_, _, live) = live(recording)
        live.execute(recording)
        try { live.snapshot(recording); fail() } catch (e: CliFailure) { assertEquals("BUSY", e.code) }
    }

    @Test fun `a cancelled recording that fails to save reports the cancellation`() {
        val recording = command("record.start")
        val (sink, driver, live) = live(recording)
        live.execute(recording); live.previewReady(); driver.sceneReady!!()
        live.cancel(recording)
        assertTrue("stopRecording" in driver.calls)
        driver.recordDone!!(Result.failure(IllegalStateException("No frames")))
        assertEquals("CANCELLED", sink.error)
    }

    @Test fun `screen handovers and diagnostics reach the driver directly`() {
        for ((method, values, expected) in listOf(
            Triple("dual.preview", mapOf("first" to "2", "second" to "3"), "dual"),
            Triple("benchmark.run", emptyMap(), "benchmark"))) {
            val request = command(method, values)
            val (sink, driver, live) = live(request)
            live.execute(request)
            assertTrue(method, sink.handedOver)
            assertEquals(listOf(expected), driver.calls)
        }
        val cts = command("cts.run", arg = "custom:fast_on_off")
        val (ctsSink, ctsDriver, ctsLive) = live(cts)
        ctsLive.execute(cts)
        assertTrue(ctsSink.handedOver)
        assertEquals(listOf("cts"), ctsDriver.calls)

        val info = command("live.info")
        val (infoSink, infoDriver, infoLive) = live(info)
        infoLive.execute(info); infoLive.cancel(info)
        assertEquals(listOf("inspect:live.info"), infoDriver.calls)
        assertEquals("CANCELLED", infoSink.error)

        val events = command("events")
        val (_, eventsDriver, eventsLive) = live(events)
        eventsLive.execute(events); eventsLive.cancel(events)
        assertEquals(listOf("inspect:events", "cancelEvents"), eventsDriver.calls)
    }

    @Test fun `stopping the preview reports the camera as closed`() {
        val stop = command("preview.stop")
        val (sink, driver, live) = live(stop)
        live.execute(stop)
        assertEquals("running", sink.state)
        assertEquals(listOf("stopPreview"), driver.calls)
        assertEquals(mapOf("camera_ready" to false), sink.result)
    }

    @Test fun `internal CLI contracts carry maps rather than org json`() {
        val dir = listOf("src/main/java/dev/halcamera/cli", "app/src/main/java/dev/halcamera/cli").map(::File).first { it.isDirectory }
        val pure = listOf("CliExecution.kt", "LiveController.kt", "CliCommandSpec.kt", "CliCommand.kt", "AdbArguments.kt",
            "CliPreflight.kt", "CliOutcome.kt", "CliArtifactRegistry.kt", "BenchmarkController.kt", "CtsController.kt", "CliSequence.kt")
        val offenders = pure.filter { File(dir, it).readText().contains("org.json") }
        assertEquals(emptyList<String>(), offenders)
    }
}
