package dev.halcamera.cli

import dev.halcamera.camera.*

/** Explicit per-request overrides; omitted fields use capability-derived defaults, never prior UI state. */
data class CliStreams(val values: Map<String, String>) {
    init {
        if (values.isEmpty() || values.keys.any { it !in KEYS }) invalid("Unknown or empty stream options")
        values.forEach { (key, value) ->
            when (key) {
                "stabilization" -> if (value !in LiveStabilization.entries.map { it.name }) invalid("stabilization must be AUTO, OFF, OIS, VIDEO or PREVIEW")
                "jpeg_size" -> if (value !in setOf("yuv", "off")) size(value)
                "fps" -> fps(value)
                "codec" -> if (value !in setOf("H264", "HEVC", "Auto")) invalid("codec must be H264, HEVC or Auto")
                "video_fps" -> if (value.toIntOrNull() !in 1..240) invalid("video_fps must be 1–240")
                else -> if (value != "off" || key !in setOf("yuv_size", "jpeg_size", "raw_size")) size(value)
            }
        }
    }

    fun resolve(support: LiveStreamSupport, mode: LiveModePolicy? = null, pip: PipSource? = null): LiveStreamSettings {
        if (pip == null && values.keys.any { it in PIP_KEYS })
            throw CliFailure("INVALID_ARGUMENT", "PIP sizes require --pip-type physical|service and --pip-camera")
        if (mode == LiveModePolicy.VIDEO && values["raw_size"].let { it != null && it != "off" })
            throw CliFailure("PREFLIGHT_FAILED", "RAW/DNG is available in Photo mode only")
        if (mode == LiveModePolicy.VIDEO && values["fps"].let { it != null && it != "auto" })
            throw CliFailure("PREFLIGHT_FAILED", "Use --video-fps in Video mode; --fps is for Photo preview")
        if (mode == LiveModePolicy.PHOTO && values["stabilization"].let { it != null && it !in setOf("AUTO", "OFF") })
            throw CliFailure("PREFLIGHT_FAILED", "Stabilization is available in Video mode only")
        if (mode == LiveModePolicy.PHOTO && values.keys.any { it in VIDEO_KEYS })
            throw CliFailure("PREFLIGHT_FAILED", "Recording stream options require Video mode; use preview --mode Video or record start")
        val defaults = support.defaults()
        fun optional(key: String, fallback: LiveSize?) = when (val value = values[key]) {
            null -> fallback
            "off", "yuv" -> null
            else -> size(value)
        }
        val video = if (values.keys.any { it in VIDEO_KEYS }) {
            val base = support.defaultVideo ?: invalid("Camera has no recording configuration")
            LiveVideo(values["video_size"]?.let(::size) ?: base.size,
                values["video_fps"]?.toInt() ?: base.fps, values["codec"] ?: base.codec)
        } else null
        val result = defaults.copy(preview = values["preview_size"]?.let(::size) ?: defaults.preview,
            yuv = optional("yuv_size", defaults.yuv), jpeg = optional("jpeg_size", defaults.jpeg), video = video,
            raw = optional("raw_size", null), fps = values["fps"]?.let(::fps),
            stabilization = values["stabilization"]?.let(LiveStabilization::valueOf) ?: defaults.stabilization,
            jpegFromYuv = values["jpeg_size"] == "yuv",
            pip = pip?.let { mapOf(it.key to PipStreamSettings(values["pip_preview_size"]?.let(::size), values["pip_photo_size"]?.let(::size))) }.orEmpty())
        support.rejection(result)?.let { throw CliFailure("PREFLIGHT_FAILED", it) }
        return mode?.apply(result) ?: result
    }

    companion object {
        val VIDEO_KEYS = setOf("video_size", "video_fps", "codec")
        val PIP_KEYS = setOf("pip_preview_size", "pip_photo_size")
        val KEYS = setOf("preview_size", "yuv_size", "jpeg_size", "raw_size", "fps", "stabilization") + VIDEO_KEYS + PIP_KEYS
        private fun fps(value: String): LiveFps? {
            if (value == "auto") return null
            val parts = value.split('-').map { it.toIntOrNull() ?: invalid("fps must be auto, FPS or MIN-MAX") }
            if (parts.size !in 1..2 || parts.any { it !in 1..240 } || parts.last() < parts.first()) invalid("fps must be auto, FPS or MIN-MAX")
            return LiveFps(parts.first(), parts.last())
        }
        private fun invalid(message: String): Nothing = throw CliFailure("INVALID_ARGUMENT", message)
        private fun size(value: String): LiveSize {
            if (!value.matches(Regex("[1-9][0-9]{0,4}x[1-9][0-9]{0,4}"))) invalid("Size must be WIDTHxHEIGHT (or off for YUV/JPEG)")
            val parts = value.split('x')
            return LiveSize(parts[0].toInt(), parts[1].toInt())
        }
    }
}
