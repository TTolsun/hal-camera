package dev.halcamera.camera

/**
 * What the photo button of a running recording may do right now (#175). Kept free of Android types so the rules
 * run in JVM tests: [phase] follows the recording, and [reason] says why a camera cannot take the photo.
 */
data class SnapshotStatus(val phase: Phase, val reason: String? = null) {
    enum class Phase { NOT_RECORDING, READY, BUSY, UNSUPPORTED }

    val canCapture: Boolean get() = phase == Phase.READY

    companion object {
        val NONE = SnapshotStatus(Phase.NOT_RECORDING)

        /**
         * A stopping recording is never ready: its session is closing, and the JPEG that is still in flight is
         * answered by the recorder. One snapshot at a time; a tap while [inFlight] is refused, not queued.
         */
        fun of(recording: Boolean, stopping: Boolean, unsupported: String?, inFlight: Boolean): SnapshotStatus = when {
            !recording -> NONE
            unsupported != null -> SnapshotStatus(Phase.UNSUPPORTED, unsupported)
            stopping || inFlight -> SnapshotStatus(Phase.BUSY)
            else -> SnapshotStatus(Phase.READY)
        }
    }
}

/** Sizes a video snapshot may use. The JPEG stream shares the recording session, so not every size is accepted. */
object VideoSnapshotPlan {
    private const val DEFAULT_BUDGET = 1920L * 1080

    /**
     * JPEG sizes to try, best first: the size the user asked for (or the LIVE default, the largest up to 1080p),
     * then the largest that fits inside the video size, which is the combination the camera guarantees (preview +
     * record + JPEG at record size). The caller keeps the first one the camera accepts and reports which one it is,
     * so the applied size is never mistaken for the requested one.
     */
    fun candidates(requested: LiveSize?, jpegSizes: List<LiveSize>, video: LiveSize): List<LiveSize> {
        if (jpegSizes.isEmpty()) return emptyList()
        fun area(size: LiveSize) = size.width.toLong() * size.height
        val preferred = requested ?: jpegSizes.filter { area(it) <= DEFAULT_BUDGET }.maxByOrNull(::area) ?: jpegSizes.minBy(::area)
        val guaranteed = jpegSizes.filter { it.width <= video.width && it.height <= video.height }.maxByOrNull(::area)
            ?: jpegSizes.minBy(::area)
        return listOf(preferred, guaranteed).distinct()
    }

    const val OFF_REASON = "Snapshots unavailable: JPEG is off in Live stream settings."
    const val NO_SIZE_REASON = "Snapshots unavailable: this camera has no JPEG output sizes."
    const val REFUSED_REASON = "Recording without snapshots: this camera cannot combine video and JPEG."
    const val CAMERAX_REASON = "Recording without snapshots: CameraX cannot combine video and photos."
}
