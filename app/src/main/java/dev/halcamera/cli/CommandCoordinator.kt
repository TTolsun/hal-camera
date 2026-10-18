package dev.halcamera.cli

import androidx.core.content.edit
import androidx.core.net.toUri
import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Owns the one active request: threads, the durable store, the attached screen and its handover. What a request
 * must pass ([CliPreflight]), how its files are filed ([CliArtifactRegistry]) and how it ends ([CliOutcome]) are
 * decided by pure classes; this class applies their answers and is the JSON boundary for [CliProvider].
 */
class CommandCoordinator private constructor(private val context: Context) : CliExecution, CliArtifacts {
    /** Files the CLI itself writes (probe and CTS reports), one directory per request, removed with the record. */
    private val artifactRoot = File(context.filesDir, "cli/artifacts")
    private val store = CommandStore(File(context.filesDir, "cli/requests")) { id -> File(artifactRoot, id).deleteRecursively() }
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val queries = CliQueries(context, this, io)
    private val preflight = CliPreflight(object : CliPreflight.Device {
        override fun cameraGranted() = permission(Manifest.permission.CAMERA)
        override fun microphoneGranted() = permission(Manifest.permission.RECORD_AUDIO)
        override fun storageReady() = Build.VERSION.SDK_INT > 28 ||
            (permission(Manifest.permission.WRITE_EXTERNAL_STORAGE) && permission(Manifest.permission.READ_EXTERNAL_STORAGE))
        override fun locked() = this@CommandCoordinator.locked()
        override fun cameraIds() = context.getSystemService(CameraManager::class.java).cameraIdList.toList()
        override fun suiteKeys() = queries.suiteItems().map { it["key"].toString() }
    })
    @Volatile private var host: CliHost? = null
    @Volatile private var uiBusy = false
    @Volatile override var active: CliCommand? = null
        private set
    private var handover = false
    private var timeout: Runnable? = null
    @Volatile private var cancellationCode: String? = null
    // Default to ADB access; preserve an explicit opt-out across app updates.
    val enabled: Boolean get() = context.getSharedPreferences("cli", Context.MODE_PRIVATE).getBoolean("enabled", true)
    val busy: Boolean get() = active != null || uiBusy

    fun setEnabled(value: Boolean) {
        context.getSharedPreferences("cli", Context.MODE_PRIVATE).edit { putBoolean("enabled", value) }
        if (!value) active?.let { cancel(it.id) }
    }

    fun attach(value: CliHost) { host = value; uiBusy = value.isBusy() }
    fun setUiBusy(owner: CliHost, value: Boolean) { if (host === owner) uiBusy = value }
    fun canOpenLive(): Boolean = !busy && host?.isBusy() != true
    fun detach(value: CliHost) {
        if (host !== value) return
        host = null; uiBusy = false
        active?.let { command ->
            if (CliOutcome.abandonedOnDetach(handover, store.read(command.id)?.optString("state")))
                fail(command.id, "APP_NOT_FOREGROUND", "App left foreground")
        }
    }

    override fun beginHandover() { handover = true }
    fun continueHandover(value: CliHost) {
        attach(value)
        if (handover) {
            handover = false
            active?.let { command ->
                if (store.read(command.id)?.optBoolean("completed") == true) return@let
                try { value.execute(command) }
                catch (e: Exception) { fail(command.id, (e as? CliFailure)?.code ?: "EXECUTION_FAILED", e.message ?: "Cannot prepare screen") }
            }
        }
    }

    fun hello(): JSONObject = if (!enabled) CliJson.failure("CLI_DISABLED", "Enable ADB CLI in Lab > ADB CLI")
        else CliJson.of(capabilities()) as JSONObject

