package dev.halcamera.cts.recording

import dev.halcamera.cts.Dim
import dev.halcamera.cts.Verdict
/**
 * The decisions of CTS `RecordingTest#testBasicRecording` (platform/cts, tests/camera), separated from the
 * camera and MediaRecorder calls so they run on the JVM. [BasicRecordingRunner] feeds them what CTS reads from
 * the device and performs the recording in between.
 *
 * Constants, skip conditions and assertion messages are kept as CTS has them, so a FAIL here reads against the
 * original test. This is not CTS: the official verdict is cts-tradefed's test_result.xml.
 */
object BasicRecordingRules {
    const val SOURCE = "RecordingTest#testBasicRecording"
    const val RECORDING_DURATION_MS = 3000L
    const val DURATION_MARGIN = 0.2f
    const val FRAMEDURATION_MARGIN = 0.2f
    const val FRMDRP_RATE_TOLERANCE = 5.0f
    private const val PREVIEW_FRAME_DURATION_TOLERANCE = 0.01f

    // CamcorderProfile quality ids, in the order CTS iterates them (mCamcorderProfileList).
    const val QUALITY_LOW = 0
    const val QUALITY_HIGH = 1
    const val QUALITY_QCIF = 2
    const val QUALITY_CIF = 3
    const val QUALITY_480P = 4
    const val QUALITY_720P = 5
    const val QUALITY_1080P = 6
    const val QUALITY_QVGA = 7
    const val QUALITY_2160P = 8
    const val QUALITY_QHD = 11
    const val QUALITY_2K = 12
    val PROFILE_ORDER = listOf(QUALITY_HIGH, QUALITY_2160P, QUALITY_1080P, QUALITY_720P, QUALITY_480P, QUALITY_CIF, QUALITY_QCIF, QUALITY_QVGA, QUALITY_LOW)

    fun qualityName(id: Int): String = when (id) {
        QUALITY_LOW -> "LOW"; QUALITY_HIGH -> "HIGH"; QUALITY_QCIF -> "QCIF"; QUALITY_CIF -> "CIF"
        QUALITY_480P -> "480P"; QUALITY_720P -> "720P"; QUALITY_1080P -> "1080P"; QUALITY_QVGA -> "QVGA"
        QUALITY_2160P -> "2160P"; QUALITY_QHD -> "QHD"; QUALITY_2K -> "2K"; else -> "quality($id)"
    }

    // CameraTestUtils size bounds.
    val SIZE_BOUND_720P = Dim(1280, 720)
    val SIZE_BOUND_1080P = Dim(1920, 1088)
    val SIZE_BOUND_2K = Dim(2048, 1088)
    val SIZE_BOUND_QHD = Dim(2560, 1440)
    val SIZE_BOUND_2160P = Dim(3840, 2160)
    val PREVIEW_SIZE_BOUND = SIZE_BOUND_1080P

    /** What the CTS StaticMetadata and helpers read for one camera before any profile is tried. */
    data class CameraInfo(
        val cameraId: String,
        val hasColorOutput: Boolean,
        val isExternal: Boolean,
        val isLegacy: Boolean,
        /** SurfaceHolder sizes within the preview bound, largest first (mOrderedPreviewSizes). */
        val orderedPreviewSizes: List<Dim>,
        /** MediaRecorder sizes within the profile-derived bound (mSupportedVideoSizes). */
        val supportedVideoSizes: List<Dim>,
        val fpsRanges: List<Pair<Int, Int>>,
        /** Min frame duration of each PRIVATE output size, ns. */
        val privateMinFrameDurationNs: Map<Dim, Long>
    )

    /** One CamcorderProfile as CTS uses it. */
    data class Profile(val quality: Int, val size: Dim, val frameRate: Int)

    /** Recording-independent outcome for one quality id: run it with [preview], or a verdict already decided. */
    data class ProfilePlan(
        val quality: Int,
        val profile: Profile?,
        val preview: Dim?,
        val verdict: Verdict?,
        val details: List<String>
    )

    /** A recorded file as MediaExtractor reports it, plus the capture-result count from the session. */
    data class Recording(
        val fileExists: Boolean,
        val recordedSize: Dim?,
        val durationUs: Long,
        val sampleTimesUs: List<Long>,
        val framesProduced: Long
    )

