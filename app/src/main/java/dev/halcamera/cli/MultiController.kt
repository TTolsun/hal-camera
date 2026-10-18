package dev.halcamera.cli

/** Multi's request lifecycle is independent of Activity recreation and camera callback order. */
class MultiController(private val commands: CliExecution, private val driver: Driver) : CliHost, CliRecordingHost, CliNavigationHost {
    interface Driver {
        fun busy(): Boolean
        fun prepare(command: CliCommand)
        fun info(): Map<String, Any?>
        fun capture()
        fun record(audio: Boolean)
        fun stopRecording()
        fun close(done: () -> Unit)
        fun events(command: CliCommand)
        fun cancelEvents()
    }

    override val screen = "multi"
    override fun isBusy() = driver.busy()
    override fun closeForNavigation(done: () -> Unit) = driver.close(done)
    private var request: CliCommand? = null
    private var submitted = false
    private var started = false
    private var stopping = false
    private var cancelled = false
    private var completing = false
    private var foregroundLost = false

    override fun execute(command: CliCommand) {
        when (command.command) {
            "live.info" -> commands.complete(command.id, driver.info())
            "events" -> driver.events(command)
            "preview.stop" -> driver.close { commands.complete(command.id, mapOf("camera_ready" to false)) }
            "meter" -> throw CliFailure("PREFLIGHT_FAILED", "Multi CLI metering is not supported")
            else -> {
                if (command.spec.flow != CliCommandSpec.Flow.MULTI) throw CliFailure("PREFLIGHT_FAILED", "Unsupported Multi command")
                request = command; submitted = false; started = false; stopping = false
                cancelled = false; completing = false; foregroundLost = false
                driver.prepare(command)
            }
        }
    }

    fun ready() {
        val command = current() ?: return
        if (submitted || stopping || completing) return
        submitted = true
        if (!commands.state(command.id, "running")) return
        try {
            when (command.command) {
                "multi.preview" -> { completing = true; commands.complete(command.id, driver.info() + ("camera_ready" to true)) }
                "multi.capture" -> driver.capture()
                "multi.record" -> driver.record(command.audio != false)
            }
        } catch (error: Exception) { failed(error.message ?: "Multi operation failed") }
    }

    fun recordingStarted() {
        val command = current() ?: return
        if (completing || !command.spec.recording) return
        started = true
        if (!cancelled && !commands.recordingStarted(command, driver.info())) { driver.stopRecording(); return }
        if (stopping) driver.stopRecording()
    }

    /** Keyed outcomes preserve ownership even when the callbacks complete in reverse camera order. */
    fun media(outcomes: Map<String, Result<CliArtifact>>) {
        val command = current() ?: return
        if (completing || command.command == "multi.preview") return
        val ids = CliMultiOptions.ids(command.options!!.values.getValue("cameras"))
        val result = MultiResults(ids, outcomes)
        val artifacts = result.artifacts
        val failure = when {
            foregroundLost -> CliFailure("APP_NOT_FOREGROUND", "Multi left foreground")
            cancelled -> CliFailure("CANCELLED", "Multi operation stopped")
            artifacts.size != ids.size -> CliFailure(if (command.spec.recording) "RECORDING_FAILED" else "CAPTURE_FAILED", "Not every camera saved a file")
            command.spec.recording && !stopping -> CliFailure("RECORDING_INTERRUPTED", "Multi recording stopped unexpectedly")
            else -> null
        }
        completing = true
        commands.complete(command.id, mapOf("cameras" to result.cameras, "recording" to false,
            "artifact_count" to artifacts.size, "cancelled" to cancelled), artifacts, failure)
    }

    fun failed(reason: String) {
        val command = current() ?: return
        if (completing) return
        // Close first; an in-flight photo may still report saved files before close(done).
        stopping = true
        driver.close {
            if (!completing && current() != null) {
                completing = true
                commands.fail(command.id, if (foregroundLost) "APP_NOT_FOREGROUND" else if (cancelled) "CANCELLED" else
                    if (submitted) "EXECUTION_FAILED" else "PREFLIGHT_FAILED", reason)
            }
        }
    }

    override fun stopRecording(command: CliCommand) {
        if (current()?.id != command.id || completing || !command.spec.recording) return
        stopping = true
        if (!submitted) failed("Recording stopped before preview was ready")
        else if (started) driver.stopRecording()
    }

    override fun cancel(command: CliCommand) {
        if (command.command == "events") { driver.cancelEvents(); return }
        if (command.id != current()?.id || completing) return
        cancelled = true; stopping = true
        if (command.spec.recording && submitted) {
            if (started) driver.stopRecording() // A start callback that arrives later performs the pending stop.
        } else failed("Multi operation cancelled")
    }

    fun stopped() {
        foregroundLost = true
        failed("Multi left foreground")
    }

    private fun current() = request?.takeIf { commands.active?.id == it.id }
}

/** Ordered per-camera outcomes retain partial success independently of Android media types. */
internal class MultiResults<T>(ids: List<String>, outcomes: Map<String, Result<T>>) {
    val artifacts = ids.mapNotNull { outcomes[it]?.getOrNull() }
    val cameras = ids.map { id ->
        val outcome = outcomes[id]
        cliResult("camera_id" to id, "saved" to (outcome?.isSuccess == true),
            "error" to if (outcome?.isSuccess == true) null else outcome?.exceptionOrNull()?.message ?: "No file saved")
    }
}
