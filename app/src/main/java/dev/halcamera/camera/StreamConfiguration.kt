package dev.halcamera.camera

enum class OutputKind(val label: String, val eventKind: String = "image_available") {
    PREVIEW("Preview", "preview_available"), YUV("YUV"), JPEG("JPEG"), RECORDING("Recording"), RAW("RAW")
}

/** App conversion is observed after encoding; it never creates a HAL JPEG target. */
internal val appJpegOutput = OutputDescriptor("yuv_jpeg", OutputKind.JPEG, false,
    eventKind = "app_jpeg_available", displayName = "Jpeg")

/** Identity belongs to the output, never to its translated/numbered display name. */
data class OutputDescriptor(
    val id: String,
    val kind: OutputKind,
    val repeating: Boolean,
    val stillCapture: Boolean = false,
    val observable: Boolean = true,
    val physicalId: String? = null,
    val eventKind: String = kind.eventKind,
    val displayName: String? = null
) {
    fun metadata(): Map<String, Any?> = mapOf(
        "id" to id, "kind" to kind.name, "eventKind" to eventKind,
        "label" to ((displayName ?: kind.label) + if (physicalId != null) " (Phy)" else ""),
        "physicalId" to physicalId, "repeating" to repeating, "observable" to observable)
}

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

    fun metadata(appJpeg: Boolean = false): List<Map<String, Any?>> =
        outputs.map { it.descriptor.metadata() } + if (appJpeg) listOf(appJpegOutput.metadata()) else emptyList()
}
