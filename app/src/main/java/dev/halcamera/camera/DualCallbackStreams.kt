package dev.halcamera.camera

/** Camera buffers and app encoders remain distinct; unobservable recording outputs still have rows. */
fun dualCallbackStreams(recordingConfigured: Boolean = false, photos: Boolean = true,
    appRecording: Boolean = false, physicalIds: List<String> = emptyList()): List<Map<String, Any?>> = buildList {
    addAll(listOf(
        mapOf("id" to "preview_main", "label" to "Main display", "observable" to true, "eventKind" to "preview_available"),
        mapOf("id" to "preview_sub", "label" to "Sub display", "observable" to true, "eventKind" to "preview_available"),
    ))
    if (photos) addAll(listOf(
        mapOf("id" to "photo_main", "label" to "Main photo", "observable" to true, "eventKind" to "image_available", "repeating" to false),
        mapOf("id" to "photo_sub", "label" to "Sub photo", "observable" to true, "eventKind" to "image_available", "repeating" to false),
    ))
    if (recordingConfigured) listOf("main", "sub").forEachIndexed { index, id ->
        add(OutputDescriptor("recording_$id", OutputKind.RECORDING, true, observable = false,
            physicalId = physicalIds.getOrNull(index).takeUnless { appRecording },
            displayName = "${if (index == 0) "Main" else "Sub"} Recording${if (appRecording) " (App)" else ""}").metadata())
    }
}

/** Direct Camera2 encoder targets exist in repeating requests only after the recorders start. */
internal fun dualRequestStreams(photo: Boolean, recording: Boolean): List<String> =
    if (photo) listOf("photo_main", "photo_sub") else listOf("preview_main", "preview_sub") +
        if (recording) listOf("recording_main", "recording_sub") else emptyList()
