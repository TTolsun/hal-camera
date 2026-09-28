package dev.halcamera.camera

/** LIVE-only values. Benchmark StreamSpec and canonical profiles never read these preferences. */
data class LiveSize(val width: Int, val height: Int) : java.io.Serializable {
    init { require(width > 0 && height > 0) }
    override fun toString() = "${width}x${height}"
}

data class LiveFps(val min: Int, val max: Int) : java.io.Serializable {
    init { require(min > 0 && max >= min) }
    override fun toString() = "$min–$max fps"
}

data class LiveVideo(val size: LiveSize, val fps: Int, val codec: String, val bitrate: Int = 10_000_000) : java.io.Serializable {
    override fun toString() = "$size · $fps fps · $codec"
}

data class LiveStreamSettings(
    val preview: LiveSize,
    val yuv: LiveSize?,
    val jpeg: LiveSize?,
    val fps: LiveFps?,
    val video: LiveVideo? = null,
) : java.io.Serializable {
    val canCapture get() = yuv != null || jpeg != null
    fun metadata(): Map<String, Any?> = mapOf("preview" to preview.toString(), "analysis" to yuv?.toString(),
        "jpeg" to jpeg?.toString(), "fpsRange" to fps?.toString(), "video" to video?.toString())
    fun summary() = "Preview $preview · YUV ${yuv ?: "Off"} · JPEG ${jpeg ?: "Off"} · ${fps ?: "Auto FPS"}"
}

data class LiveStreamSupport(
    val preview: List<LiveSize>, val yuv: List<LiveSize>, val jpeg: List<LiveSize>,
    val fps: List<LiveFps>, val videos: List<LiveVideo>,
) {
    fun rejection(value: LiveStreamSettings): String? = when {
        value.preview !in preview -> "지원하지 않는 Preview 크기입니다."
        value.yuv != null && value.yuv !in yuv -> "지원하지 않는 YUV 크기입니다."
        value.jpeg != null && value.jpeg !in jpeg -> "지원하지 않는 JPEG 크기입니다."
        value.fps != null && value.fps !in fps -> "지원하지 않는 일반 세션 FPS 범위입니다."
        value.video != null && value.video !in videos -> "카메라와 인코더가 지원하지 않는 녹화 설정입니다."
        else -> null
    }
    fun defaults(): LiveStreamSettings {
        fun choose(sizes: List<LiveSize>, budget: Long) = sizes.filter { it.width.toLong() * it.height <= budget }
            .maxByOrNull { it.width.toLong() * it.height } ?: sizes.minBy { it.width.toLong() * it.height }
        return LiveStreamSettings(choose(preview, 1280L * 720), yuv.takeIf { it.isNotEmpty() }?.let { choose(it, 640L * 480) },
            jpeg.takeIf { it.isNotEmpty() }?.let { choose(it, 1920L * 1080) }, null)
    }
}
