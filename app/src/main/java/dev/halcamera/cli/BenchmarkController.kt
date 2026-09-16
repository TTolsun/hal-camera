package dev.halcamera.cli

import android.net.Uri
import org.json.JSONObject
import java.io.File

/** Bridges the existing UI run path to durable command completion after report persistence. */
class BenchmarkController(private val commands: CommandCoordinator, private val driver: Driver) : CliHost {
    interface Driver {
        fun begin(camera: String): Boolean
        fun abort()
        fun busy(): Boolean
    }
    override val screen = "benchmark"
    override fun isBusy() = driver.busy()
    @Volatile private var request: CliCommand? = null

    override fun execute(command: CliCommand) {
        if (command.command != "benchmark.run") throw CliFailure("APP_NOT_FOREGROUND", "Open LIVE for this command")
        if (driver.busy()) throw CliFailure("BUSY", "Benchmark is already running")
        request = command
        if (!driver.begin(requireNotNull(command.camera))) {
            request = null
            commands.fail(command.id, "PREFLIGHT_FAILED", "The selected camera/profile cannot start under current conditions")
        }
    }

    fun started(): Boolean = request?.let { commands.state(it.id, "running") } ?: true
    override fun cancel(command: CliCommand) { driver.abort() }

    fun saveFailed(message: String) {
        val command = request ?: return
        request = null
        commands.fail(command.id, "SAVE_FAILED", message)
    }

    /** Completes the command from the persisted outcome; scalar arguments keep this package free of benchmark types. */
    fun reportSaved(runId: String, aborted: String?, hardFailure: String?, schemaVersion: Int, file: File?) {
        val command = request ?: return
        request = null
        val failure = when {
            file == null -> CliFailure("SAVE_FAILED", "Benchmark report could not be saved")
            aborted != null -> CliFailure("CANCELLED", "Benchmark aborted: $aborted")
            hardFailure != null -> CliFailure("BENCHMARK_FAILED", "Benchmark failed at $hardFailure")
            else -> null
        }
        commands.complete(command.id, JSONObject().put("run_id", runId).put("camera_id", command.camera)
            .put("cancelled", aborted != null).put("report_schema_version", schemaVersion),
            file?.let { listOf(CliArtifact(it.name, "application/json", Uri.fromFile(it))) }.orEmpty(), failure)
    }
}
