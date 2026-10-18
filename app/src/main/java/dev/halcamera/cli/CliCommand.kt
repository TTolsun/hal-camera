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
    val cases: List<String>? = null,
    val audio: Boolean? = null,
    val engine: String? = null,
    val streams: CliStreams? = null,
    val options: CliOptions? = null,
    val mode: String? = null
) {
    val spec: CliCommandSpec get() = CliCommands.get(command)
    val videoMode get() = spec.recording || mode == "Video"
    init {
        validateId(id)
        val spec = CliCommands.get(command)
        if (mode != null && (!spec.acceptsMode || mode !in setOf("Photo", "Video")))
            throw CliFailure("INVALID_ARGUMENT", "--mode Photo|Video is only for preview or streams")
        if (options?.values?.keys?.any { it !in CliOptions.allowed(command) } == true)
            throw CliFailure("INVALID_ARGUMENT", "Unexpected option for $command")
        val required = spec.requiredOptions
        if (!options?.values.orEmpty().keys.containsAll(required)) throw CliFailure("INVALID_ARGUMENT", "$command requires ${required.joinToString()}")
        if (spec.flow == CliCommandSpec.Flow.MULTI) CliMultiOptions.validate(options!!.values)
        if (spec.confirmRequired && options?.values?.get("confirm") != "true")
            throw CliFailure("CONFIRM_REQUIRED", "Inspect the listed ID, then add --confirm true to delete that item")
        if (timeoutMs !in 1..3_600_000) throw CliFailure("INVALID_ARGUMENT", "Invalid execution timeout")
        if (spec.camera && (camera.isNullOrBlank() || camera.length > 128)) throw CliFailure("INVALID_ARGUMENT", "camera_id is required")
        if (!spec.camera && camera != null) throw CliFailure("INVALID_ARGUMENT", "$command takes no camera_id")
        if (engine != null && (engine !in setOf("Camera2", "CameraX") || !spec.engine))
            throw CliFailure("INVALID_ARGUMENT", "engine requires a camera command and must be Camera2 or CameraX")
        if (streams != null && !spec.streams)
            throw CliFailure("INVALID_ARGUMENT", "Stream overrides require preview, capture or record.start")
        if (spec.stills && streams?.values?.get("yuv_size") == "off" && streams.values["jpeg_size"] == "off" && streams.values["raw_size"].let { it == null || it == "off" })
            throw CliFailure("INVALID_ARGUMENT", "Capture requires JPEG (HAL or YUV) or RAW output")
        if (spec.profile) {
            if (profile != BENCHMARK_PROFILE) throw CliFailure("INVALID_ARGUMENT", "profile_id must be $BENCHMARK_PROFILE")
        } else if (profile != null) throw CliFailure("INVALID_ARGUMENT", "Unexpected profile_id")
        if (!spec.audio && audio != null) throw CliFailure("INVALID_ARGUMENT", "Unexpected audio")
        if (spec.silentOnly && audio == true) throw CliFailure("INVALID_ARGUMENT", "Dual video supports silent recording only")
        if (spec.cases) {
            if (cases.isNullOrEmpty()) throw CliFailure("INVALID_ARGUMENT", "cases must name at least one suite item")
            if (cases.size > MAX_CASES) throw CliFailure("INVALID_ARGUMENT", "cases lists more than $MAX_CASES items")
            if (cases.any { it.length !in 1..256 || (!it.startsWith(CUSTOM_PREFIX) && !it.startsWith(VENDORED_PREFIX)) })
                throw CliFailure("INVALID_ARGUMENT", "Each case is a suite key starting with $CUSTOM_PREFIX or $VENDORED_PREFIX")
            if (cases.toSet().size != cases.size) throw CliFailure("INVALID_ARGUMENT", "cases repeats an item")
        } else if (cases != null) throw CliFailure("INVALID_ARGUMENT", "Unexpected cases")
    }

    companion object {
        val COMMANDS get() = CliCommands.specs.keys.toList()
        val STREAM_COMMANDS get() = CliCommands.specs.values.filter { it.streams }.map { it.name }.toSet()
        val CAMERA_COMMANDS get() = CliCommands.specs.values.filter { it.camera }.map { it.name }.toSet()
        const val BENCHMARK_PROFILE = "camera2-standard-v2"
        const val MAX_CASES = 64
        /** The suite key prefixes of [dev.halcamera.cts.suite.SuiteItem], repeated so this file stays free of cts types. */
        const val CUSTOM_PREFIX = "custom:"
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
