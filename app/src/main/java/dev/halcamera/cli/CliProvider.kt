package dev.halcamera.cli

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors

/** Shell-only transport. Camera work is never performed on a Binder thread. */
class CliProvider : ContentProvider() {
    private val writer = Executors.newFixedThreadPool(2)
    private val commands get() = CommandCoordinator.get(requireNotNull(context))
    override fun onCreate() = true

    private fun authorize() {
        if (Binder.getCallingUid() != 2000) throw SecurityException("ADB shell required")
        requireNotNull(context).enforceCallingPermission("android.permission.DUMP", "ADB shell required")
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        authorize()
        val result = json {
            if (!commands.enabled) throw CliFailure("CLI_DISABLED", "Enable ADB CLI in the app")
            require(extras == null || extras.isEmpty) { "Unexpected extras" }
            require(arg != null && arg.length <= 12000 && arg.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid request encoding" }
            val decoded = Base64.decode(arg, Base64.URL_SAFE or Base64.NO_WRAP)
            require(decoded.size <= 8192) { "Request too large" }
            val request = JSONObject(decoded.toString(Charsets.UTF_8))
            when (method) {
                "submit" -> commands.submit(CliJson.decode(request))
                "cancel" -> {
                    require(request.keys().asSequence().toSet() == setOf("protocol_version", "request_id")) { "Invalid cancellation" }
                    if (request.opt("protocol_version") != 1) throw CliFailure("PROTOCOL_MISMATCH", "Expected protocol version 1")
                    commands.cancel(request.getString("request_id"))
                }
                else -> throw CliFailure("INVALID_ARGUMENT", "Unknown method")
            }
        }
        return Bundle().apply { putString("halcam_v1", Base64.encodeToString(result.toString().toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)) }
    }

    private fun json(action: () -> JSONObject): JSONObject = try { action() }
        catch (e: Exception) { CliJson.failure((e as? CliFailure)?.code ?: "INVALID_ARGUMENT", e.message ?: "Invalid request") }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        authorize()
        if (mode != "r" || uri.query != null || uri.fragment != null) throw FileNotFoundException("Read only canonical URI required")
        val parts = uri.pathSegments
        if (parts.size == 5 && parts[0] == "v1" && parts[1] == "requests" && parts[3] == "artifacts") {
            if (!commands.enabled) throw SecurityException("CLI_DISABLED")
            val source = commands.artifact(parts[2], parts[4])
            val identity = Binder.clearCallingIdentity()
            try {
                return if (source.scheme == "file") ParcelFileDescriptor.open(File(requireNotNull(source.path)), ParcelFileDescriptor.MODE_READ_ONLY)
                else requireNotNull(context).contentResolver.openFileDescriptor(source, "r") ?: throw FileNotFoundException("ARTIFACT_MISSING")
            } finally { Binder.restoreCallingIdentity(identity) }
        }
        val bytes = json {
            if (!commands.enabled) return@json commands.hello()
            when {
                parts == listOf("v1", "hello") -> commands.hello()
                parts == listOf("v1", "status") -> commands.status()
                parts.size == 3 && parts[0] == "v1" && parts[1] == "requests" -> commands.request(parts[2])
                else -> throw CliFailure("INVALID_ARGUMENT", "Unknown URI")
            }
        }.toString().toByteArray(Charsets.UTF_8)
        val pipe = ParcelFileDescriptor.createReliablePipe()
        writer.execute {
            try { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) } }
            catch (_: Exception) { runCatching { pipe[1].close() } }
        }
        return pipe[0]
    }

    override fun getType(uri: Uri): String { authorize(); return "application/octet-stream" }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? { authorize(); throw UnsupportedOperationException() }
    override fun insert(uri: Uri, values: ContentValues?): Uri? { authorize(); throw UnsupportedOperationException() }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int { authorize(); throw UnsupportedOperationException() }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { authorize(); throw UnsupportedOperationException() }
}
