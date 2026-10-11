package dev.halcamera.camera

/** One identity definition for graph rows, submitted targets and image telemetry. */
internal object DualOutputs {
    private fun outputs(prefix: String, kind: OutputKind, label: String, repeating: Boolean, observable: Boolean, still: Boolean = false) =
        listOf("main", "sub").mapIndexed { index, id ->
            OutputDescriptor("${prefix}_$id", kind, repeating, stillCapture = still, observable = observable,
                displayName = "${if (index == 0) "Main" else "Sub"} $label")
        }
    val previews = outputs("preview", OutputKind.PREVIEW, "Preview", true, false)
    val photos = outputs("photo", OutputKind.YUV, "photo", false, true, still = true)
    val recordings = outputs("recording", OutputKind.RECORDING, "Recording", true, false)
}

/** Only Camera2's direct camera recording targets belong in the Callback stream list. */
fun dualCallbackStreams(recordingConfigured: Boolean = false, photos: Boolean = true,
    physicalIds: List<String> = emptyList()): List<Map<String, Any?>> = buildList {
    fun physical(outputs: List<OutputDescriptor>) = outputs.mapIndexed { index, output ->
        output.copy(physicalId = physicalIds.getOrNull(index)).metadata()
    }
    addAll(physical(DualOutputs.previews))
    if (photos) addAll(DualOutputs.photos.map { it.metadata() })
    if (recordingConfigured) addAll(physical(DualOutputs.recordings))
}

/** Direct Camera2 encoder targets exist in repeating requests only after the recorders start. */
internal fun dualRequestStreams(photo: Boolean, recording: Boolean): List<String> =
    (if (photo) DualOutputs.photos else DualOutputs.previews + if (recording) DualOutputs.recordings else emptyList()).map { it.id }
