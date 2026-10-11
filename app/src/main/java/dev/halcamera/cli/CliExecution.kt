package dev.halcamera.cli

import android.net.Uri
import org.json.JSONObject
import java.io.File

/** Screens implement only the capabilities they actually support. */
interface CliHost {
    val screen: String
    fun isBusy(): Boolean = false
    fun execute(command: CliCommand)
    fun cancel(command: CliCommand)
}

interface CliControlsHost { fun tune(options: CliOptions?, reset: Boolean): JSONObject }
interface CliRecordingHost { fun stopRecording(command: CliCommand) }
interface CliSnapshotHost { fun snapshot(command: CliCommand) }

data class CliArtifact(val name: String, val mimeType: String, val uri: Uri)

/** Outcome reporting does not expose persistence or depend on the concrete coordinator. */
interface CliCompletion {
    fun state(id: String, value: String): Boolean
    fun fail(id: String, code: String, message: String)
    fun complete(id: String, result: JSONObject, artifacts: List<CliArtifact> = emptyList(), failure: CliFailure? = null)
}

interface CliArtifacts : CliCompletion { fun artifactDir(id: String): File }

interface CliExecution : CliCompletion {
    val active: CliCommand?
    fun beginHandover()
    fun recordingStarted(command: CliCommand, streams: Map<String, Any?>): Boolean
    fun readRecord(id: String): JSONObject?
    fun updateRecord(id: String, mutate: (JSONObject) -> Unit)
}
