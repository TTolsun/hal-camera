package dev.halcamera.camera

/** Only pairs actually advertised by CameraX belong in its PIP picker. */
internal object CameraXPipSources {
    fun forParent(parent: String, combinations: List<List<String>>): List<PipSource> = combinations
        .filter { it.size == 2 && parent in it && it.distinct().size == 2 }
        .flatten().filter { it != parent }.distinct()
        .map { PipSource(it, false, "Service · ID $it") }
}
