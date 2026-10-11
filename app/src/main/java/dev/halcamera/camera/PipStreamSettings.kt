package dev.halcamera.camera

/** Auto is represented by null. Explicit PIP sizes must never be silently replaced. */
data class PipStreamSettings(val preview: LiveSize? = null, val photo: LiveSize? = null) : java.io.Serializable

data class PipStreamCapabilities(val source: PipSource, val preview: List<LiveSize>,
    val yuv: List<LiveSize>, val jpeg: List<LiveSize>) {
    fun rejection(settings: LiveStreamSettings): String? {
        val value = settings.pip[source.key] ?: return null
        return when {
            value.preview != null && value.preview !in preview -> "Unsupported PIP preview size."
            settings.jpegFromYuv && value.photo != null && value.photo !in yuv -> "Unsupported PIP YUV size."
            settings.jpeg != null && value.photo != null && value.photo !in jpeg -> "Unsupported PIP JPEG size."
            else -> null
        }
    }
}
