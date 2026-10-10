package dev.halcamera.camera

/** One definition for the main device's PIP inputs and its virtual composed photo output. */
internal class PipOutputs(physicalId: String?) {
    val inputs = listOfNotNull(
        OutputDescriptor("preview", OutputKind.PREVIEW, true, eventKind = "pip_preview_available"),
        physicalId?.let { OutputDescriptor("pip", OutputKind.PREVIEW, true,
            physicalId = it, eventKind = "pip_preview_available") })
    val photo = OutputDescriptor("pip_photo", OutputKind.JPEG, false,
        eventKind = "pip_photo_available", displayName = "Jpeg")

    fun <T> configure(targets: List<T>, analysis: List<ConfiguredOutput<T>>): StreamConfiguration<T> {
        require(targets.size == inputs.size)
        return StreamConfiguration(inputs.zip(targets) { descriptor, target -> ConfiguredOutput(descriptor, target) } + analysis)
    }
}
