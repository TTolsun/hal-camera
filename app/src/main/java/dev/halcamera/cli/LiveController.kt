package dev.halcamera.cli

import dev.halcamera.camera.PhotoResult
import android.net.Uri
import org.json.JSONObject

/** Owns the asynchronous CLI LIVE flow; the Activity supplies its actual preview and engine. */
class LiveController(private val commands: CommandCoordinator, private val driver: Driver) : CliHost {
    interface Driver {
        fun prepare(command: CliCommand, ready: () -> Unit)
        fun tune(options: CliOptions?, reset: Boolean): JSONObject
        fun sequence(command: CliCommand, done: (JSONObject, List<CliArtifact>, CliFailure?) -> Unit)
        fun cancelSequence()
        fun cancelEvents()
        fun inspect(command: CliCommand)
        fun dual(command: CliCommand)
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
    private var cancelled = false
    private var recordingStopped = false
    private val snapshots = mutableListOf<CliArtifact>()
    private var snapshotPending = false
    private var stopAfterSnapshot = false

    override fun execute(command: CliCommand) {
        snapshots.clear(); snapshotPending = false; stopAfterSnapshot = false
        pending = command; prepared = false; submitted = false; cancelled = false; recordingStopped = false
        if (command.command in setOf("live.info", "events", "meter")) {
            submitted = true
            driver.inspect(command)
        } else if (command.command.startsWith("dual.")) {
            commands.beginHandover(); driver.dual(command); pending = null
        } else if (command.command == "preview.stop") {
            submitted = true
            commands.state(command.id, "running")
            driver.stopPreview {
                pending = null
                commands.complete(command.id, JSONObject().put("camera_ready", false))
            }
        } else if (command.command in setOf("cts.run", "benchmark.run")) {
            commands.beginHandover()
            if (command.command == "benchmark.run") driver.benchmark(command) else driver.cts(command)
            pending = null
        } else driver.prepare(command) { prepared = true }
    }

    fun previewReady() {
        val command = pending ?: return
        if (!prepared || submitted || commands.active?.id != command.id) return
        submitted = true
        if (!commands.state(command.id, "running")) { pending = null; driver.stopPreparing(); return }
        if (command.command in setOf("burst", "bracket")) {
            driver.sequence(command) { result, artifacts, failure ->
                pending = null
                commands.complete(command.id, result, artifacts, failure)
            }
        } else if (command.command == "preview") {
            pending = null
            commands.complete(command.id, JSONObject().put("camera_id", command.camera).put("camera_ready", true)
                .put("engine", command.engine ?: "Camera2").put("streams", CliJson.of(driver.streamInfo())))
        } else if (command.command == "record.start") {
            var recordingStreams = emptyMap<String, Any?>()
            driver.record(command.audio != false, started = {
                recordingStreams = driver.streamInfo()
                if (commands.active?.id == command.id && !recordingStopped && !cancelled) {
                    if (!commands.recordingStarted(command, recordingStreams)) driver.stopRecording()
                }
            }) { result ->
                pending = null
                result.fold({ uri ->
                    commands.complete(command.id, JSONObject().put("camera_id", command.camera)
                        .put("recording", false).put("audio", command.audio != false).put("cancelled", cancelled)
                        .put("engine", command.engine ?: "Camera2").put("streams", CliJson.of(recordingStreams))
                        .put("artifact_count", 1 + snapshots.size), listOf(CliArtifact("recording_${command.id}.mp4", "video/mp4", uri)) + snapshots,
                        if (!recordingStopped && !cancelled) CliFailure("RECORDING_INTERRUPTED", "Recording stopped without a CLI stop request") else null)
                }, { commands.fail(command.id, if (cancelled) "CANCELLED" else "RECORDING_FAILED", it.message ?: "Recording failed") })
            }
        } else driver.capture(command.id) { result ->
            pending = null
            result.fold({ photo ->
                commands.complete(command.id, JSONObject().put("camera_id", command.camera)
                    .put("capture_id", photo.name).put("sensor_timestamp_ns", photo.sensorTimestamp).put("artifact_count", photo.uris.size),
                    if (photo.artifacts.isNotEmpty()) photo.artifacts.map { CliArtifact(it.name, it.mime, it.uri) }
                    else photo.uris.mapIndexed { index, uri -> CliArtifact("${photo.name}_${driver.photoLabels()[index]}.jpg", "image/jpeg", uri) })
            }, { commands.fail(command.id, "CAPTURE_FAILED", it.message ?: "Capture failed") })
        }
    }

    override fun cancel(command: CliCommand) {
        cancelled = true
        if (!submitted) { pending = null; driver.stopPreparing() }
        else if (command.command == "events") driver.cancelEvents()
        else if (command.command == "record.start") stopRecording(command)
        else if (command.command in setOf("burst", "bracket")) driver.cancelSequence()
        else if (command.command != "capture") commands.fail(command.id, "CANCELLED", "Operation cancelled")
        // A submitted capture completes through its saved-pair callback, even after Activity.stop.
    }

    override fun snapshot(command: CliCommand) {
        if (snapshotPending || recordingStopped || cancelled || commands.store.read(command.id)?.optJSONObject("result")?.optBoolean("recording") != true)
            throw CliFailure("BUSY", "Wait until recording or the previous snapshot is ready")
        snapshotPending = true
        commands.store.transition(command.id, "running") { it.put("snapshot_pending", true) }
        val finish: (Result<PhotoResult>) -> Unit = { result ->
            snapshotPending = false
            result.getOrNull()?.let { photo -> snapshots += photo.artifacts.ifEmpty { photo.uris.mapIndexed { i, uri ->
                dev.halcamera.camera.PhotoArtifact("${photo.name}_snapshot_$i.jpg", "image/jpeg", uri)
            } }.map { CliArtifact(it.name, it.mime, it.uri) } }
            if (commands.active?.id == command.id) runCatching { commands.store.update(command.id) {
                it.put("snapshot_count", snapshots.size).put("snapshot_pending", false).put("snapshot_error", result.exceptionOrNull()?.message)
            } }
            if (stopAfterSnapshot && commands.active?.id == command.id) stopRecording(command)
        }
        try { driver.snapshot(finish) }
        catch (e: Exception) { finish(Result.failure(e)) }
    }

    override fun stopRecording(command: CliCommand) {
        if (snapshotPending) { stopAfterSnapshot = true; return }
        if (pending?.id != command.id || command.command != "record.start")
            throw CliFailure("NOT_RECORDING", "No matching recording is active")
        if (!submitted) {
            pending = null
            driver.stopPreparing()
            commands.fail(command.id, "NOT_RECORDING", "Recording stopped before preview was ready")
        } else if (!recordingStopped) {
            recordingStopped = true
            driver.stopRecording()
        }
    }
}
