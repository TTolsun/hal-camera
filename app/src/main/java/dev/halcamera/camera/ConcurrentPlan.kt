package dev.halcamera.camera

/** Independently addressable devices, never physical outputs of another logical camera. */
data class ConcurrentCamera(val id: String, val facing: Int?, val previews: List<LiveSize>, val photos: List<LiveSize>, val physicalIds: List<String> = emptyList()) {
    val label get() = "${when (facing) { CameraLabel.FACING_FRONT -> "Front"; CameraLabel.FACING_BACK -> "Rear"; else -> "Camera" }} $id"
}
data class ConcurrentStream(val camera: ConcurrentCamera, val preview: LiveSize, val photo: LiveSize)
data class ConcurrentPlan(val streams: List<ConcurrentStream>, val advertised: Boolean = true) {
    val label get() = streams.joinToString(" + ") { it.camera.label }
}

/** Status is data, never inferred from translated messages or filenames. */
data class ConcurrentMediaResult(val message: String, val failed: Boolean = false)

object ConcurrentPlanner {
    fun plans(sdk: Int, combinations: Set<Set<String>>, cameras: List<ConcurrentCamera>, maxDevices: Int = 2): List<ConcurrentPlan> {
        if (sdk < 30) return emptyList()
        val available = cameras.distinctBy { it.id }.sortedWith(compareBy<ConcurrentCamera> {
            if (it.facing == CameraLabel.FACING_BACK) 0 else 1
        }.thenBy { it.id })
        val limit = maxDevices.coerceAtMost(available.size)
        if (limit < 2) return emptyList()
        val groups = linkedSetOf<List<ConcurrentCamera>>()
        // Include independent pairs for explicit preflight feedback, then every advertised larger subset.
        available.forEachIndexed { index, first -> available.drop(index + 1).forEach { groups += listOf(first, it) } }
        fun subsets(group: List<ConcurrentCamera>, index: Int, chosen: List<ConcurrentCamera>) {
            if (chosen.size >= 3) groups += chosen
            if (chosen.size == limit) return
            for (next in index until group.size) subsets(group, next + 1, chosen + group[next])
        }
        combinations.forEach { ids -> subsets(available.filter { it.id in ids }, 0, emptyList()) }
        return groups.mapNotNull { group ->
            val streams = group.map { camera ->
                val preview = previewChoices(camera).firstOrNull() ?: return@mapNotNull null
                val photo = photoChoices(camera).firstOrNull() ?: return@mapNotNull null
                ConcurrentStream(camera, preview, photo)
            }
            ConcurrentPlan(streams, combinations.any { ids -> group.all { it.id in ids } })
        }.sortedWith(compareBy<ConcurrentPlan> { !it.advertised }.thenByDescending { it.streams.size })
    }

    /** Concurrent sessions guarantee s720p preview and s1440p JPEG, so only sizes within them are offered. Largest first. */
    fun previewChoices(camera: ConcurrentCamera) = choices(camera.previews, 1280L * 720)
    fun photoChoices(camera: ConcurrentCamera) = choices(camera.photos, 1920L * 1440)

    /** Applies per-camera (preview, JPEG) choices; a size outside the offered list is ignored, never substituted. */
    fun withSizes(plan: ConcurrentPlan, sizes: Map<String, Pair<LiveSize, LiveSize>>) = plan.copy(streams = plan.streams.map { stream ->
        val (preview, photo) = sizes[stream.camera.id] ?: return@map stream
        stream.copy(preview = preview.takeIf { it in previewChoices(stream.camera) } ?: stream.preview,
            photo = photo.takeIf { it in photoChoices(stream.camera) } ?: stream.photo)
    })

    private fun choices(sizes: List<LiveSize>, maxArea: Long) = sizes.distinct()
        .filter { it.width.toLong() * it.height <= maxArea }
        .sortedWith(compareByDescending<LiveSize> { it.width.toLong() * it.height }.thenByDescending { it.width })
}

/** Both opens must finish before any configure; shutdown must include late open callbacks. Handler-confined. */
internal class ConcurrentLifecycle(ids: List<String>) {
    enum class Phase { IDLE, OPENING, OPEN, STREAMING, CLOSING, CLOSED }
    val phases = ids.associateWith { Phase.IDLE }.toMutableMap()
    var stopping = false
        private set
    val canConfigure get() = !stopping && phases.values.all { it == Phase.OPEN }
    val ready get() = !stopping && phases.values.all { it == Phase.STREAMING }
    val closed get() = stopping && phases.values.all { it == Phase.CLOSED }
    fun opening(id: String) { check(!stopping); phases[id] = Phase.OPENING }
    fun opened(id: String) { phases[id] = if (stopping) Phase.CLOSING else Phase.OPEN }
    fun streaming(id: String) { if (!stopping) phases[id] = Phase.STREAMING }
    fun closed(id: String) { phases[id] = Phase.CLOSED }
    fun stop() {
        stopping = true
        phases.replaceAll { _, phase -> when (phase) {
            Phase.IDLE -> Phase.CLOSED
            Phase.OPEN, Phase.STREAMING -> Phase.CLOSING
            else -> phase
        } }
    }
}

