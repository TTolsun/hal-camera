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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

class CommandCoordinator private constructor(private val context: Context) : CliExecution, CliArtifacts {
    /** Files the CLI itself writes (probe and CTS reports), one directory per request, removed with the record. */
    private val artifactRoot = File(context.filesDir, "cli/artifacts")
    private val store = CommandStore(File(context.filesDir, "cli/requests")) { id -> File(artifactRoot, id).deleteRecursively() }
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val queries = CliQueries(context, this, io)
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
        if (!handover) active?.let { command ->
            val state = store.read(command.id)?.optString("state")
            if (state == "accepted" || state == "preparing") fail(command.id, "APP_NOT_FOREGROUND", "App left foreground")
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

    fun hello(): JSONObject {
        if (!enabled) return CliJson.failure("CLI_DISABLED", "Enable ADB CLI in Lab > ADB CLI")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return CliJson.envelope().put("enabled", true).put("app_version", info.versionName)
            .put("commands", JSONArray(CliCommand.COMMANDS))
            .put("command_specs", CliJson.of(CliCommands.specs.mapValues { it.value.toMap() }))
            .put("controls", JSONArray(listOf("live.set", "live.reset", "record.snapshot", "record.stop", "status", "request", "requests.list", "request.cancel")))
            .put("retention_ms", CommandStore.RETENTION_MS).put("max_completed_requests", CommandStore.MAX_RECORDS)
            .put("camera_permission", permission(Manifest.permission.CAMERA)).put("locked", locked())
            .put("completed", true)
    }

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
            timeout = Runnable { cancelWithCode(command.id, "EXECUTION_TIMEOUT") }.also { main.postDelayed(it, command.timeoutMs) }
            if (command.command in setOf("record.start", "dual.record")) main.postDelayed({
                if (active?.id == command.id && store.read(command.id)?.optJSONObject("result")?.optBoolean("recording") != true)
                    cancelWithCode(command.id, "EXECUTION_TIMEOUT")
            }, 30_000)
            try {
                if (!enabled) throw CliFailure("CLI_DISABLED", "CLI was disabled")
                if (uiBusy) throw CliFailure("BUSY", "A UI operation is running")
                if (host?.isBusy() == true) throw CliFailure("BUSY", "A UI operation is running")
                val spec = CliCommands.get(command.command)
                if (spec.cameraPermission && !permission(Manifest.permission.CAMERA)) throw CliFailure("PERMISSION_REQUIRED", "Allow camera access in the app")
                if (!state(command.id, "preparing")) return@post
                when (spec.target) {
                    CliCommandSpec.Target.QUERY -> queries.execute(command)
                    CliCommandSpec.Target.LIBRARY -> {
                        if (!state(command.id, "running")) return@post
                        io.execute {
                            try { CliLibrary(context, this, ::hello).execute(command) }
                            catch (e: Exception) { fail(command.id, (e as? CliFailure)?.code ?: "LIBRARY_FAILED", e.message ?: "Library operation failed") }
                        }
                    }
                    else -> {
                        if (locked()) throw CliFailure("DEVICE_LOCKED", "Unlock the device")
                        if (spec.savesMedia && Build.VERSION.SDK_INT <= 28 &&
                            (!permission(Manifest.permission.WRITE_EXTERNAL_STORAGE) || !permission(Manifest.permission.READ_EXTERNAL_STORAGE)))
                            throw CliFailure("PERMISSION_REQUIRED", "Allow storage access for photos on Android 8–9")
                        if (command.command == "record.start" && command.audio != false && !permission(Manifest.permission.RECORD_AUDIO))
                            throw CliFailure("PERMISSION_REQUIRED", "Allow microphone access in the app, or use audio=false")
                        if (command.command == "cts.run") {
                            val known = queries.suiteItems().map { it["key"] }
                            command.cases.orEmpty().firstOrNull { it !in known }?.let { throw CliFailure("UNKNOWN_CASE", "Unknown suite item: $it") }
                        } else if (command.command in CliCommand.CAMERA_COMMANDS) {
                            val cameraIds = context.getSystemService(CameraManager::class.java).cameraIdList
                            if (command.camera !in cameraIds) throw CliFailure("UNSUPPORTED_CAMERA", "Camera is not independently openable")
                        }
                        val screen = host ?: throw CliFailure("APP_NOT_FOREGROUND", "Open Live before executing a camera command")
                        if (screen.screen != "live" && !(screen.screen == "dual" && CliCommands.get(command.command).currentScreen)) throw CliFailure("APP_NOT_FOREGROUND", "Open Live before executing a camera command")
                        screen.execute(command)
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

    override fun recordingStarted(command: CliCommand, streams: Map<String, Any?>): Boolean = try {
        store.transition(command.id, "running") {
            it.put("result", JSONObject().put("camera_id", command.camera).put("recording", true).put("engine", command.engine ?: "Camera2").put("streams", CliJson.of(streams)))
        }
        true
    } catch (error: Exception) { fail(command.id, "STORE_FAILED", error.message ?: "Cannot persist recording state"); false }

    @Synchronized override fun complete(id: String, result: JSONObject, artifacts: List<CliArtifact>, failure: CliFailure?) {
        if (store.read(id)?.optString("state") in CliStates.terminal + "saving") return
        if (!state(id, "saving")) return
        io.execute {
            try {
                val files = JSONArray()
                artifacts.forEachIndexed { index, artifact ->
                    val hash = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    openSource(artifact.uri).use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            hash.update(buffer, 0, n); size += n
                        }
                    }
                    files.put(JSONObject().put("artifact_id", "file-$index").put("name", artifact.name)
                        .put("mime_type", artifact.mimeType).put("size_bytes", size)
                        .put("sha256", hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
                        .put("source_uri", artifact.uri.toString()))
                }
                val cancellation = cancellationCode
                val terminal = if (cancellation == "EXECUTION_TIMEOUT") "failed" else if (failure?.code == "CANCELLED") "cancelled" else if (failure != null) "failed" else if (cancellation == "CANCELLED" && result.optBoolean("cancelled")) "cancelled"
                    else "succeeded"
                store.transition(id, terminal) {
                    it.put("result", result).put("artifacts", files)
                    if (cancellation != null) it.put("cancel_effective", terminal != "succeeded")
                    val error = if (cancellation == "EXECUTION_TIMEOUT") CliFailure("EXECUTION_TIMEOUT", "App execution deadline exceeded")
                        else failure ?: cancellation?.takeIf { terminal != "succeeded" }?.let { code -> CliFailure(code, code) }
                    if (error != null) it.put("error", CliJson.error(error.code, error.message ?: error.code))
                }
                store.cleanup()
            } catch (e: Exception) { fail(id, "SAVE_FAILED", e.message ?: "Cannot register artifacts") }
            finally { main.post { release(id) } }
        }
    }

    override fun fail(id: String, code: String, message: String) {
        val actualCode = if (active?.id == id && cancellationCode == "EXECUTION_TIMEOUT") "EXECUTION_TIMEOUT" else code
        try {
            store.transition(id, if (actualCode == "CANCELLED") "cancelled" else "failed") { it.put("error", CliJson.error(actualCode, message)) }
        } catch (error: Exception) {
            store.failInMemory(id, error.message ?: message)
        }
        main.post { release(id) }
    }

    fun cancel(id: String): JSONObject {
        CliCommand.validateId(id)
        val record = store.read(id) ?: throw CliFailure("REQUEST_NOT_FOUND", "Unknown request")
        if (!record.optBoolean("completed")) main.post { cancelWithCode(id, "CANCELLED") }
        return CliJson.publicRecord(record)
    }

    /** Stop is a control of the original request, so recording's BUSY lock cannot block it. */
    fun stopRecording(id: String?): JSONObject {
        id?.let(CliCommand::validateId)
        val command = active?.takeIf { it.command in setOf("record.start", "dual.record") && (id == null || it.id == id) }
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
        val task = java.util.concurrent.FutureTask<JSONObject> {
            if (!enabled) throw CliFailure("CLI_DISABLED", "Enable ADB CLI in the app")
            if (locked()) throw CliFailure("DEVICE_LOCKED", "Unlock the device")
            if (active != null && active?.command !in setOf("record.start", "dual.record")) throw CliFailure("BUSY", "Wait for the current operation")
            if (active == null && (uiBusy || host?.isBusy() == true)) throw CliFailure("BUSY", "A UI operation is running")
            (host as? CliControlsHost ?: throw CliFailure("APP_NOT_FOREGROUND", "Start Live or Dual preview first")).tune(options, reset)
        }
        main.post(task)
        return try { task.get(5, java.util.concurrent.TimeUnit.SECONDS) }
        catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? CliFailure ?: CliFailure("EXECUTION_FAILED", e.cause?.message ?: "Control update failed")) }
        catch (e: java.util.concurrent.TimeoutException) { task.cancel(false); throw CliFailure("EXECUTION_TIMEOUT", "Control update timed out; inspect live info before retrying") }
    }

    fun snapshot(): JSONObject {
        val command = active?.takeIf { it.command == "record.start" } ?: throw CliFailure("NOT_RECORDING", "Start a CLI recording first")
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
        if (cancellationCode != "EXECUTION_TIMEOUT") cancellationCode = code
        val current = store.read(id)?.optString("state") ?: return
        if (current in CliStates.terminal) return
        if (current == "saving") return // Submitted saves complete with their actual result.
        if (current == "accepted" || current == "preparing") {
            // A screenless command never reached the host; telling Live to stop preparing would pause its preview.
            if ((command.command in CliCommand.CAMERA_COMMANDS && command.command != "streams") || command.command in setOf("preview.stop", "cts.run", "events", "meter", "live.info")) host?.cancel(command)
            fail(id, code, "Operation cancelled before capture")
        } else {
            state(id, "cancelling")
            if (CliCommands.get(command.command).target == CliCommandSpec.Target.LIBRARY) return
            host?.cancel(command) ?: fail(id, code, "Activity is no longer available")
        }
    }

    @Synchronized private fun release(id: String) {
        if (active?.id != id) return
        timeout?.let(main::removeCallbacks); timeout = null
        active = null; handover = false; cancellationCode = null
    }

    override fun readRecord(id: String): JSONObject? = store.read(id)
    override fun updateRecord(id: String, mutate: (JSONObject) -> Unit) { store.update(id, mutate) }

    /**
     * The retained requests, newest first, for a client that lost a request ID. A read rather than a submitted
     * command: it creates no record, so it never evicts one of the [CommandStore.MAX_RECORDS] it lists.
     */
    fun requests(): JSONObject {
        if (!enabled) return CliJson.failure("CLI_DISABLED", "Enable ADB CLI in the app")
        val items = store.list().mapNotNull { record -> runCatching {
            CliRequestSummary(record.getString("request_id"), record.getJSONObject("request").getString("command"),
                record.getString("state"), record.optLong("created_at_ms"), record.optJSONArray("artifacts")?.length() ?: 0,
                record.optJSONObject("error")?.optString("code"))
        }.getOrNull() }
        return CliJson.envelope().put("requests", CliJson.of(CliRequestSummary.listing(items)))
            .put("retention_ms", CommandStore.RETENTION_MS).put("max_completed_requests", CommandStore.MAX_RECORDS)
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
