package dev.halcamera.camera

enum class OutputKind(val label: String, val eventKind: String = "image_available") {
    PREVIEW("Preview", "preview_available"), YUV("YUV"), JPEG("JPEG"), RECORDING("Recording"), RAW("RAW")
}

/** Identity belongs to the output, never to its translated/numbered display name. */
data class OutputDescriptor(
    val id: String,
    val kind: OutputKind,
    val repeating: Boolean,
    val stillCapture: Boolean = false,
    val observable: Boolean = true
)

data class ConfiguredOutput<T>(val descriptor: OutputDescriptor, val target: T)

/** The very same outputs create the session, select request targets and describe the graph. */
class StreamConfiguration<T>(outputs: List<ConfiguredOutput<T>>) {
    val outputs = outputs.toList()
    init {
        require(this.outputs.all { it.descriptor.id.isNotBlank() }) { "Output IDs must not be blank" }
        require(this.outputs.map { it.descriptor.id }.distinct().size == this.outputs.size) { "Duplicate output ID" }
        require(this.outputs.map { it.target }.distinct().size == this.outputs.size) { "Duplicate output target" }
    }
    val targets get() = outputs.map { it.target }
    val repeating get() = outputs.filter { it.descriptor.repeating }
    val still get() = outputs.filter { it.descriptor.stillCapture }

    fun metadata(): List<Map<String, Any?>> {
        return outputs.map { output ->
            val stream = output.descriptor
            mapOf("id" to stream.id, "kind" to stream.kind.name, "eventKind" to stream.kind.eventKind,
                "label" to stream.kind.label,
                "repeating" to stream.repeating, "observable" to stream.observable)
        }
    }
}
