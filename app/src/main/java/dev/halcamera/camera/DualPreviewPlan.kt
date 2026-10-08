package dev.halcamera.camera

/**
 * Pure choices behind the dual rear preview (#171): which two physical cameras of a logical multi-camera to stream
 * together, and at which size. Android-free so the rules can be tested without a camera.
 *
 * Every physical stream goes through the logical camera with OutputConfiguration.setPhysicalCameraId; a physical
 * id is never opened directly, because the public API does not promise that it can be. The size chosen here is
 * only a candidate: isSessionConfigurationSupported and the real configure decide, and a refused candidate moves
 * on to the next one in [DualPreviewPlan.sizes].
 */
data class PhysicalLens(
    val id: String,
    val role: LensRole,
    val equivalentFocalMm: Double?,
    /** SurfaceTexture output sizes from the physical camera's own stream configuration map; empty when unreadable. */
    val previewSizes: List<LiveSize>
)

data class LogicalMultiCamera(
    val logicalId: String,
    val facing: Int?,
    val logicalMultiCamera: Boolean,
    /** LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE; null when the HAL did not report it. */
    val syncType: Int?,
    val physical: List<PhysicalLens>
)

/** Why a logical camera, or a pair on it, cannot be streamed. Shown as-is; nothing here is guessed. */
enum class DualPreviewRefusal(val label: String) {
    API_TOO_OLD("Android 9 (API 28) 이상이 필요합니다."),
    NOT_LOGICAL("논리 멀티 카메라가 아닙니다 (LOGICAL_MULTI_CAMERA 없음)."),
    TOO_FEW_PHYSICAL("물리 카메라가 2개 미만입니다."),
    SAME_LENS("서로 다른 물리 카메라 2개를 골라야 합니다."),
    NO_COMMON_SIZE("두 물리 카메라가 함께 지원하는 프리뷰 크기가 없습니다.")
}

data class DualPreviewPlan(
    val logicalId: String,
    val first: PhysicalLens,
    val second: PhysicalLens,
    /** Candidate sizes, best first, all listed by both physical cameras. */
    val sizes: List<LiveSize>
)

object DualPreviewPlanner {
    /** Two streams at once: keep each at or below 1080p so the pair stays inside the mandatory combinations. */
    const val MAX_AREA = 1920 * 1080
    const val MAX_CANDIDATES = 4

    /** Rear logical cameras worth offering, in id order. */
    fun candidates(cameras: List<LogicalMultiCamera>): List<LogicalMultiCamera> =
        cameras.filter { it.facing == CameraLabel.FACING_BACK && it.logicalMultiCamera && it.physical.size >= 2 }
            .sortedBy { it.logicalId }

    fun refusal(camera: LogicalMultiCamera, sdk: Int): DualPreviewRefusal? = when {
        sdk < 28 -> DualPreviewRefusal.API_TOO_OLD
        !camera.logicalMultiCamera -> DualPreviewRefusal.NOT_LOGICAL
        camera.physical.size < 2 -> DualPreviewRefusal.TOO_FEW_PHYSICAL
        else -> null
    }

    /** Wide + Tele first, then Wide + UWide, then the first two listed. */
    fun defaultPair(camera: LogicalMultiCamera): Pair<String, String>? {
        val lenses = camera.physical
        if (lenses.size < 2) return null
        val main = lenses.firstOrNull { it.role == LensRole.MAIN }
        val other = main?.let { m ->
            lenses.firstOrNull { it.id != m.id && it.role == LensRole.TELE }
                ?: lenses.firstOrNull { it.id != m.id && it.role == LensRole.ULTRA_WIDE }
        }
        return if (main != null && other != null) main.id to other.id else lenses[0].id to lenses[1].id
    }

    fun plan(camera: LogicalMultiCamera, firstId: String, secondId: String, sdk: Int): Result {
        refusal(camera, sdk)?.let { return Result.Refused(it) }
        if (firstId == secondId) return Result.Refused(DualPreviewRefusal.SAME_LENS)
        val first = camera.physical.firstOrNull { it.id == firstId } ?: return Result.Refused(DualPreviewRefusal.TOO_FEW_PHYSICAL)
        val second = camera.physical.firstOrNull { it.id == secondId } ?: return Result.Refused(DualPreviewRefusal.TOO_FEW_PHYSICAL)
        val sizes = commonSizes(first.previewSizes, second.previewSizes)
        if (sizes.isEmpty()) return Result.Refused(DualPreviewRefusal.NO_COMMON_SIZE)
        return Result.Ready(DualPreviewPlan(camera.logicalId, first, second, sizes))
    }

    /**
     * Sizes both cameras list, capped at [MAX_AREA]: 4:3 first (the sensors' native shape), then 16:9, then the
     * rest, largest first within each group.
     */
    fun commonSizes(a: List<LiveSize>, b: List<LiveSize>): List<LiveSize> {
        val shared = a.toSet().intersect(b.toSet()).filter { it.width * it.height <= MAX_AREA }
        return shared.sortedWith(compareBy<LiveSize>({ aspectRank(it) }, { -it.width * it.height }))
            .take(MAX_CANDIDATES)
    }

    private fun aspectRank(size: LiveSize): Int = when {
        size.width * 3 == size.height * 4 -> 0
        size.width * 9 == size.height * 16 -> 1
        else -> 2
    }

    fun syncLabel(syncType: Int?): String = when (syncType) {
        0 -> "APPROXIMATE"
        1 -> "CALIBRATED"
        null -> "미보고"
        else -> "알 수 없음 ($syncType)"
    }

    sealed class Result {
        data class Ready(val plan: DualPreviewPlan) : Result()
        data class Refused(val reason: DualPreviewRefusal) : Result()
    }
}

/**
 * Frame bookkeeping for one physical output. [delivered] counts frames drawn on that output's view, which is the
 * proof that the stream arrived; [metadata] counts capture results that carried this physical id, and [missing]
 * counts results that should have and did not. The two are kept apart because a HAL can deliver buffers while
 * leaving the physical metadata out.
 */
class PhysicalOutputStats(val physicalId: String) {
    var delivered = 0L
        private set
    var metadata = 0L
        private set
    var missing = 0L
        private set
    var lastSensorTimestampNs: Long? = null
        private set
    private var firstFrameMs: Long? = null
    private var lastFrameMs: Long? = null

    fun frame(nowMs: Long) {
        if (firstFrameMs == null) firstFrameMs = nowMs
        lastFrameMs = nowMs
        delivered++
    }

    fun result(sensorTimestampNs: Long?) {
        if (sensorTimestampNs == null) { missing++; return }
        metadata++
        lastSensorTimestampNs = sensorTimestampNs
    }

    /** Average delivered frame rate since the first frame; null before two frames. */
    fun fps(): Double? {
        val start = firstFrameMs ?: return null
        val end = lastFrameMs ?: return null
        if (delivered < 2 || end <= start) return null
        return (delivered - 1) * 1000.0 / (end - start)
    }
}

/** Difference between the two physical sensor timestamps of the same logical result; null when either is missing. */
fun physicalTimestampSkewNs(first: Long?, second: Long?): Long? =
    if (first == null || second == null) null else first - second
