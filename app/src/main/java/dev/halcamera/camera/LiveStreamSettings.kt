package dev.halcamera.camera

/** Preserve the existing Camera2 keys while keeping CameraX choices and rollback independent. */
fun liveStreamSettingsKey(cameraId: String, engine: String) = if (engine == "Camera2") cameraId else "$engine:$cameraId"

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

/** Shared by the recorder and its default-option label, including cameras without 1080p. */
fun defaultLiveVideo(sizes: List<LiveSize>): LiveVideo? {
    val landscape = sizes.filter { it.width >= it.height }
    val size = landscape.filter { it.width.toLong() * it.height <= 1920L * 1080 }
        .maxByOrNull { it.width.toLong() * it.height }
        ?: landscape.minByOrNull { it.width.toLong() * it.height }
    return size?.let { LiveVideo(it, 30, "H264") }
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
    val fps: List<LiveFps>, val videos: List<LiveVideo>, val defaultVideo: LiveVideo? = null,
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

/** Dependent recording choices only commit supported triples; opening the panel preserves defaults. */
class LiveVideoDraft(private val support: LiveStreamSupport, initial: LiveVideo?) {
    var requested: LiveVideo? = initial
        private set
    val value get() = requested ?: support.defaultVideo
    val formats get() = support.videos.map { it.codec }.distinct()
    fun sizes(codec: String? = value?.codec) = support.videos.filter { it.codec == codec }
        .map { it.size }.distinct().sortedByDescending { it.width.toLong() * it.height }
    fun rates() = support.videos.filter { it.codec == value?.codec && it.size == value?.size }
        .map { it.fps }.distinct().sortedDescending()
    fun selectFormat(codec: String) {
        val sizes = sizes(codec)
        val size = value?.size?.takeIf { it in sizes } ?: sizes.firstOrNull() ?: return
        select(codec, size, value?.fps)
    }
    fun selectSize(size: LiveSize) { value?.codec?.let { select(it, size, value?.fps) } }
    fun selectRate(rate: Int) { value?.let { select(it.codec, it.size, rate) } }
    private fun select(codec: String, size: LiveSize, rate: Int?) {
        val options = support.videos.filter { it.codec == codec && it.size == size }
        requested = options.firstOrNull { it.fps == rate } ?: options.firstOrNull { it.fps == 30 }
            ?: options.maxByOrNull { it.fps } ?: return
    }
}