    /** The hello document as a map; `settings.show` reports it too. */
    private fun capabilities(): Map<String, Any?> = cliResult("protocol_version" to 1, "enabled" to true,
        "app_version" to context.packageManager.getPackageInfo(context.packageName, 0).versionName,
        "commands" to CliCommand.COMMANDS, "command_specs" to CliCommands.specs.mapValues { it.value.toMap() }, "stream_keys" to CliStreams.KEYS.toList(),
        "controls" to listOf("live.set", "live.reset", "record.snapshot", "record.stop", "status", "request", "request.cancel"),
        "retention_ms" to CommandStore.RETENTION_MS, "max_completed_requests" to CommandStore.MAX_RECORDS,
        "camera_permission" to permission(Manifest.permission.CAMERA), "locked" to locked(), "completed" to true)

    fun status(): JSONObject = if (!enabled) CliJson.failure("CLI_DISABLED", "Enable ADB CLI in the app") else
        CliJson.envelope().put("foreground", host != null).put("screen", host?.screen ?: JSONObject.NULL)
            .put("busy", busy).put("request_id", active?.id ?: JSONObject.NULL).put("locked", locked())

    @Synchronized fun submit(command: CliCommand): JSONObject {
        if (!enabled) throw CliFailure("CLI_DISABLED", "Enable ADB CLI in the app")
        val old = store.read(command.id)
        if (old != null) {
            if (CliJson.decode(old.getJSONObject("request")) != command) throw CliFailure("REQUEST_CONFLICT", "Request ID already has different parameters")
            return CliJson.publicRecord(old)
        }
        if (busy) throw CliFailure("BUSY", "Another operation is running")
        store.cleanup()
        val record = store.create(command)
        active = command
        cancellationCode = null
        main.post {
            if (active?.id != command.id) return@post
            val spec = command.spec
            timeout = Runnable { cancelWithCode(command.id, CliOutcome.TIMEOUT) }.also { main.postDelayed(it, command.timeoutMs) }
            if (spec.recording) main.postDelayed({
                if (active?.id == command.id && !recording(command.id)) cancelWithCode(command.id, CliOutcome.TIMEOUT)
            }, 30_000)
            try {
                if (!enabled) throw CliFailure("CLI_DISABLED", "CLI was disabled")
                preflight.beforePreparing(spec, uiBusy || host?.isBusy() == true)
                if (!state(command.id, "preparing")) return@post
                when (spec.target) {
                    CliCommandSpec.Target.QUERY -> queries.execute(command)
                    CliCommandSpec.Target.LIBRARY -> {
                        if (!state(command.id, "running")) return@post
                        io.execute {
                            try { CliLibrary(context, this, ::capabilities).execute(command) }
                            catch (e: Exception) { fail(command.id, (e as? CliFailure)?.code ?: "LIBRARY_FAILED", e.message ?: "Library operation failed") }
                        }
                    }
                    CliCommandSpec.Target.LIVE -> {
                        val screen = host
                        preflight.beforeScreen(command, spec, screen?.screen)
                        requireNotNull(screen).execute(command)
                    }
                }
            } catch (e: Exception) { fail(command.id, (e as? CliFailure)?.code ?: "EXECUTION_FAILED", e.message ?: "Execution failed") }
        }
        return CliJson.publicRecord(record)
    }

    /** Where a command that produces its own files (probe, CTS) writes them; the directory goes with the record. */
    override fun artifactDir(id: String): File = File(artifactRoot, id).apply { check(isDirectory || mkdirs()) { "Cannot create artifact directory" } }

    override fun state(id: String, value: String): Boolean = try { store.transition(id, value); true }
        catch (error: Exception) { fail(id, "STORE_FAILED", error.message ?: "Cannot persist state"); false }

    override fun recording(id: String): Boolean = store.read(id)?.optJSONObject("result")?.optBoolean("recording") == true

    override fun recordingStarted(command: CliCommand, streams: Map<String, Any?>): Boolean = try {
        store.transition(command.id, "running") {
            it.put("result", JSONObject().put("camera_id", command.camera).put("recording", true).put("engine", command.engine ?: "Camera2").put("streams", CliJson.of(streams)))
        }
        true
    } catch (error: Exception) { fail(command.id, "STORE_FAILED", error.message ?: "Cannot persist recording state"); false }

