package dev.halcamera.cli

import dev.halcamera.camera.PhotoResult
import android.net.Uri
import dev.halcamera.cli.CliCommandSpec.Flow

/** Owns the asynchronous CLI LIVE flow; the Activity supplies its actual preview and engine. */
class LiveController(private val commands: CliExecution, private val driver: Driver) : CliHost, CliControlsHost, CliRecordingHost, CliSnapshotHost {
    interface Driver {
        fun prepare(command: CliCommand, ready: () -> Unit)
        fun prepareScene(command: CliCommand, ready: () -> Unit)
        fun tune(options: CliOptions?, reset: Boolean): Map<String, Any?>
        fun sequence(command: CliCommand, done: (Map<String, Any?>, List<CliArtifact>, CliFailure?) -> Unit)
        fun cancelSequence()
        fun cancelEvents()
        fun inspect(command: CliCommand)
        fun dual(command: CliCommand)
        fun multi(command: CliCommand)
        fun snapshot(done: (Result<PhotoResult>) -> Unit)
        fun streamInfo(): Map<String, Any?>
        fun photoLabels(): List<String>
        fun capture(id: String, done: (Result<PhotoResult>) -> Unit)
        fun record(audio: Boolean, started: () -> Unit, done: (Result<Uri>) -> Unit)
        fun stopRecording()
        fun stopPreview(done: () -> Unit)
        /** Hands the screen over to the CTS suite run for [command]; the suite screen completes the request. */
        fun cts(command: CliCommand)
        fun benchmark(command: CliCommand)
        fun stopPreparing()
        fun busy(): Boolean
    }
    override val screen = "live"
    override fun isBusy() = driver.busy()
    override fun tune(options: CliOptions?, reset: Boolean) = driver.tune(options, reset)
    private var pending: CliCommand? = null
    private var prepared = false
    private var submitted = false
    private var scenePrepared = false
    private var cancelled = false
    private var recordingStopped = false
    private val snapshots = mutableListOf<CliArtifact>()
    private var snapshotPending = false
    private var stopAfterSnapshot = false

    override fun execute(command: CliCommand) {
        snapshots.clear(); snapshotPending = false; stopAfterSnapshot = false
        pending = command; prepared = false; submitted = false; cancelled = false; recordingStopped = false
        scenePrepared = false
        when (command.spec.flow) {
            Flow.INSPECT, Flow.EVENTS -> {
                submitted = true
                driver.inspect(command)
            }
            Flow.DUAL -> { commands.beginHandover(); driver.dual(command); pending = null }
            Flow.MULTI -> { commands.beginHandover(); driver.multi(command); pending = null }
            Flow.STOP_PREVIEW -> {
                submitted = true
                commands.state(command.id, "running")
                driver.stopPreview {
                    pending = null
                    commands.complete(command.id, mapOf("camera_ready" to false))
                }
            }
            Flow.CTS, Flow.BENCHMARK -> {
                commands.beginHandover()
                if (command.spec.flow == Flow.BENCHMARK) driver.benchmark(command) else driver.cts(command)
                pending = null
            }
            Flow.PREVIEW, Flow.CAPTURE, Flow.SEQUENCE, Flow.RECORD -> driver.prepare(command) { prepared = true }
            Flow.NONE -> { pending = null; throw CliFailure("APP_NOT_FOREGROUND", "${command.command} does not run on Live") }
        }
    }

    fun previewReady() {
        val command = pending ?: return
        if (!prepared || submitted || commands.active?.id != command.id) return
        submitted = true
        driver.prepareScene(command) {
            if (commands.active?.id == command.id && !cancelled && !scenePrepared) {
                scenePrepared = true
                try { runReady(command) }
                catch (e: Exception) { pending = null; commands.fail(command.id, (e as? CliFailure)?.code ?: "EXECUTION_FAILED", e.message ?: "Cannot execute prepared scene") }
            }
        }
    }

