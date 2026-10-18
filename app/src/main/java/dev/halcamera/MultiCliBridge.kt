package dev.halcamera

import android.net.Uri
import android.os.Build
import dev.halcamera.camera.*
import dev.halcamera.cli.*

/** The Activity supplies camera operations; MultiController owns the CLI request. */
internal fun ConcurrentCameraActivity.createMultiCli(): MultiController = MultiController(cli, object : MultiController.Driver {
    override fun busy() = mediaBusy
    override fun prepare(command: CliCommand) {
        val next = CliMultiOptions.resolve(command, Build.VERSION.SDK_INT,
            if (Build.VERSION.SDK_INT >= 30) readConcurrentPlans(manager) else emptyList())
        cliPaused = true
        closeSession {
            if (cli.active?.id != command.id || !foreground) return@closeSession
            engineName = command.engine ?: "Camera2"; videoMode = command.spec.recording
            selectedIds = next.streams.map { it.camera.id }; selected = 0; plans = listOf(next)
            chosenSizes = next.streams.associate { it.camera.id to (it.preview to it.photo) }
            arrangement = SplitArrangement.identity(next.streams.size)
            activeIndex = 0; failed = false; cliPaused = false
            startIfReady()
        }
    }
    override fun info(): Map<String, Any?> = mapOf("engine" to engineName, "ready" to ready,
        "recording" to recording, "cameras" to plans.getOrNull(selected)?.streams.orEmpty().map {
            mapOf("camera_id" to it.camera.id, "preview_size" to it.preview.toString(), "photo_size" to it.photo.toString())
        }, "events" to CliEvents.recent(recorder))
    override fun capture() { takingPhoto = true; session?.capture(displayDegrees()); updateButtons() }
    override fun record(audio: Boolean) { videoPending = true; session?.startVideo(audio); updateButtons() }
    override fun stopRecording() { videoPending = true; session?.stopVideo(); updateButtons() }
    override fun close(done: () -> Unit) { cliPaused = true; closeSession(done) }
    override fun events(command: CliCommand) {
        if (!recorder.trigger(command.id)) throw CliFailure("BUSY", "Events are already being saved")
        cli.state(command.id, "running")
    }
    override fun cancelEvents() { recorder.finish("cancelled")?.let(::exportIncident) }
})

internal fun ConcurrentCameraActivity.multiArtifacts(outcomes: Map<String, Result<Uri>>, video: Boolean) {
    val command = cli.active?.takeIf { it.spec.flow == CliCommandSpec.Flow.MULTI } ?: return
    multiCli.media(outcomes.mapValues { (id, outcome) -> outcome.map { uri ->
        CliArtifact("multi_${command.id}_$id.${if (video) "mp4" else "jpg"}",
            if (video) "video/mp4" else "image/jpeg", uri)
    } })
}
