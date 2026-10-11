package dev.halcamera.ui

/** Pure presentation policy, shared by visible text and accessibility. Composite files are not camera streams. */
internal data class LiveStreamReadout(val text: String, val description: String) {
    companion object {
        private data class Label(val key: String, val short: String, val name: String, val pip: Boolean = false)
        private val labels = listOf(Label("preview", "P", "Preview"), Label("analysis", "Y", "YUV"),
            Label("jpeg", "J", "JPEG"), Label("raw", "RAW", "RAW"), Label("recording", "R", "Recording"),
            Label("pip", "P", "PIP Preview", true), Label("pipYuv", "Y", "PIP YUV", true), Label("pipJpeg", "J", "PIP JPEG", true))

        fun from(streams: Map<*, *>?): LiveStreamReadout {
            fun value(key: String) = streams?.get(key)?.toString()?.takeIf { it.isNotBlank() }
            val format = value("recordingFormat") ?: "Auto"
            val active = labels.mapNotNull { label ->
                val size = value(label.key) ?: if (label.key == "jpeg") value("appJpeg")?.let {
                    "${value("appJpegSource") ?: "YUV"} ($it)"
                } else null
                size?.let { label to it }
            }
            val text = active.groupBy { it.first.pip }.entries.joinToString("\n") { (pip, row) ->
                (if (pip) "PIP " else "") + row.joinToString("   ") { (label, size) ->
                    "${label.short}${if (label.key == "recording") " $format" else ""} ${size.replace('x', '×')}"
                }
            }
            val description = active.joinToString(", ") { (label, size) ->
                "${label.name}${if (label.key == "recording") " $format" else ""} $size"
            }
            return LiveStreamReadout(text, description)
        }
    }
}
