package dev.halcamera.cli

import org.json.JSONArray
import org.json.JSONObject

object CliJson {
    fun envelope() = JSONObject().put("protocol_version", 1)
    fun error(code: String, message: String) = JSONObject().put("code", code).put("message", message)
    fun failure(code: String, message: String) = envelope().put("error", error(code, message)).put("completed", false)

    private fun exactKeys(json: JSONObject, allowed: Set<String>) {
        if (json.keys().asSequence().any { it !in allowed }) throw CliFailure("INVALID_ARGUMENT", "Unknown request field")
    }

    fun decode(json: JSONObject): CliCommand {
        exactKeys(json, setOf("protocol_version", "request_id", "command", "params", "execution_timeout_ms"))
        if (json.opt("protocol_version") != 1) throw CliFailure("PROTOCOL_MISMATCH", "Expected protocol version 1")
        val params = json.optJSONObject("params") ?: throw CliFailure("INVALID_ARGUMENT", "params object required")
        exactKeys(params, setOf("camera_id", "profile_id", "cases"))
        fun string(obj: JSONObject, key: String, optional: Boolean = false): String? {
            if (optional && !obj.has(key)) return null
            return obj.opt(key) as? String ?: throw CliFailure("INVALID_ARGUMENT", "$key must be a string")
        }
        val cases = if (!params.has("cases")) null else {
            val array = params.opt("cases") as? JSONArray ?: throw CliFailure("INVALID_ARGUMENT", "cases must be an array of strings")
            List(array.length()) { array.opt(it) as? String ?: throw CliFailure("INVALID_ARGUMENT", "cases must be an array of strings") }
        }
        val timeout = json.opt("execution_timeout_ms")
        if (timeout !is Int && timeout !is Long) throw CliFailure("INVALID_ARGUMENT", "execution_timeout_ms must be an integer")
        return CliCommand(string(json, "request_id")!!, string(json, "command")!!,
            string(params, "camera_id", true), string(params, "profile_id", true), (timeout as Number).toLong(), cases)
    }

    fun encode(command: CliCommand) = envelope().put("request_id", command.id).put("command", command.command)
        .put("execution_timeout_ms", command.timeoutMs).put("params", JSONObject().apply {
            command.camera?.let { put("camera_id", it) }; command.profile?.let { put("profile_id", it) }
            command.cases?.let { put("cases", JSONArray(it)) }
        })

    /** A pure-Kotlin map (the app's internal data contract) as org.json, for the file boundary. */
    fun of(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { obj -> value.forEach { (k, v) -> obj.put(k.toString(), of(v)) } }
        is List<*> -> JSONArray().also { arr -> value.forEach { arr.put(of(it)) } }
        else -> value
    }

    fun publicRecord(record: JSONObject): JSONObject = JSONObject(record.toString()).apply {
        remove("request")
        val artifacts = optJSONArray("artifacts") ?: return@apply
        for (i in 0 until artifacts.length()) artifacts.getJSONObject(i).remove("source_uri")
    }
}
