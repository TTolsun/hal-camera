package dev.halcamera.camera

import dev.halcamera.cli.CliArtifact
import dev.halcamera.cli.CliCommand
import dev.halcamera.cli.CliHost
import dev.halcamera.cli.CommandCoordinator
import org.json.JSONObject

/** Owns the asynchronous CLI LIVE flow; the Activity supplies its actual preview and engine. */
class LiveController(private val commands: CommandCoordinator, private val driver: Driver) : CliHost {
    interface Driver {
        fun prepare(camera: String)
        fun capture(id: String, done: (Result<PhotoResult>) -> Unit)
        fun benchmark(command: CliCommand)
        fun stopPreparing()
        fun busy(): Boolean
    }
    override val screen = "live"
    override fun isBusy() = driver.busy()
    private var pending: CliCommand? = null
    private var submitted = false

    override fun execute(command: CliCommand) {
        pending = command; submitted = false
        if (command.command == "benchmark.run") {
            commands.beginHandover()
            driver.benchmark(command)
            pending = null
        } else driver.prepare(requireNotNull(command.camera))
    }

    fun previewReady() {
        val command = pending ?: return
        if (submitted || commands.active?.id != command.id) return
        submitted = true
        if (!commands.state(command.id, "running")) { pending = null; driver.stopPreparing(); return }
        if (command.command == "preview") {
            pending = null
            commands.complete(command.id, JSONObject().put("camera_id", command.camera).put("camera_ready", true))
        } else driver.capture(command.id) { result ->
            pending = null
            result.fold({ photo ->
                commands.complete(command.id, JSONObject().put("camera_id", command.camera)
                    .put("capture_id", photo.name).put("sensor_timestamp_ns", photo.sensorTimestamp).put("artifact_count", 2),
                    photo.uris.mapIndexed { index, uri -> CliArtifact("${photo.name}_${if (index == 0) "YUV" else "JPEG"}.jpg", "image/jpeg", uri) })
            }, { commands.fail(command.id, "CAPTURE_FAILED", it.message ?: "Capture failed") })
        }
    }

    override fun cancel(command: CliCommand) {
        if (!submitted) { pending = null; driver.stopPreparing() }
        // A submitted capture completes through its saved-pair callback, even after Activity.stop.
    }
}

data class PhotoResult(val requestId: String?, val name: String, val sensorTimestamp: Long, val uris: List<android.net.Uri>)
