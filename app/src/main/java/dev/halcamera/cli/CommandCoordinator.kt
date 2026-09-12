package dev.halcamera.cli

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
import dev.halcamera.camera.CameraEndpointResolver
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Activity-owned adapters execute on main. Durable state and artifacts outlive Activity instances. */
interface CliHost {
    val screen: String
    fun isBusy(): Boolean = false
    fun execute(command: CliCommand)
    fun cancel(command: CliCommand)
}

data class CliArtifact(val name: String, val mimeType: String, val uri: Uri)

class CommandCoordinator private constructor(private val context: Context) {
    val store = CommandStore(File(context.filesDir, "cli/requests"))
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var host: CliHost? = null
    @Volatile private var uiBusy = false
    @Volatile var active: CliCommand? = null
        private set
    private var handover = false
    private var timeout: Runnable? = null
    @Volatile private var cancellationCode: String? = null
    val enabled: Boolean get() = context.getSharedPreferences("cli", Context.MODE_PRIVATE).getBoolean("enabled", false)
    val busy: Boolean get() = active != null || uiBusy

    fun setEnabled(value: Boolean) {
        context.getSharedPreferences("cli", Context.MODE_PRIVATE).edit().putBoolean("enabled", value).apply()
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

    fun beginHandover() { handover = true }
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
        if (!enabled) return CliJson.failure("CLI_DISABLED", "Enable ADB CLI in the app's Benchmark panel")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return CliJson.envelope().put("enabled", true).put("app_version", info.versionName)
            .put("commands", JSONArray(listOf("cameras", "preview", "capture", "benchmark.run")))
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
            try {
                if (!enabled) throw CliFailure("CLI_DISABLED", "CLI was disabled")
                if (uiBusy) throw CliFailure("BUSY", "A UI operation is running")
                if (host?.isBusy() == true) throw CliFailure("BUSY", "A UI operation is running")
                if (!permission(Manifest.permission.CAMERA)) throw CliFailure("PERMISSION_REQUIRED", "Allow camera access in the app")
                if (!state(command.id, "preparing")) return@post
                if (command.command == "cameras") {
                    val endpoints = CameraEndpointResolver(context.getSystemService(CameraManager::class.java)).resolve()
                    val cameras = JSONArray(endpoints.map { JSONObject(it.toJsonMap()).put("selectable", it.independentlyOpenable && it.physicalCameraId == null) })
                    complete(command.id, JSONObject().put("cameras", cameras))
                } else {
                    if (locked()) throw CliFailure("DEVICE_LOCKED", "Unlock the device")
                    if (command.command == "capture" && Build.VERSION.SDK_INT <= 28 &&
                        (!permission(Manifest.permission.WRITE_EXTERNAL_STORAGE) || !permission(Manifest.permission.READ_EXTERNAL_STORAGE)))
                        throw CliFailure("PERMISSION_REQUIRED", "Allow storage access for photos on Android 8–9")
                    val cameraIds = context.getSystemService(CameraManager::class.java).cameraIdList
                    if (command.camera !in cameraIds) throw CliFailure("UNSUPPORTED_CAMERA", "Camera is not independently openable")
                    (host ?: throw CliFailure("APP_NOT_FOREGROUND", "Open the app before executing a camera command")).execute(command)
                }
            } catch (e: Exception) { fail(command.id, (e as? CliFailure)?.code ?: "EXECUTION_FAILED", e.message ?: "Execution failed") }
        }
        return CliJson.publicRecord(record)
    }

    fun state(id: String, value: String): Boolean = try { store.transition(id, value); true }
        catch (error: Exception) { fail(id, "STORE_FAILED", error.message ?: "Cannot persist state"); false }

    fun complete(id: String, result: JSONObject, artifacts: List<CliArtifact> = emptyList(), failure: CliFailure? = null) {
        if (store.read(id)?.optString("state") in CliStates.terminal) return
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

    fun fail(id: String, code: String, message: String) {
        try {
            store.transition(id, if (code == "CANCELLED") "cancelled" else "failed") { it.put("error", CliJson.error(code, message)) }
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

    private fun cancelWithCode(id: String, code: String) {
        val command = active?.takeIf { it.id == id } ?: return
        if (cancellationCode != "EXECUTION_TIMEOUT") cancellationCode = code
        val current = store.read(id)?.optString("state") ?: return
        if (current in CliStates.terminal) return
        if (current == "saving") return // Submitted saves complete with their actual result.
        if (current == "accepted" || current == "preparing") {
            host?.cancel(command)
            fail(id, code, "Operation cancelled before capture")
        } else {
            state(id, "cancelling")
            host?.cancel(command) ?: fail(id, code, "Activity is no longer available")
        }
    }

    @Synchronized private fun release(id: String) {
        if (active?.id != id) return
        timeout?.let(main::removeCallbacks); timeout = null
        active = null; handover = false; cancellationCode = null
    }

    fun request(id: String): JSONObject = CliJson.publicRecord(store.read(id) ?: throw CliFailure("REQUEST_NOT_FOUND", "Unknown or expired request"))
    fun artifact(id: String, aid: String): Uri {
        if (busy) throw CliFailure("BUSY", "Wait for the active operation before downloading")
        val record = store.read(id) ?: throw CliFailure("ARTIFACT_EXPIRED", "Unknown or expired request")
        if (!record.optBoolean("completed")) throw CliFailure("BUSY", "Request is not complete")
        if (record.optLong("expires_at_ms", Long.MAX_VALUE) <= System.currentTimeMillis()) throw CliFailure("ARTIFACT_EXPIRED", "Artifact registration expired")
        val files = record.getJSONArray("artifacts")
        for (i in 0 until files.length()) files.getJSONObject(i).let {
            if (it.getString("artifact_id") == aid) return Uri.parse(it.getString("source_uri"))
        }
        throw CliFailure("ARTIFACT_MISSING", "Unknown artifact")
    }

    fun openSource(uri: Uri): java.io.InputStream = if (uri.scheme == "file") File(requireNotNull(uri.path)).inputStream()
        else context.contentResolver.openInputStream(uri) ?: throw CliFailure("ARTIFACT_MISSING", "Media was removed")
    private fun permission(name: String) = ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
    private fun locked() = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    companion object {
        @Volatile private var instance: CommandCoordinator? = null
        fun get(context: Context): CommandCoordinator = instance ?: synchronized(this) {
            instance ?: CommandCoordinator(context.applicationContext).also { instance = it }
        }
    }
}
