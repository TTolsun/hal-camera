package dev.halcamera.camera

/** One definition for the main device's PIP inputs and its virtual composed photo output. */
internal class PipOutputs(physicalId: String?) {
    val inputs = listOfNotNull(
        OutputDescriptor("preview", OutputKind.PREVIEW, true, eventKind = "pip_preview_available"),
        physicalId?.let { OutputDescriptor("pip", OutputKind.PREVIEW, true,
            physicalId = it, eventKind = "pip_preview_available") })
    val photo = OutputDescriptor("pip_photo", OutputKind.JPEG, false,
        eventKind = "pip_photo_available", displayName = "Jpeg")

    fun <T> configure(targets: List<T>, retained: List<ConfiguredOutput<T>>): StreamConfiguration<T> {
        require(targets.size == inputs.size)
        val outputs = retained.map {
            if (it.descriptor.kind == OutputKind.YUV) it.copy(descriptor = it.descriptor.copy(stillCapture = false)) else it
        }
        return StreamConfiguration(inputs.zip(targets) { descriptor, target -> ConfiguredOutput(descriptor, target) } + outputs)
    }
}