    @Synchronized override fun complete(id: String, result: Map<String, Any?>, artifacts: List<CliArtifact>, failure: CliFailure?) {
        if (store.read(id)?.optString("state") in CliStates.terminal + "saving") return
        if (!state(id, "saving")) return
        io.execute {
            try {
                val files = CliArtifactRegistry.register(artifacts.map { artifact ->
                    CliArtifactRegistry.Source(artifact.name, artifact.mimeType, artifact.uri.toString()) { openSource(artifact.uri) }
                })
                val outcome = CliOutcome.completed(cancellationCode, failure, result["cancelled"] == true)
                store.transition(id, outcome.state) {
                    it.put("result", CliJson.of(result)).put("artifacts", CliJson.of(files))
                    outcome.cancelEffective?.let { effective -> it.put("cancel_effective", effective) }
                    outcome.error?.let { error -> it.put("error", CliJson.error(error.code, error.message ?: error.code)) }
                }
                store.cleanup()
            } catch (e: Exception) { fail(id, "SAVE_FAILED", e.message ?: "Cannot register artifacts") }
            finally { main.post { release(id) } }
        }
    }

    override fun fail(id: String, code: String, message: String) {
        val actualCode = CliOutcome.failureCode(code, active?.id == id && cancellationCode == CliOutcome.TIMEOUT)
        try {
            store.transition(id, CliOutcome.failureState(actualCode)) { it.put("error", CliJson.error(actualCode, message)) }
        } catch (error: Exception) {
            store.failInMemory(id, error.message ?: message)
        }
        main.post { release(id) }
    }

    fun cancel(id: String): JSONObject {
        CliCommand.validateId(id)
        val record = store.read(id) ?: throw CliFailure("REQUEST_NOT_FOUND", "Unknown request")
        if (!record.optBoolean("completed")) main.post { cancelWithCode(id, CliOutcome.CANCELLED) }
        return CliJson.publicRecord(record)
    }

    /** Stop is a control of the original request, so recording's BUSY lock cannot block it. */
    fun stopRecording(id: String?): JSONObject {
        id?.let(CliCommand::validateId)
        val command = active?.takeIf { it.spec.recording && (id == null || it.id == id) }
            ?: throw CliFailure("NOT_RECORDING", "No matching CLI recording is active")
        main.post {
            if (active?.id != command.id) return@post
            val current = store.read(command.id)?.optString("state")
            if (current == "saving" || current in CliStates.terminal) return@post
            try {
                (host as? CliRecordingHost ?: throw CliFailure("NOT_RECORDING", "Recording screen is unavailable")).stopRecording(command)
            } catch (e: Exception) { fail(command.id, (e as? CliFailure)?.code ?: "RECORDING_FAILED", e.message ?: "Cannot stop recording") }
        }
        return request(command.id)
    }