    /** CameraTestUtils.getPreviewSizeBound: 1080p unless the window is smaller, then the window (landscape). */
    fun previewSizeBound(windowWidth: Int, windowHeight: Int): Dim {
        val long = maxOf(windowWidth, windowHeight)
        val short = minOf(windowWidth, windowHeight)
        return if (PREVIEW_SIZE_BOUND.width <= long && PREVIEW_SIZE_BOUND.height <= short) PREVIEW_SIZE_BOUND else Dim(long, short)
    }

    /** RecordingTest.initSupportedVideoSize: the bound follows the largest quality the camera has a profile for. */
    fun videoSizeBound(presentQualities: Set<Int>): Dim = when {
        QUALITY_2160P in presentQualities -> SIZE_BOUND_2160P
        QUALITY_QHD in presentQualities -> SIZE_BOUND_QHD
        QUALITY_2K in presentQualities -> SIZE_BOUND_2K
        QUALITY_1080P in presentQualities -> SIZE_BOUND_1080P
        else -> SIZE_BOUND_720P
    }

    /** Sizes within [bound] on both edges, largest area first (CameraTestUtils sorting). */
    fun boundedDescending(sizes: List<Dim>, bound: Dim): List<Dim> =
        sizes.filter { it.width <= bound.width && it.height <= bound.height }.sortedWith(compareByDescending<Dim> { it.area }.thenByDescending { it.width })

    /** doBasicRecording: the camera is skipped before anything is opened. Null means run. */
    fun cameraSkipReason(info: CameraInfo): String? = when {
        !info.hasColorOutput -> "Camera ${info.cameraId} does not support color outputs, skipping"
        info.isExternal -> "Camera ${info.cameraId} does not support CamcorderProfile, skipping"
        else -> null
    }

    /** RecordingTest.allowedUnsupported: LEGACY may lack the 1080p-and-up qualities. */
    fun allowedUnsupported(info: CameraInfo, profile: Profile): Boolean {
        if (!info.isLegacy) return false
        return when (profile.quality) {
            QUALITY_2160P, QUALITY_1080P, QUALITY_HIGH -> profile.size.width >= 1080
            else -> false
        }
    }

    /**
     * basicRecordingTestByCamera, per quality id. [profiles] holds the qualities CamcorderProfile.hasProfile
     * answered yes for. The two assertions before the recording become a FAIL plan; the CTS `continue`s become
     * SKIP plans; everything else is a recording to perform with the chosen preview size.
     */
    fun plan(info: CameraInfo, profiles: Map<Int, Profile>): List<ProfilePlan> {
        val maxPreview = info.orderedPreviewSizes.firstOrNull()
        return PROFILE_ORDER.map { quality ->
            val profile = profiles[quality]
                ?: return@map ProfilePlan(quality, null, null, Verdict.SKIP, listOf("CamcorderProfile ${qualityName(quality)} is not present"))
            val fps = profile.frameRate to profile.frameRate
            if (allowedUnsupported(info, profile))
                return@map ProfilePlan(quality, profile, null, Verdict.SKIP, listOf("LEGACY device may not support ${qualityName(quality)} (${profile.size})"))
            if (info.isLegacy && maxPreview != null && (profile.size.width > maxPreview.width || profile.size.height > maxPreview.height))
                return@map ProfilePlan(quality, profile, null, Verdict.SKIP, listOf("Legacy mode can only do recording up to max preview size $maxPreview"))
            val failures = ArrayList<String>()
            if (profile.size !in info.supportedVideoSizes)
                failures += "Video size ${profile.size} for profile ID $quality must be one of the camera device supported video size!"
            if (fps !in info.fpsRanges)
                failures += "Frame rate range [${fps.first}, ${fps.second}] (for profile ID $quality) must be one of the camera device available FPS range!"
            if (failures.isNotEmpty()) return@map ProfilePlan(quality, profile, null, Verdict.FAIL, failures)
            ProfilePlan(quality, profile, previewSizeForVideo(info, profile.size, profile.frameRate), null, emptyList())
        }
    }

    /**
     * basicRecordingTestByCamera's closing assertion. CTS takes the max over every present profile, before any
     * skip, so a camera whose only profiles are all skipped is still held to it.
     */
    fun frameRateFloorFailure(profiles: Map<Int, Profile>): String? {
        val max = profiles.values.maxOfOrNull { it.frameRate } ?: return null
        return if (max >= 24) null else "At least one CamcorderProfile must support >= 24 FPS"
    }

