package dev.halcamera.cli

import android.net.Uri
import dev.halcamera.camera.PhotoResult
import org.json.JSONObject

/** Deterministic lifecycle checks use real JSONObject, without opening a camera or writing media. */
internal object CliFlowChecks {
    private class Execution(command: CliCommand) : CliExecution {
        override var active: CliCommand? = command
        val record = JSONObject().put("state", "preparing")
        var error: String? = null
        override fun beginHandover() = Unit
        override fun state(id: String, value: String): Boolean { record.put("state", value); return true }
        override fun fail(id: String, code: String, message: String) { error = code; active = null }
        override fun complete(id: String, result: Map<String, Any?>, artifacts: List<CliArtifact>, failure: CliFailure?) { active = null }
        override fun recordingStarted(command: CliCommand, streams: Map<String, Any?>): Boolean {
            record.put("result", JSONObject().put("recording", true)); return true
        }
        override fun recording(id: String) = record.optJSONObject("result")?.optBoolean("recording") == true
        override fun updateRecord(id: String, fields: Map<String, Any?>) { fields.forEach { (key, value) -> record.put(key, value) } }
    }

    private class Driver : LiveController.Driver {
        var sceneReady: (() -> Unit)? = null
        var snapshotDone: ((Result<PhotoResult>) -> Unit)? = null
        var captures = 0
        var recordings = 0
        var stops = 0
        var preparationStops = 0
        var captureFailure = false
        override fun prepare(command: CliCommand, ready: () -> Unit) = ready()
        override fun prepareScene(command: CliCommand, ready: () -> Unit) { sceneReady = ready }
        override fun tune(options: CliOptions?, reset: Boolean) = emptyMap<String, Any?>()
        override fun sequence(command: CliCommand, done: (Map<String, Any?>, List<CliArtifact>, CliFailure?) -> Unit) = Unit
        override fun cancelSequence() = Unit
        override fun cancelEvents() = Unit
        override fun inspect(command: CliCommand) = Unit
        override fun dual(command: CliCommand) = Unit
        override fun snapshot(done: (Result<PhotoResult>) -> Unit) { snapshotDone = done }
        override fun streamInfo() = emptyMap<String, Any?>()
        override fun photoLabels() = listOf("JPEG")
        override fun capture(id: String, done: (Result<PhotoResult>) -> Unit) { if (captureFailure) throw IllegalStateException("Capture unavailable"); captures++ }
        override fun record(audio: Boolean, started: () -> Unit, done: (Result<Uri>) -> Unit) { recordings++; started() }
        override fun stopRecording() { stops++ }
        override fun stopPreview(done: () -> Unit) = done()
        override fun cts(command: CliCommand) = Unit
        override fun benchmark(command: CliCommand) = Unit
        override fun stopPreparing() { preparationStops++ }
        override fun busy() = false
    }

    fun run(): Int {
        val capture = AdbArguments.command("capture", emptyMap())
        val sink = Execution(capture); val driver = Driver(); val live = LiveController(sink, driver)
        live.execute(capture); live.previewReady(); live.previewReady()
        check(driver.captures == 0 && sink.record.getString("state") == "preparing")
        driver.sceneReady!!()
        driver.sceneReady!!()
        check(driver.captures == 1 && sink.record.getString("state") == "running")

        val failureSink = Execution(capture); val failureDriver = Driver().apply { captureFailure = true }
        val failed = LiveController(failureSink, failureDriver)
        failed.execute(capture); failed.previewReady(); failureDriver.sceneReady!!()
        check(failureSink.error == "EXECUTION_FAILED")

        val cancelledSink = Execution(capture); val cancelledDriver = Driver(); val cancelled = LiveController(cancelledSink, cancelledDriver)
        cancelled.execute(capture); cancelled.previewReady(); cancelled.cancel(capture); cancelledSink.active = null
        cancelledDriver.sceneReady!!()
        check(cancelledDriver.captures == 0 && cancelledDriver.preparationStops == 1)

        val recording = AdbArguments.command("record.start", emptyMap())
        val earlySink = Execution(recording); val earlyDriver = Driver(); val early = LiveController(earlySink, earlyDriver)
        early.execute(recording); early.previewReady(); early.stopRecording(recording); earlyDriver.sceneReady!!()
        check(earlyDriver.recordings == 0 && earlyDriver.stops == 0 && earlyDriver.preparationStops == 1 && earlySink.error == "NOT_RECORDING")

        val recordingSink = Execution(recording); val recordingDriver = Driver(); val active = LiveController(recordingSink, recordingDriver)
        active.execute(recording); active.previewReady(); recordingDriver.sceneReady!!()
        active.snapshot(recording); active.stopRecording(recording)
        check(recordingDriver.stops == 0)
        recordingDriver.snapshotDone!!(Result.success(PhotoResult(recording.id, "snapshot", 1L, listOf(Uri.parse("content://test/snapshot")))))
        check(recordingDriver.stops == 1 && recordingSink.record.getInt("snapshot_count") == 1 && !recordingSink.record.getBoolean("snapshot_pending"))
        return 5
    }
}
