package dev.halcamera.camera

/** One definition for the main device's PIP inputs and its virtual composed photo output. */
internal class PipOutputs(physicalId: String?) {
    companion object {
        fun <T> retained(outputs: List<ConfiguredOutput<T>>) = outputs.filter {
            it.descriptor.kind in setOf(OutputKind.YUV, OutputKind.RAW, OutputKind.JPEG) && it.descriptor.id != "video_snapshot"
        }
    }
    val inputs = listOfNotNull(
        OutputDescriptor("preview", OutputKind.PREVIEW, true, eventKind = "pip_preview_available"),
        physicalId?.let { OutputDescriptor("pip", OutputKind.PREVIEW, true,
            physicalId = it, eventKind = "pip_preview_available") })
    val photo = OutputDescriptor("pip_photo", OutputKind.JPEG, false,
        eventKind = "pip_photo_available", displayName = "PIP JPEG")

    fun sizes(preview: String, analysis: String?, output: String, raw: String? = null,
        photo: PipPhotoPlan? = null, secondaryPreview: LiveSize? = null, secondaryPhoto: LiveSize? = null): Map<String, String?> =
        mapOf("preview" to preview, "analysis" to analysis, "raw" to raw, "composite" to output,
            "jpeg" to photo?.mainSize?.toString()?.takeIf { photo.kind == OutputKind.JPEG },
            "appJpeg" to photo?.mainSize?.toString()?.takeIf { photo.kind == OutputKind.YUV },
            "pip" to secondaryPreview?.toString(),
            "pipJpeg" to secondaryPhoto?.toString()?.takeIf { photo?.kind == OutputKind.JPEG },
            "pipYuv" to secondaryPhoto?.toString()?.takeIf { photo?.kind == OutputKind.YUV })

    fun <T> configure(targets: List<T>, retained: List<ConfiguredOutput<T>>): StreamConfiguration<T> {
        require(targets.size == inputs.size)
        return StreamConfiguration(inputs.zip(targets) { descriptor, target -> ConfiguredOutput(descriptor, target) } + retained)
    }
}