    /**
     * getPreviewSizesForVideo().get(0): the video size itself unless it exceeds the largest preview size, in
     * which case the largest preview size that fits inside the video size and keeps the video frame rate.
     */
    fun previewSizeForVideo(info: CameraInfo, videoSize: Dim, videoFrameRate: Int): Dim {
        val maxPreview = info.orderedPreviewSizes.firstOrNull() ?: return videoSize
        if (videoSize.width <= maxPreview.width && videoSize.height <= maxPreview.height) return videoSize
        val videoFrameDuration = (1e9 / videoFrameRate * (1.0 + PREVIEW_FRAME_DURATION_TOLERANCE)).toLong()
        return info.orderedPreviewSizes.firstOrNull { s ->
            val frameDuration = if (info.isLegacy) 0L else info.privateMinFrameDurationNs[s] ?: Long.MAX_VALUE
            frameDuration <= videoFrameDuration && s.width <= videoSize.width && s.height <= videoSize.height
        } ?: videoSize
    }

    /** completeBasicRecording: the frames the session completed, converted to a duration at the profile rate. */
    fun expectedDurationMs(framesProduced: Long, frameRate: Int): Float = framesProduced * (1000.0f / frameRate)

    /**
     * validateRecording with fixed-fps arguments. Returns the assertion messages that would have failed, in CTS
     * order; the first failure in CTS stops the test, so only the first entry is what CTS would print.
     * [frameDropTolerance] is the rate in percent; testVideoSnapshot passes its own.
     */
    fun validate(cameraId: String, isLegacy: Boolean, profile: Profile, rec: Recording, frameDropTolerance: Float = FRMDRP_RATE_TOLERANCE): List<String> {
        val sz = profile.size
        if (!rec.fileExists) return listOf("No video is recorded")
        val recorded = rec.recordedSize ?: return listOf("Cannot find video track!")
        if (recorded != sz) return listOf("Video size doesn't match, expected $sz got $recorded")
        if (isLegacy) return emptyList()

        val expectedFrameDurationMs = 1000.0f / profile.frameRate
        val expectedDurationMs = expectedDurationMs(rec.framesProduced, profile.frameRate)
        val duration = (rec.durationUs / 1000).toFloat()
        val minMs = expectedDurationMs * (1f - DURATION_MARGIN)
        val maxMs = expectedDurationMs * (1f + DURATION_MARGIN)
        if (!(duration > minMs && duration < maxMs))
            return listOf("Camera $cameraId: Video duration doesn't match: recorded ${duration}ms, expected [$minMs,$maxMs]ms.")

        val maxFrameDuration = expectedFrameDurationMs * (1.0f + FRAMEDURATION_MARGIN)
        val timestamps = rec.sampleTimesUs.sorted()
        if (timestamps.isEmpty()) return listOf("Camera $cameraId: Video has no samples")
        var frameDropCount = 0
        var prev = timestamps[0]
        for (i in 1 until timestamps.size) {
            val frameDurationMs = (timestamps[i] - prev).toFloat() / 1000
            if (frameDurationMs > maxFrameDuration) frameDropCount++
            prev = timestamps[i]
        }
        val frameDropRate = 100f * frameDropCount / timestamps.size
        if (!(frameDropRate < frameDropTolerance))
            return listOf("Camera $cameraId: Video frame drop rate too high: $frameDropRate%, tolerance $frameDropTolerance%. " +
                "Video size: $sz, expectedDuration [$expectedDurationMs,$expectedDurationMs], expectedFrameDuration $expectedFrameDurationMs, " +
                "frameDropCnt $frameDropCount, frameCount ${timestamps.size}")
        return emptyList()
    }

    /** The numbers a PASS row should still show, so a borderline pass is visible without re-running. */
    fun summary(profile: Profile, rec: Recording): String {
        val timestamps = rec.sampleTimesUs.sorted()
        val expectedFrameDurationMs = 1000.0f / profile.frameRate
        val maxFrameDuration = expectedFrameDurationMs * (1.0f + FRAMEDURATION_MARGIN)
        var drops = 0
        for (i in 1 until timestamps.size) if ((timestamps[i] - timestamps[i - 1]).toFloat() / 1000 > maxFrameDuration) drops++
        val rate = if (timestamps.isEmpty()) 0f else 100f * drops / timestamps.size
        return "${profile.size}@${profile.frameRate}fps · duration ${rec.durationUs / 1000}ms (expected ${expectedDurationMs(rec.framesProduced, profile.frameRate).toInt()}ms from ${rec.framesProduced} results) · frames ${timestamps.size} · drops $drops (${"%.2f".format(rate)}%)"
    }
}
