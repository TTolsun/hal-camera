package dev.halcamera.camera

/** Display delivery is observable on both engines; timestamps are matched to each physical result. */
fun dualCallbackStreams() = listOf(
    mapOf("id" to "preview_main", "label" to "Main display", "observable" to true, "eventKind" to "preview_available"),
    mapOf("id" to "preview_sub", "label" to "Sub display", "observable" to true, "eventKind" to "preview_available"),
    mapOf("id" to "photo_main", "label" to "Main photo", "observable" to true, "eventKind" to "image_available", "repeating" to false),
    mapOf("id" to "photo_sub", "label" to "Sub photo", "observable" to true, "eventKind" to "image_available", "repeating" to false),
)
