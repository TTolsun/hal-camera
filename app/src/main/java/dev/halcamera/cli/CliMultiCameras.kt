package dev.halcamera.cli

import dev.halcamera.camera.ConcurrentPlan
import dev.halcamera.camera.ConcurrentPlanner

/**
 * `multi.cameras` as plain maps: the device sets Multi can open together and the sizes Multi offers each
 * camera. Non-advertised pairs stay listed, marked, because the screen shows them for explicit preflight
 * feedback; a caller picks an advertised set.
 */
internal object CliMultiCameras {
    /** CameraX concurrent capture opens at most two devices; Camera2 takes every advertised set. */
    const val CAMERAX_MAX_DEVICES = 2

    fun toMap(plans: List<ConcurrentPlan>, supported: Boolean): Map<String, Any?> {
        val cameras = plans.flatMap { plan -> plan.streams.map { it.camera } }.distinctBy { it.id }.sortedBy { it.id }
        return mapOf(
            "supported" to supported,
            "reason" to if (supported) null else "Multi requires Android 11 or later",
            "camerax_max_devices" to CAMERAX_MAX_DEVICES,
            "cameras" to cameras.map { camera ->
                mapOf("camera_id" to camera.id, "label" to camera.label, "physical_ids" to camera.physicalIds,
                    "preview_sizes" to ConcurrentPlanner.previewChoices(camera).map { it.toString() },
                    "photo_sizes" to ConcurrentPlanner.photoChoices(camera).map { it.toString() })
            },
            "combinations" to plans.map { plan ->
                mapOf("cameras" to plan.streams.map { it.camera.id }, "label" to plan.label, "advertised" to plan.advertised,
                    "streams" to plan.streams.map {
                        mapOf("camera_id" to it.camera.id, "preview" to it.preview.toString(), "photo" to it.photo.toString())
                    })
            }
        )
    }
}
