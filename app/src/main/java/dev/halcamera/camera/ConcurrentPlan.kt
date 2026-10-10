package dev.halcamera.camera

/** Independently addressable devices, never physical outputs of another logical camera. */
data class ConcurrentCamera(val id: String, val facing: Int?, val previews: List<LiveSize>, val photos: List<LiveSize>) {
    val label get() = "${when (facing) { CameraLabel.FACING_FRONT -> "Front"; CameraLabel.FACING_BACK -> "Rear"; else -> "Camera" }} $id"
}
data class ConcurrentStream(val camera: ConcurrentCamera, val preview: LiveSize, val photo: LiveSize)
data class ConcurrentPlan(val streams: List<ConcurrentStream>, val advertised: Boolean = true) {
    val label get() = streams.joinToString(" + ") { it.camera.label }
}

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
                val preview = size(camera.previews, 1280L * 720) ?: return@mapNotNull null
                val photo = size(camera.photos, 1920L * 1440) ?: return@mapNotNull null
                ConcurrentStream(camera, preview, photo)
            }
            ConcurrentPlan(streams, combinations.any { ids -> group.all { it.id in ids } })
        }.sortedWith(compareBy<ConcurrentPlan> { !it.advertised }.thenByDescending { it.streams.size })
    }

    private fun size(sizes: List<LiveSize>, maxArea: Long) = sizes
        .filter { it.width.toLong() * it.height <= maxArea }
        .maxWithOrNull(compareBy<LiveSize> { it.width.toLong() * it.height }.thenBy { it.width })
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

/** Stable device-index order; the primary fills the stage and the others share an inset column. */
internal fun concurrentFrames(count: Int, width: Int, height: Int, primary: Int,
    split: Boolean, scale: Float, x: Float, y: Float): List<ConcurrentFrame> {
    if (count < 1 || width < 1 || height < 1) return emptyList()
    val main = primary.coerceIn(0, count - 1)
    val insetCount = (count - 1).coerceAtLeast(1)
    val insetWidth = (width * scale.coerceIn(.25f, .5f)).toInt().coerceAtLeast(1)
    val insetHeight = minOf(insetWidth * 4 / 3, height / insetCount)
    return List(count) { index ->
        val rank = (index - main + count) % count
        when {
            split -> {
                val top = rank * height / count
                ConcurrentFrame(0, top, width, (rank + 1) * height / count - top)
            }
            index == main -> ConcurrentFrame(0, 0, width, height)
            else -> ConcurrentFrame(((width - insetWidth) * x.coerceIn(0f, 1f)).toInt(),
                ((height - insetHeight * insetCount) * y.coerceIn(0f, 1f)).toInt() + (rank - 1) * insetHeight,
                insetWidth, insetHeight)
        }
    }
}
