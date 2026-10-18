package dev.halcamera.cli

import android.net.Uri
import java.io.File

/**
 * Screens implement only the capabilities they actually support. Results and record fields travel as plain
 * maps; only [CommandCoordinator] turns them into the protocol's JSON.
 */
interface CliHost {
    val screen: String
    fun isBusy(): Boolean = false
    fun execute(command: CliCommand)
    fun cancel(command: CliCommand)
}

interface CliControlsHost { fun tune(options: CliOptions?, reset: Boolean): Map<String, Any?> }
interface CliRecordingHost { fun stopRecording(command: CliCommand) }
interface CliSnapshotHost { fun snapshot(command: CliCommand) }
/** A preview screen must release its cameras before the launcher may bring Live forward. */
interface CliNavigationHost { fun closeForNavigation(done: () -> Unit) }

data class CliArtifact(val name: String, val mimeType: String, val uri: Uri)

/**
 * A result map that leaves out null values, as the JSON results always have: a field the screen could not
 * fill is absent rather than `null`.
 */
fun cliResult(vararg fields: Pair<String, Any?>): Map<String, Any?> = fields.filter { it.second != null }.toMap()

/** Outcome reporting does not expose persistence or depend on the concrete coordinator. */
interface CliCompletion {
    fun state(id: String, value: String): Boolean
    fun fail(id: String, code: String, message: String)
    fun complete(id: String, result: Map<String, Any?>, artifacts: List<CliArtifact> = emptyList(), failure: CliFailure? = null)
}

interface CliArtifacts : CliCompletion { fun artifactDir(id: String): File }

interface CliExecution : CliCompletion {
    val active: CliCommand?
    fun beginHandover()
    fun recordingStarted(command: CliCommand, streams: Map<String, Any?>): Boolean
    /** True once [recordingStarted] filed the running recording for [id]. */
    fun recording(id: String): Boolean
    /** Merges [fields] into the stored record; a null value removes that field. */
    fun updateRecord(id: String, fields: Map<String, Any?>)
}
