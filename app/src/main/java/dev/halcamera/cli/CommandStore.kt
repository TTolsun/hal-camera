package dev.halcamera.cli

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Durable records are written before execution. Never replay unfinished work after process death. */
class CommandStore(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    init {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create command store" }
        directory.listFiles()?.filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
            ?.map { it.name.removeSuffix(".bak").removeSuffix(".json") }?.distinct()?.forEach { id ->
            runCatching {
                val record = read(id) ?: return@runCatching
                if (record.getString("state") !in CliStates.terminal) {
                    record.put("state", "interrupted").put("completed", true)
                        .put("error", CliJson.error("INTERRUPTED", "App process stopped; execution was not replayed"))
                        .put("expires_at_ms", now() + RETENTION_MS)
                    write(record)
                }
            }
        }
    }

    @Synchronized fun read(id: String): JSONObject? {
        CliCommand.validateId(id)
        val file = File(directory, "$id.json")
        if (!file.exists() && !File(directory, "$id.json.bak").exists()) return null
        return try { JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() }) }
        catch (e: Exception) { throw CliFailure("STORE_FAILED", "Unreadable request record; do not replay $id") }
    }

    @Synchronized fun write(record: JSONObject) {
        val id = record.getString("request_id")
        CliCommand.validateId(id)
        val atomic = AtomicFile(File(directory, "$id.json"))
        val stream = atomic.startWrite()
        try { stream.write(record.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw CliFailure("STORE_FAILED", "Cannot persist request") }
    }

    @Synchronized fun create(command: CliCommand): JSONObject = CliJson.envelope().apply {
        put("request_id", command.id)
        put("request", CliJson.encode(command))
        put("state", "accepted"); put("completed", false)
        put("created_at_ms", now()); put("expires_at_ms", JSONObject.NULL)
        put("result", JSONObject.NULL); put("error", JSONObject.NULL); put("artifacts", JSONArray())
    }.also(::write)

    @Synchronized fun transition(id: String, state: String, mutate: (JSONObject) -> Unit = {}): JSONObject {
        val record = read(id) ?: throw CliFailure("REQUEST_NOT_FOUND", "Unknown request")
        val old = record.getString("state")
        if (old in CliStates.terminal) return record
        check(CliStates.allows(old, state)) { "Invalid CLI transition $old -> $state" }
        record.put("state", state).put("completed", state in CliStates.terminal)
        if (state in CliStates.terminal) record.put("expires_at_ms", now() + RETENTION_MS)
        mutate(record)
        write(record)
        return record
    }

    @Synchronized fun cleanup() {
        val records = directory.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
            runCatching { read(file.nameWithoutExtension) }.getOrNull()?.takeIf { it.optBoolean("completed") }
        }.sortedByDescending { it.optLong("created_at_ms") }
        records.forEachIndexed { index, record ->
            if (index >= MAX_RECORDS || record.optLong("expires_at_ms", Long.MAX_VALUE) <= now()) {
                AtomicFile(File(directory, "${record.getString("request_id")}.json")).delete()
            }
        }
    }

    companion object { const val RETENTION_MS = 86_400_000L; const val MAX_RECORDS = 200 }
}