/** A single command produces independent outcomes, never a claim of sensor synchronization. */
internal class ConcurrentCapture<T>(val groupId: String, val requestedAtNs: Long, ids: List<String>) {
    val outcomes = ids.associateWith { null as Result<T>? }.toMutableMap()
    val complete get() = outcomes.values.all { it != null }
    fun settle(id: String, result: Result<T>) {
        if (id in outcomes && outcomes[id] == null) outcomes[id] = result
    }
    fun cancel(reason: String) {
        outcomes.keys.forEach { settle(it, Result.failure(IllegalStateException(reason))) }
    }
}

/** Serialize successive Activity instances, including recreation while old open callbacks are pending. */
internal class ConcurrentLease {
    private var owner: Any? = null
    private val waiting = linkedMapOf<Any, () -> Unit>()

    fun acquire(key: Any, start: () -> Unit) {
        val run = synchronized(this) {
            if (owner == null) { owner = key; true }
            else { waiting[key] = start; false }
        }
        if (run) start()
    }

    fun release(key: Any) {
        val next = synchronized(this) {
            waiting.remove(key)
            if (owner !== key) null else {
                val first = waiting.entries.firstOrNull()
                owner = first?.key
                first?.value.also { if (first != null) waiting.remove(first.key) }
            }
        }
        next?.invoke()
    }
}

internal data class ConcurrentFrame(val left: Int, val top: Int, val width: Int, val height: Int)

/** Chosen by dragging a panel (see [SplitArrangement.snap]), not from a menu. */
internal enum class SplitLayout { STACKED, SIDE_BY_SIDE, GRID }

internal fun splitGridColumns(count: Int) = kotlin.math.ceil(kotlin.math.sqrt(count.toDouble())).toInt()

/** Preview geometry only. Captures and encoders retain each camera's own dimensions. */
internal fun concurrentFrames(count: Int, width: Int, height: Int, layout: SplitLayout = SplitLayout.SIDE_BY_SIDE): List<ConcurrentFrame> {
    if (count < 1 || width < 1 || height < 1) return emptyList()
    val columns = when (layout) {
        SplitLayout.STACKED -> 1
        SplitLayout.SIDE_BY_SIDE -> count
        SplitLayout.GRID -> splitGridColumns(count)
    }
    val rows = (count + columns - 1) / columns
    return List(count) { index ->
        val row = index / columns
        val inRow = minOf(columns, count - row * columns)
        val col = index % columns
        val left = col * width / inRow
        val top = row * height / rows
        ConcurrentFrame(left, top, (col + 1) * width / inRow - left, (row + 1) * height / rows - top)
    }
}

/** Sensor output size is independent of how small or wide its Split panel is. */
internal fun concurrentOutputSize(preview: LiveSize, rotation: Int): LiveSize =
    if ((rotation % 180 + 180) % 180 == 90) LiveSize(preview.height, preview.width) else preview

/**
 * Preview inside a Split panel, between a [header]-tall name bar and a [footer]-tall readout row that ends at the
 * panel bottom. Spare height then sits above the name bar instead of as a black band next to the capture
 * controls. [aspect] is null until the stream size is known.
 */
internal fun splitPreviewFrame(width: Int, height: Int, header: Int, footer: Int, aspect: Float?): ConcurrentFrame {
    val room = (height - header - footer).coerceAtLeast(1)
    val bottom = height - footer
    if (aspect == null) return ConcurrentFrame(0, bottom - room, width, room)
    val fit = fitConcurrentFrame(width, room, aspect)
    return fit.copy(top = bottom - fit.height)
}

/** Side by side and grid panels are narrow, so FPS and exposure go under the preview; stacked ones fit them in the name bar. */
internal fun splitReadoutBelow(layout: SplitLayout) = layout != SplitLayout.STACKED

/** Letterbox instead of sampling a centre crop when a preview surface resizes. */
internal fun fitConcurrentFrame(width: Int, height: Int, aspect: Float): ConcurrentFrame {
    require(width > 0 && height > 0 && aspect.isFinite() && aspect > 0)
    val w = minOf(width, (height * aspect).toInt().coerceAtLeast(1))
    val h = minOf(height, (width / aspect).toInt().coerceAtLeast(1))
    return ConcurrentFrame((width-w)/2,(height-h)/2,w,h)
}
