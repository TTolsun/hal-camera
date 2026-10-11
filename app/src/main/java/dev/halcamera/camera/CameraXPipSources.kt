package dev.halcamera.camera

/** Keep all candidates visible; only service pairs advertised by CameraX are selectable. */
internal object CameraXPipSources {
    fun forParent(parent: String, combinations: List<List<String>>): List<PipSource> = combinations
        .filter { it.size == 2 && parent in it && it.distinct().size == 2 }
        .flatten().filter { it != parent }.distinct()
        .map { PipSource(it, false, "Service · ID $it") }

    fun forParent(parent: String, combinations: List<List<String>>, candidates: List<PipSource>): List<PipSource> {
        val supported = forParent(parent,combinations).map { it.key }.toSet()
        return candidates.map { source -> source.copy(unavailableReason = when {
            source.physical -> "Camera2 only"
            source.key !in supported -> "Cannot run together"
            else -> source.unavailableReason
        }) }
    }
}
