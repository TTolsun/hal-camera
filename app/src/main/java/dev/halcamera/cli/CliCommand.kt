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
    val options: CliOptions? = null
) {
    init {
        validateId(id)
        if (options?.values?.keys?.any { it !in CliOptions.allowed(command) } == true)
            throw CliFailure("INVALID_ARGUMENT", "Unexpected option for $command")
        val required = when (command) {
            "settings.limit" -> setOf("limit")
            "incidents.export", "incidents.delete" -> setOf("incident")
            "results.show", "results.export", "results.delete", "baseline.add", "baseline.remove" -> setOf("run")
            "results.compare" -> setOf("run", "reference")
            "gallery.export", "gallery.delete" -> setOf("media")
            "meter" -> setOf("x", "y")
            "dual.preview", "dual.capture", "dual.record" -> setOf("first", "second")
            else -> emptySet()
        }
        if (!options?.values.orEmpty().keys.containsAll(required)) throw CliFailure("INVALID_ARGUMENT", "$command requires ${required.joinToString()}")
        if (command.endsWith(".delete") && options?.values?.get("confirm") != "true")
            throw CliFailure("CONFIRM_REQUIRED", "Inspect the listed ID, then add --confirm true to delete that item")
        if (command !in COMMANDS) throw CliFailure("INVALID_ARGUMENT", "Unknown command")
        if (timeoutMs !in 1..3_600_000) throw CliFailure("INVALID_ARGUMENT", "Invalid execution timeout")
        if (command in CAMERA_COMMANDS && (camera.isNullOrBlank() || camera.length > 128)) throw CliFailure("INVALID_ARGUMENT", "camera_id is required")
        if (command !in CAMERA_COMMANDS && camera != null) throw CliFailure("INVALID_ARGUMENT", "$command takes no camera_id")
        if (engine != null && (engine !in setOf("Camera2", "CameraX") || command !in LIVE_CAMERA_COMMANDS))
            throw CliFailure("INVALID_ARGUMENT", "engine requires a camera command and must be Camera2 or CameraX")
        if (streams != null && command !in STREAM_COMMANDS)
            throw CliFailure("INVALID_ARGUMENT", "Stream overrides require preview, capture or record.start")
        if (command in setOf("capture", "burst", "bracket") && streams?.values?.get("yuv_size") == "off" && streams.values["jpeg_size"] == "off" && streams.values["raw_size"].let { it == null || it == "off" })
            throw CliFailure("INVALID_ARGUMENT", "Capture requires YUV or JPEG output")
        if (command == "benchmark.run") {
            if (profile != BENCHMARK_PROFILE) throw CliFailure("INVALID_ARGUMENT", "profile_id must be $BENCHMARK_PROFILE")
        } else if (profile != null) throw CliFailure("INVALID_ARGUMENT", "Unexpected profile_id")
        if (command !in setOf("record.start", "dual.record") && audio != null) throw CliFailure("INVALID_ARGUMENT", "Unexpected audio")
        if (command == "dual.record" && audio == true) throw CliFailure("INVALID_ARGUMENT", "Dual video supports silent recording only")
        if (command == "cts.run") {
            if (cases.isNullOrEmpty()) throw CliFailure("INVALID_ARGUMENT", "cases must name at least one suite item")
            if (cases.size > MAX_CASES) throw CliFailure("INVALID_ARGUMENT", "cases lists more than $MAX_CASES items")
            if (cases.any { it.length !in 1..256 || (!it.startsWith(CUSTOM_PREFIX) && !it.startsWith(VENDORED_PREFIX)) })
                throw CliFailure("INVALID_ARGUMENT", "Each case is a suite key starting with $CUSTOM_PREFIX or $VENDORED_PREFIX")
            if (cases.toSet().size != cases.size) throw CliFailure("INVALID_ARGUMENT", "cases repeats an item")
        } else if (cases != null) throw CliFailure("INVALID_ARGUMENT", "Unexpected cases")
    }

    companion object {
        val COMMANDS = listOf("settings.show", "settings.limit", "incidents.list", "incidents.export", "incidents.delete", "burst", "bracket", "meter", "events", "live.info", "dual.cameras", "dual.preview", "dual.capture", "dual.record", "results.list", "results.show", "results.export", "results.compare", "results.delete", "baseline.add", "baseline.remove", "gallery.list", "gallery.export", "gallery.delete", "streams", "cameras", "preview", "preview.stop", "capture", "record.start", "probe", "cts.cases", "cts.run", "benchmark.run")
        val STREAM_COMMANDS = setOf("preview", "capture", "record.start", "burst", "bracket")
        val LIVE_CAMERA_COMMANDS = STREAM_COMMANDS + setOf("streams", "dual.preview", "dual.capture", "dual.record")
        val CAMERA_COMMANDS = LIVE_CAMERA_COMMANDS + "benchmark.run"
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
