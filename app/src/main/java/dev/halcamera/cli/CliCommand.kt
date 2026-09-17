package dev.halcamera.cli

import java.util.UUID

class CliFailure(val code: String, message: String) : RuntimeException(message)

/**
 * One normalized request. [camera] and [profile] belong to the camera commands, [cases] to `cts.run`; the
 * other fields stay null so a request that carries a parameter its command does not take is rejected here,
 * before anything is stored.
 */
data class CliCommand(
    val id: String,
    val command: String,
    val camera: String?,
    val profile: String?,
    val timeoutMs: Long,
    val cases: List<String>? = null
) {
    init {
        validateId(id)
        if (command !in COMMANDS) throw CliFailure("INVALID_ARGUMENT", "Unknown command")
        if (timeoutMs !in 1..3_600_000) throw CliFailure("INVALID_ARGUMENT", "Invalid execution timeout")
        if (command in CAMERA_COMMANDS && (camera.isNullOrBlank() || camera.length > 128)) throw CliFailure("INVALID_ARGUMENT", "camera_id is required")
        if (command !in CAMERA_COMMANDS && camera != null) throw CliFailure("INVALID_ARGUMENT", "$command takes no camera_id")
        if (command == "benchmark.run" && profile != "camera2-standard-v1") throw CliFailure("UNSUPPORTED_PROFILE", "Unsupported benchmark profile")
        if (command != "benchmark.run" && profile != null) throw CliFailure("INVALID_ARGUMENT", "Unexpected profile_id")
        if (command == "cts.run") {
            if (cases.isNullOrEmpty()) throw CliFailure("INVALID_ARGUMENT", "cases must name at least one suite item")
            if (cases.size > MAX_CASES) throw CliFailure("INVALID_ARGUMENT", "cases lists more than $MAX_CASES items")
            if (cases.any { it.length !in 1..256 || !it.startsWith(VENDORED_PREFIX) })
                throw CliFailure("INVALID_ARGUMENT", "Each case is a suite key starting with $VENDORED_PREFIX")
            if (cases.toSet().size != cases.size) throw CliFailure("INVALID_ARGUMENT", "cases repeats an item")
        } else if (cases != null) throw CliFailure("INVALID_ARGUMENT", "Unexpected cases")
    }

    companion object {
        val COMMANDS = listOf("cameras", "preview", "capture", "benchmark.run", "probe", "cts.cases", "cts.run")
        val CAMERA_COMMANDS = setOf("preview", "capture", "benchmark.run")
        const val MAX_CASES = 64
        /** The suite key prefix of [dev.halcamera.cts.suite.SuiteItem], repeated so this file stays free of cts types. */
        const val VENDORED_PREFIX = "vendored:"

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
