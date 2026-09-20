package dev.halcamera.cli

import java.util.UUID

/** Human-facing `content call` arguments, independent of Android and shell quoting. */
object AdbArguments {
    fun command(method: String, values: Map<String, Any?>): CliCommand {
        val command = if (method == "preview.start") "preview" else method
        val allowed = mutableSetOf("request_id", "timeout_ms")
        if (command in CliCommand.CAMERA_COMMANDS) allowed += "camera"
        if (command == "record.start") allowed += "audio"
        if (command == "cts.run") allowed += "cases"
        if (values.keys.any { it !in allowed }) throw CliFailure("INVALID_ARGUMENT", "Unexpected argument for $method")
        fun string(key: String): String? = if (!values.containsKey(key)) null else
            values[key] as? String ?: throw CliFailure("INVALID_ARGUMENT", "$key must be a string")
        val timeout = values["timeout_ms"]
        if (values.containsKey("timeout_ms") && timeout !is Int && timeout !is Long)
            throw CliFailure("INVALID_ARGUMENT", "timeout_ms must be an integer")
        val audio = values["audio"]
        if (values.containsKey("audio") && audio !is Boolean) throw CliFailure("INVALID_ARGUMENT", "audio must be a boolean")
        return CliCommand(string("request_id") ?: UUID.randomUUID().toString(), command,
            if (command in CliCommand.CAMERA_COMMANDS) string("camera") ?: "0" else null,
            null, (timeout as? Number)?.toLong() ?: when (command) {
                "record.start" -> 3_600_000L
                "cts.run" -> 1_800_000L
                else -> 30_000L
            }, string("cases")?.split(","), audio as? Boolean)
    }
}
