package dev.halcamera.benchmark

import android.net.Uri
import dev.halcamera.cli.CliArtifact
import dev.halcamera.cli.CliCommand
import dev.halcamera.cli.CliFailure
import dev.halcamera.cli.CliHost
import dev.halcamera.cli.CommandCoordinator
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

    fun started() { request?.let { commands.state(it.id, "running") } }
    override fun cancel(command: CliCommand) { driver.abort() }

    fun reportSaved(run: BenchmarkRun, result: BenchmarkRunner.Result, file: File?) {
        val command = request ?: return
        request = null
        val failure = when {
            file == null -> CliFailure("SAVE_FAILED", "Benchmark report could not be saved")
            result.aborted != null -> CliFailure("CANCELLED", "Benchmark aborted: ${result.aborted}")
            result.hardFailure != null -> CliFailure("BENCHMARK_FAILED", "Benchmark failed at ${result.hardFailure}")
            else -> null
        }
        commands.complete(command.id, JSONObject().put("run_id", run.runId).put("camera_id", command.camera)
            .put("cancelled", result.aborted != null).put("report_schema_version", BenchmarkReportCodec.SCHEMA_VERSION),
            file?.let { listOf(CliArtifact(it.name, "application/json", Uri.fromFile(it))) }.orEmpty(), failure)
    }
}
