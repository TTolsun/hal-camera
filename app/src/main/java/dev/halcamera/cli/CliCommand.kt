package dev.halcamera.cli

import java.util.UUID

class CliFailure(val code: String, message: String) : RuntimeException(message)

data class CliCommand(val id: String, val command: String, val camera: String?, val profile: String?, val timeoutMs: Long) {
    init {
        validateId(id)
        if (command !in setOf("cameras", "preview", "capture", "benchmark.run")) throw CliFailure("INVALID_ARGUMENT", "Unknown command")
        if (timeoutMs !in 1..3_600_000) throw CliFailure("INVALID_ARGUMENT", "Invalid execution timeout")
        if (command != "cameras" && (camera.isNullOrBlank() || camera.length > 128)) throw CliFailure("INVALID_ARGUMENT", "camera_id is required")
        if (command == "cameras" && camera != null) throw CliFailure("INVALID_ARGUMENT", "cameras takes no camera_id")
        if (command == "benchmark.run" && profile != "camera2-standard-v1") throw CliFailure("UNSUPPORTED_PROFILE", "Unsupported benchmark profile")
        if (command != "benchmark.run" && profile != null) throw CliFailure("INVALID_ARGUMENT", "Unexpected profile_id")
    }

    companion object {
        fun validateId(id: String) {
            if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id) throw CliFailure("INVALID_ARGUMENT", "Expected canonical lowercase UUID")
        }
    }
}

object CliStates {
    val terminal = setOf("succeeded", "failed", "cancelled", "interrupted")
    private val transitions = mapOf(
        "accepted" to setOf("preparing", "failed", "cancelled", "interrupted"),
        "preparing" to setOf("running", "saving", "failed", "cancelled", "cancelling", "interrupted"),
        "running" to setOf("saving", "failed", "cancelling", "cancelled", "interrupted"),
        "saving" to setOf("succeeded", "failed", "cancelled", "interrupted"),
        "cancelling" to setOf("saving", "cancelled", "failed", "interrupted")
    )
    fun allows(from: String, to: String) = from == to || to in transitions[from].orEmpty()
}
