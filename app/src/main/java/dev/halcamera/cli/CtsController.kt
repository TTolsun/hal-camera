package dev.halcamera.cli

import android.net.Uri
import java.io.File

/**
 * The CTS suite screen as a CLI host. The screen owns the queue and the run; this adapter only turns the
 * CLI request into `begin`, the finished suite into a completed request with its report files, and a cancel
 * into `stop`. Scalar arguments keep this package free of cts types.
 */
class CtsController(private val commands: CommandCoordinator, private val driver: Driver) : CliHost {
    interface Driver {
        /** Starts the queue the screen was opened with; returns a failure code, or null when the run began. */
        fun begin(): String?
        fun stop()
        fun busy(): Boolean
    }
    override val screen = "cts"
    override fun isBusy() = driver.busy()
    @Volatile private var request: CliCommand? = null

    override fun execute(command: CliCommand) {
        if (command.command != "cts.run") throw CliFailure("APP_NOT_FOREGROUND", "Open Live for this command")
        if (driver.busy()) throw CliFailure("BUSY", "A CTS run is already in progress")
        request = command
        val failure = driver.begin()
        if (failure != null) {
            request = null
            commands.fail(command.id, failure, when (failure) {
                "PERMISSION_REQUIRED" -> "Allow camera and microphone access in the app"
                else -> "The suite could not start"
            })
        }
    }

    /** True while a CLI request owns the run, so the screen knows to file the report. */
    fun driving(): Boolean = request != null

    /** The first item began: the request leaves `preparing`. */
    fun started(): Boolean = request?.let { commands.state(it.id, "running") } ?: true
    override fun cancel(command: CliCommand) { driver.stop() }

    fun saveFailed(message: String) {
        val command = request ?: return
        request = null
        commands.fail(command.id, "SAVE_FAILED", message)
    }

    /** Completes the command from the files the screen wrote; a stopped suite is a cancellation, a FAIL row is not a failure. */
    fun reportSaved(cancelled: Boolean, passed: Int, failed: Int, skipped: Int, notRun: Int, json: File, text: File) {
        val command = request ?: return
        request = null
        val result = org.json.JSONObject().put("cancelled", cancelled).put("passed", passed).put("failed", failed)
            .put("skipped", skipped).put("not_run", notRun).put("artifact_count", 2)
        val artifacts = listOf(CliArtifact(json.name, "application/json", Uri.fromFile(json)), CliArtifact(text.name, "text/plain", Uri.fromFile(text)))
        commands.complete(command.id, result, artifacts, if (cancelled) CliFailure("CANCELLED", "CTS suite stopped") else null)
    }
}
