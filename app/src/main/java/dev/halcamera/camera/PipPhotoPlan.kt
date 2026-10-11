package dev.halcamera.camera

/** A secondary original uses the selected JPEG source, within the main source's size budget. */
internal data class PipPhotoPlan(val kind: OutputKind, val mainSize: LiveSize) {
    init { require(kind == OutputKind.JPEG || kind == OutputKind.YUV) }
    fun candidates(sizes: List<LiveSize>): List<LiveSize> {
        val budget = mainSize.width.toLong() * mainSize.height
        val allowed = sizes.filter { it.width.toLong() * it.height <= budget }
        // A camera with only larger outputs still has a valid source; let session support decide.
        return (allowed.ifEmpty { sizes }).sortedWith(compareByDescending<LiveSize> {
            it.width.toLong() * it.height
        }.thenBy { kotlin.math.abs(it.width.toDouble() / it.height - mainSize.width.toDouble() / mainSize.height) })
    }
    fun descriptor(source: PipSource) = OutputDescriptor("pip_still", kind, false, stillCapture = true,
        physicalId = source.id.takeIf { source.physical })
}

internal data class PipCheckedInputs(val previews: List<LiveSize>, val photo: LiveSize?)

/** CameraX 1.6.2 concurrent mode's common PRIVATE/YUV/JPEG combinations (no vendor exceptions). */
internal fun cameraXConcurrentPipStreamsSupported(preview: LiveSize, analysis: LiveSize?, jpeg: LiveSize?): Boolean {
    fun area(size: LiveSize) = size.width.toLong() * size.height
    val small = 1280L * 720
    val large = 1920L * 1440
    if (analysis != null && jpeg != null) return false
    if (jpeg != null) return area(preview) <= small && area(jpeg) <= large
    if (analysis != null) return (area(preview) <= small && area(analysis) <= large) ||
        (area(preview) <= large && area(analysis) <= small)
    return area(preview) <= large
}

/** Secondary size is automatic; a refused largest original must not hide a smaller supported source. */
internal fun choosePipInputs(main: List<LiveSize>, secondary: List<LiveSize>, photos: List<LiveSize?>,
    query: (LiveSize, LiveSize, LiveSize?) -> PipStreamSupport): Pair<PipStreamSupport, PipCheckedInputs?> {
    var unknown = false
    for (photo in photos) for (mainSize in main) for (secondarySize in secondary) {
        when (val result = query(mainSize, secondarySize, photo)) {
            PipStreamSupport.SUPPORTED -> return result to PipCheckedInputs(listOf(mainSize, secondarySize), photo)
            PipStreamSupport.UNKNOWN -> unknown = true
            PipStreamSupport.UNSUPPORTED -> Unit
        }
    }
    return (if (unknown) PipStreamSupport.UNKNOWN else PipStreamSupport.UNSUPPORTED) to null
}