    private fun runReady(command: CliCommand) {
        if (!commands.state(command.id, "running")) { pending = null; driver.stopPreparing(); return }
        when (command.spec.flow) {
            Flow.SEQUENCE -> driver.sequence(command) { result, artifacts, failure ->
                pending = null
                commands.complete(command.id, result, artifacts, failure)
            }
            Flow.PREVIEW -> {
                pending = null
                commands.complete(command.id, cliResult("camera_id" to command.camera, "camera_ready" to true,
                    "engine" to (command.engine ?: "Camera2"), "mode" to if (command.videoMode) "Video" else "Photo", "streams" to driver.streamInfo()))
            }
            Flow.RECORD -> record(command)
            else -> driver.capture(command.id) { result ->
                pending = null
                result.fold({ photo ->
                    commands.complete(command.id, cliResult("camera_id" to command.camera, "capture_id" to photo.name,
                        "sensor_timestamp_ns" to photo.sensorTimestamp, "artifact_count" to photo.uris.size),
                        if (photo.artifacts.isNotEmpty()) photo.artifacts.map { CliArtifact(it.name, it.mime, it.uri) }
                        else photo.uris.mapIndexed { index, uri -> CliArtifact("${photo.name}_${driver.photoLabels()[index]}.jpg", "image/jpeg", uri) })
                }, { commands.fail(command.id, "CAPTURE_FAILED", it.message ?: "Capture failed") })
            }
        }
    }

    private fun record(command: CliCommand) {
        var recordingStreams = emptyMap<String, Any?>()
        driver.record(command.audio != false, started = {
            recordingStreams = driver.streamInfo()
            if (commands.active?.id == command.id && !recordingStopped && !cancelled) {
                if (!commands.recordingStarted(command, recordingStreams)) driver.stopRecording()
            }
        }) { result ->
            pending = null
            result.fold({ uri ->
                commands.complete(command.id, cliResult("camera_id" to command.camera, "recording" to false,
                    "audio" to (command.audio != false), "cancelled" to cancelled, "engine" to (command.engine ?: "Camera2"),
                    "streams" to recordingStreams, "artifact_count" to 1 + snapshots.size),
                    listOf(CliArtifact("recording_${command.id}.mp4", "video/mp4", uri)) + snapshots,
                    if (!recordingStopped && !cancelled) CliFailure("RECORDING_INTERRUPTED", "Recording stopped without a CLI stop request") else null)
            }, { commands.fail(command.id, if (cancelled) "CANCELLED" else "RECORDING_FAILED", it.message ?: "Recording failed") })
        }
    }

    override fun cancel(command: CliCommand) {
        cancelled = true
        val flow = command.spec.flow
        if (!submitted || (command.spec.streams && !scenePrepared)) { pending = null; driver.stopPreparing() }
        else if (flow == Flow.EVENTS) driver.cancelEvents()
        else if (flow == Flow.RECORD) stopRecording(command)
        else if (flow == Flow.SEQUENCE) driver.cancelSequence()
        else if (flow != Flow.CAPTURE) commands.fail(command.id, "CANCELLED", "Operation cancelled")
        // A submitted capture completes through its saved-pair callback, even after Activity.stop.
    }

    override fun snapshot(command: CliCommand) {
        if (snapshotPending || recordingStopped || cancelled || !commands.recording(command.id))
            throw CliFailure("BUSY", "Wait until recording or the previous snapshot is ready")
        snapshotPending = true
        commands.updateRecord(command.id, mapOf("snapshot_pending" to true))
        val finish: (Result<PhotoResult>) -> Unit = { result ->
            snapshotPending = false
            result.getOrNull()?.let { photo -> snapshots += photo.artifacts.ifEmpty { photo.uris.mapIndexed { i, uri ->
                dev.halcamera.camera.PhotoArtifact("${photo.name}_snapshot_$i.jpg", "image/jpeg", uri)
            } }.map { CliArtifact(it.name, it.mime, it.uri) } }
            if (commands.active?.id == command.id) runCatching { commands.updateRecord(command.id, mapOf(
                "snapshot_count" to snapshots.size, "snapshot_pending" to false, "snapshot_error" to result.exceptionOrNull()?.message))
            }
            if (stopAfterSnapshot && commands.active?.id == command.id) stopRecording(command)
        }
        try { driver.snapshot(finish) }
        catch (e: Exception) { finish(Result.failure(e)) }
    }

    override fun stopRecording(command: CliCommand) {
        if (snapshotPending) { stopAfterSnapshot = true; return }
        if (pending?.id != command.id || command.spec.flow != Flow.RECORD)
            throw CliFailure("NOT_RECORDING", "No matching recording is active")
        if (!submitted || !scenePrepared) {
            pending = null
            driver.stopPreparing()
            commands.fail(command.id, "NOT_RECORDING", "Recording stopped before preview was ready")
        } else if (!recordingStopped) {
            recordingStopped = true
            driver.stopRecording()
        }
    }
}