    fun tune(options: CliOptions?, reset: Boolean): JSONObject {
        if (!reset && (options == null || options.values.keys.any { it !in CliOptions.CONTROL_KEYS })) throw CliFailure("INVALID_ARGUMENT", "live set requires camera control options")
        val task = java.util.concurrent.FutureTask {
            if (!enabled) throw CliFailure("CLI_DISABLED", "Enable ADB CLI in the app")
            if (locked()) throw CliFailure("DEVICE_LOCKED", "Unlock the device")
            if (active?.spec?.recording == false) throw CliFailure("BUSY", "Wait for the current operation")
            if (active == null && (uiBusy || host?.isBusy() == true)) throw CliFailure("BUSY", "A UI operation is running")
            (host as? CliControlsHost ?: throw CliFailure("APP_NOT_FOREGROUND", "Start Live or Dual preview first")).tune(options, reset)
        }
        main.post(task)
        val result = try { task.get(5, java.util.concurrent.TimeUnit.SECONDS) }
        catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? CliFailure ?: CliFailure("EXECUTION_FAILED", e.cause?.message ?: "Control update failed")) }
        catch (e: java.util.concurrent.TimeoutException) { task.cancel(false); throw CliFailure("EXECUTION_TIMEOUT", "Control update timed out; inspect live info before retrying") }
        return CliJson.envelope().apply { result.forEach { (key, value) -> put(key, CliJson.of(value)) } }
    }

    fun snapshot(): JSONObject {
        val command = active?.takeIf { it.spec.snapshots } ?: throw CliFailure("NOT_RECORDING", "Start a CLI recording first")
        main.post {
            if (active?.id != command.id) return@post
            try { (host as? CliSnapshotHost)?.snapshot(command) ?: throw CliFailure("APP_NOT_FOREGROUND", "Recording screen unavailable") }
            catch (e: Exception) { runCatching { store.update(command.id) {
                it.put("snapshot_error", e.message ?: "Snapshot failed")
            } } }
        }
        return request(command.id)
    }

    private fun cancelWithCode(id: String, code: String) {
        val command = active?.takeIf { it.id == id } ?: return
        cancellationCode = CliOutcome.cancellation(cancellationCode, code)
        when (CliOutcome.cancelStep(store.read(id)?.optString("state"), command.spec)) {
            CliOutcome.CancelStep.IGNORE -> Unit
            CliOutcome.CancelStep.FAIL -> fail(id, code, "Operation cancelled before capture")
            CliOutcome.CancelStep.STOP_SCREEN_AND_FAIL -> { host?.cancel(command); fail(id, code, "Operation cancelled before capture") }
            CliOutcome.CancelStep.MARK_CANCELLING -> state(id, "cancelling")
            CliOutcome.CancelStep.MARK_CANCELLING_AND_STOP_SCREEN -> {
                state(id, "cancelling")
                host?.cancel(command) ?: fail(id, code, "Activity is no longer available")
            }
        }
    }

    @Synchronized private fun release(id: String) {
        if (active?.id != id) return
        timeout?.let(main::removeCallbacks); timeout = null
        active = null; handover = false; cancellationCode = null
    }

    /** The stored record, for screens that show CLI progress. */
    fun readRecord(id: String): JSONObject? = store.read(id)
    override fun updateRecord(id: String, fields: Map<String, Any?>) {
        store.update(id) { record -> fields.forEach { (key, value) -> if (value == null) record.remove(key) else record.put(key, CliJson.of(value)) } }
    }

    fun request(id: String): JSONObject = CliJson.publicRecord(store.read(id) ?: throw CliFailure("REQUEST_NOT_FOUND", "Unknown or expired request"))
    fun artifact(id: String, aid: String): Uri {
        if (busy) throw CliFailure("BUSY", "Wait for the active operation before downloading")
        val record = store.read(id) ?: throw CliFailure("ARTIFACT_EXPIRED", "Unknown or expired request")
        if (!record.optBoolean("completed")) throw CliFailure("BUSY", "Request is not complete")
        if (record.optLong("expires_at_ms", Long.MAX_VALUE) <= System.currentTimeMillis()) throw CliFailure("ARTIFACT_EXPIRED", "Artifact registration expired")
        val files = record.getJSONArray("artifacts")
        for (i in 0 until files.length()) files.getJSONObject(i).let {
            if (it.getString("artifact_id") == aid) return it.getString("source_uri").toUri()
        }
        throw CliFailure("ARTIFACT_MISSING", "Unknown artifact")
    }

    fun openSource(uri: Uri): java.io.InputStream = if (uri.scheme == "file") File(requireNotNull(uri.path)).inputStream()
        else context.contentResolver.openInputStream(uri) ?: throw CliFailure("ARTIFACT_MISSING", "Media was removed")
    private fun permission(name: String) = ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
    private fun locked() = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    companion object {
        // get() passes applicationContext exclusively; this process singleton never retains an Activity context.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: CommandCoordinator? = null
        fun get(context: Context): CommandCoordinator = instance ?: synchronized(this) {
            instance ?: CommandCoordinator(context.applicationContext).also { instance = it }
        }
    }
}
