package dev.halcamera.cli

import dev.halcamera.camera.ConcurrentPlan
import dev.halcamera.camera.ConcurrentPlanner
import dev.halcamera.camera.LiveSize

/** Strict ordered Multi selection. Never inherit UI choices or silently replace an invalid size. */
object CliMultiOptions {
    fun ids(value: String): List<String> = value.split(',').also { ids ->
        if (ids.size !in 2..16 || ids.distinct().size != ids.size || ids.any { !it.matches(Regex("[A-Za-z0-9_-]{1,128}")) })
            throw CliFailure("INVALID_ARGUMENT", "cameras must list 2–16 distinct camera IDs")
    }

    fun sizes(value: String): List<LiveSize> = value.split(',').map {
        val parts = it.split('x')
        if (parts.size != 2 || parts.any { part -> !part.matches(Regex("[1-9][0-9]{0,4}")) })
            throw CliFailure("INVALID_ARGUMENT", "Sizes must be WIDTHxHEIGHT lists in camera order")
        LiveSize(parts[0].toInt(), parts[1].toInt())
    }

    fun validate(values: Map<String, String>) {
        val ids = ids(values.getValue("cameras"))
        for (key in listOf("preview_sizes", "photo_sizes")) values[key]?.let {
            if (sizes(it).size != ids.size) throw CliFailure("INVALID_ARGUMENT", "$key must have one size per camera")
        }
    }

    fun resolve(command: CliCommand, sdk: Int, plans: List<ConcurrentPlan>): ConcurrentPlan {
        if (sdk < 30) throw CliFailure("PREFLIGHT_FAILED", "Multi requires Android 11 or later")
        val values = command.options!!.values
        val ids = ids(values.getValue("cameras"))
        if (command.engine == "CameraX" && ids.size > CliMultiCameras.CAMERAX_MAX_DEVICES)
            throw CliFailure("PREFLIGHT_FAILED", "CameraX Multi supports at most two cameras")
        val plan = plans.firstOrNull { it.advertised && it.streams.map { stream -> stream.camera.id }.toSet() == ids.toSet() }
            ?: throw CliFailure("PREFLIGHT_FAILED", "Use an advertised combination from multi cameras")
        val previews = values["preview_sizes"]?.let(::sizes)
        val photos = values["photo_sizes"]?.let(::sizes)
        return plan.copy(streams = ids.mapIndexed { index, id ->
            val stream = plan.streams.first { it.camera.id == id }
            val preview = previews?.get(index) ?: stream.preview
            val photo = photos?.get(index) ?: stream.photo
            if (preview !in ConcurrentPlanner.previewChoices(stream.camera) || photo !in ConcurrentPlanner.photoChoices(stream.camera))
                throw CliFailure("PREFLIGHT_FAILED", "Unsupported Multi size for camera $id; use multi cameras")
            stream.copy(preview = preview, photo = photo)
        })
    }
}
