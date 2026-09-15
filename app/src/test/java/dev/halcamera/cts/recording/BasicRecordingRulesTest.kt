package dev.halcamera.cts.recording

import dev.halcamera.cts.Dim
import dev.halcamera.cts.Verdict
import dev.halcamera.cts.recording.BasicRecordingRules.Profile
import dev.halcamera.cts.recording.BasicRecordingRules.QUALITY_1080P
import dev.halcamera.cts.recording.BasicRecordingRules.QUALITY_2160P
import dev.halcamera.cts.recording.BasicRecordingRules.QUALITY_480P
import dev.halcamera.cts.recording.BasicRecordingRules.QUALITY_720P
import dev.halcamera.cts.recording.BasicRecordingRules.QUALITY_HIGH
import dev.halcamera.cts.recording.BasicRecordingRules.QUALITY_LOW
import dev.halcamera.cts.recording.BasicRecordingRules.QUALITY_QCIF
import dev.halcamera.cts.recording.BasicRecordingRules.QUALITY_QHD
import dev.halcamera.cts.recording.BasicRecordingRules.Recording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcribed decisions of RecordingTest#testBasicRecording against a fixture modelled on a FULL phone
 * camera, then against the one deviation each rule exists to catch. Recording itself needs a device.
 */
class BasicRecordingRulesTest {

    private val fhd = Dim(1920, 1080)
    private val uhd = Dim(3840, 2160)
    private val hd = Dim(1280, 720)
    private val sd = Dim(720, 480)
    private val qcif = Dim(176, 144)

    private fun info(
        legacy: Boolean = false,
        external: Boolean = false,
        color: Boolean = true,
        previewSizes: List<Dim> = listOf(fhd, Dim(1440, 1080), hd, sd, qcif),
        videoSizes: List<Dim> = listOf(uhd, fhd, hd, sd, qcif),
        fps: List<Pair<Int, Int>> = listOf(15 to 30, 30 to 30, 60 to 60),
        privateDurations: Map<Dim, Long> = previewSizes.associateWith { 33_333_333L }
    ) = BasicRecordingRules.CameraInfo("0", color, external, legacy, previewSizes, videoSizes, fps, privateDurations)

    private val profiles = mapOf(
        QUALITY_HIGH to Profile(QUALITY_HIGH, uhd, 30),
        QUALITY_2160P to Profile(QUALITY_2160P, uhd, 30),
        QUALITY_1080P to Profile(QUALITY_1080P, fhd, 30),
        QUALITY_720P to Profile(QUALITY_720P, hd, 30),
        QUALITY_480P to Profile(QUALITY_480P, sd, 30),
        QUALITY_QCIF to Profile(QUALITY_QCIF, qcif, 30),
        QUALITY_LOW to Profile(QUALITY_LOW, qcif, 30)
    )

    /** A clean 3 s recording at [fps]: one sample every frame period, the container duration matching. */
    private fun recording(size: Dim = fhd, fps: Int = 30, frames: Int = 90, dropEvery: Int = 0, durationScale: Float = 1f): Recording {
        val period = 1_000_000L / fps
        val times = ArrayList<Long>()
        var t = 0L
        for (i in 0 until frames) {
            times += t
            t += if (dropEvery > 0 && i % dropEvery == dropEvery - 1) period * 2 else period
        }
        val durationUs = (frames * period * durationScale).toLong()
        return Recording(fileExists = true, recordedSize = size, durationUs = durationUs, sampleTimesUs = times, framesProduced = frames.toLong())
    }

    @Test
    fun `preview bound is 1080p unless the window is smaller`() {
        assertEquals(Dim(1920, 1088), BasicRecordingRules.previewSizeBound(1440, 3120))
        assertEquals(Dim(1920, 1088), BasicRecordingRules.previewSizeBound(3120, 1440))
        assertEquals(Dim(1600, 720), BasicRecordingRules.previewSizeBound(720, 1600))
    }

    @Test
    fun `video size bound follows the largest quality present`() {
        assertEquals(BasicRecordingRules.SIZE_BOUND_2160P, BasicRecordingRules.videoSizeBound(setOf(QUALITY_2160P, QUALITY_1080P)))
        assertEquals(BasicRecordingRules.SIZE_BOUND_QHD, BasicRecordingRules.videoSizeBound(setOf(QUALITY_QHD, QUALITY_1080P)))
        assertEquals(BasicRecordingRules.SIZE_BOUND_1080P, BasicRecordingRules.videoSizeBound(setOf(QUALITY_1080P, QUALITY_720P)))
        assertEquals(BasicRecordingRules.SIZE_BOUND_720P, BasicRecordingRules.videoSizeBound(setOf(QUALITY_720P)))
    }

    @Test
    fun `bounded sizes are filtered on both edges and sorted largest first`() {
        val sizes = listOf(qcif, uhd, Dim(1920, 1088), fhd, Dim(2048, 1080), hd)
        assertEquals(listOf(Dim(1920, 1088), fhd, hd, qcif), BasicRecordingRules.boundedDescending(sizes, BasicRecordingRules.SIZE_BOUND_1080P))
    }

    @Test
    fun `cameras without colour output or with EXTERNAL level are skipped before opening`() {
        assertNull(BasicRecordingRules.cameraSkipReason(info()))
        assertEquals("Camera 0 does not support color outputs, skipping", BasicRecordingRules.cameraSkipReason(info(color = false)))
        assertEquals("Camera 0 does not support CamcorderProfile, skipping", BasicRecordingRules.cameraSkipReason(info(external = true)))
    }

    @Test
    fun `plan runs every present profile in CTS order with the video size as preview`() {
        val plans = BasicRecordingRules.plan(info(), profiles)
        assertEquals(BasicRecordingRules.PROFILE_ORDER, plans.map { it.quality })
        val run = plans.filter { it.verdict == null }
        assertEquals(listOf(QUALITY_HIGH, QUALITY_2160P, QUALITY_1080P, QUALITY_720P, QUALITY_480P, QUALITY_QCIF, QUALITY_LOW), run.map { it.quality })
        // 2160p exceeds the largest preview (1080p): the largest preview that fits and keeps 30 fps is chosen.
        assertEquals(fhd, run.first { it.quality == QUALITY_2160P }.preview)
        assertEquals(hd, run.first { it.quality == QUALITY_720P }.preview)
        val absent = plans.filter { it.verdict == Verdict.SKIP }
        assertEquals(listOf(BasicRecordingRules.QUALITY_CIF, BasicRecordingRules.QUALITY_QVGA), absent.map { it.quality })
    }

    @Test
    fun `a profile size the camera does not list for MediaRecorder fails before recording`() {
        val plans = BasicRecordingRules.plan(info(videoSizes = listOf(fhd, hd, sd, qcif)), profiles)
        val high = plans.first { it.quality == QUALITY_HIGH }
        assertEquals(Verdict.FAIL, high.verdict)
        assertEquals("Video size 3840x2160 for profile ID 1 must be one of the camera device supported video size!", high.details.single())
        assertNull(plans.first { it.quality == QUALITY_1080P }.verdict)
    }

    @Test
    fun `a profile frame rate without a fixed fps range fails before recording`() {
        val plans = BasicRecordingRules.plan(info(fps = listOf(15 to 30)), profiles)
        assertTrue(plans.filter { it.profile != null }.all { it.verdict == Verdict.FAIL })
        assertEquals("Frame rate range [30, 30] (for profile ID 6) must be one of the camera device available FPS range!", plans.first { it.quality == QUALITY_1080P }.details.single())
    }

    @Test
    fun `LEGACY skips 1080p-and-up qualities and sizes above the largest preview`() {
        val legacy = info(legacy = true, previewSizes = listOf(hd, sd, qcif))
        val plans = BasicRecordingRules.plan(legacy, profiles)
        assertEquals(Verdict.SKIP, plans.first { it.quality == QUALITY_HIGH }.verdict)
        assertEquals(Verdict.SKIP, plans.first { it.quality == QUALITY_2160P }.verdict)
        assertEquals(Verdict.SKIP, plans.first { it.quality == QUALITY_1080P }.verdict)
        assertNull(plans.first { it.quality == QUALITY_720P }.verdict)
        // A LEGACY 1080P profile narrower than 1080 is not excused and must then fit the preview.
        val narrow = profiles + (QUALITY_1080P to Profile(QUALITY_1080P, Dim(1024, 768), 30))
        assertEquals(listOf("Legacy mode can only do recording up to max preview size 1280x720"), BasicRecordingRules.plan(legacy, narrow).first { it.quality == QUALITY_1080P }.details)
    }

    @Test
    fun `at least one profile must reach 24 fps, counted before any skip`() {
        assertNull(BasicRecordingRules.frameRateFloorFailure(profiles))
        assertNull(BasicRecordingRules.frameRateFloorFailure(emptyMap()))
        val slow = profiles.mapValues { it.value.copy(frameRate = 15) }
        assertEquals("At least one CamcorderProfile must support >= 24 FPS", BasicRecordingRules.frameRateFloorFailure(slow))
    }

    @Test
    fun `preview for a video larger than the preview bound needs a fast enough PRIVATE size`() {
        val slow1080 = info(privateDurations = mapOf(fhd to 50_000_000L, Dim(1440, 1080) to 33_333_333L, hd to 33_333_333L))
        assertEquals(Dim(1440, 1080), BasicRecordingRules.previewSizeForVideo(slow1080, uhd, 30))
        // LEGACY reports no durations and takes the largest fitting size.
        assertEquals(fhd, BasicRecordingRules.previewSizeForVideo(info(legacy = true, privateDurations = emptyMap()), uhd, 30))
        // Nothing fits: fall back to the video size itself.
        assertEquals(uhd, BasicRecordingRules.previewSizeForVideo(info(privateDurations = emptyMap()), uhd, 30))
    }

    @Test
    fun `a clean recording passes`() {
        assertEquals(emptyList<String>(), BasicRecordingRules.validate("0", false, Profile(QUALITY_1080P, fhd, 30), recording()))
    }

    @Test
    fun `missing file, missing track and size mismatch fail in CTS order`() {
        val p = Profile(QUALITY_1080P, fhd, 30)
        assertEquals(listOf("No video is recorded"), BasicRecordingRules.validate("0", false, p, recording().copy(fileExists = false)))
        assertEquals(listOf("Cannot find video track!"), BasicRecordingRules.validate("0", false, p, recording().copy(recordedSize = null)))
        assertEquals(listOf("Video size doesn't match, expected 1920x1080 got 1280x720"), BasicRecordingRules.validate("0", false, p, recording(size = hd)))
    }

    @Test
    fun `LEGACY stops after the size check`() {
        val p = Profile(QUALITY_720P, hd, 30)
        assertEquals(emptyList<String>(), BasicRecordingRules.validate("0", true, p, recording(size = hd, dropEvery = 2, durationScale = 0.5f)))
    }

    @Test
    fun `duration must sit within 20 percent of the frames the session produced`() {
        val p = Profile(QUALITY_1080P, fhd, 30)
        assertEquals(emptyList<String>(), BasicRecordingRules.validate("0", false, p, recording(durationScale = 0.85f)))
        val short = BasicRecordingRules.validate("0", false, p, recording(durationScale = 0.7f))
        assertEquals(1, short.size)
        assertTrue(short[0].startsWith("Camera 0: Video duration doesn't match: recorded 2099.0ms, expected [2400.0,3600.0"))
        // The expectation follows the capture results, not the wall clock: fewer results, shorter expectation.
        assertEquals(emptyList<String>(), BasicRecordingRules.validate("0", false, p, recording(durationScale = 0.7f).copy(framesProduced = 63)))
    }

    @Test
    fun `frame drop rate must stay under 5 percent`() {
        val p = Profile(QUALITY_1080P, fhd, 30)
        // One doubled interval every 25 frames: 3 of 90 intervals = 3.3 %.
        assertEquals(emptyList<String>(), BasicRecordingRules.validate("0", false, p, recording(dropEvery = 25, durationScale = 1.03f)))
        // One every 10 frames, the last doubled step having no following sample: 8 of 90 = 8.9 %.
        val failures = BasicRecordingRules.validate("0", false, p, recording(dropEvery = 10, durationScale = 1.1f))
        assertEquals(1, failures.size)
        assertTrue(failures[0].startsWith("Camera 0: Video frame drop rate too high: 8.888889%, tolerance 5.0%."))
        assertTrue(failures[0].endsWith("frameDropCnt 8, frameCount 90"))
    }

    @Test
    fun `a 20 percent longer interval is not a drop, 21 percent is`() {
        val p = Profile(QUALITY_1080P, fhd, 30)
        val base = recording()
        fun withOneInterval(ms: Float): Recording {
            val times = base.sampleTimesUs.toMutableList()
            for (i in 1 until times.size) times[i] = times[i - 1] + if (i == 1) (ms * 1000).toLong() else 33_333L
            return base.copy(sampleTimesUs = times)
        }
        val at20 = BasicRecordingRules.validate("0", false, p, withOneInterval(33.333f * 1.2f))
        val at21 = BasicRecordingRules.validate("0", false, p, withOneInterval(33.333f * 1.21f))
        assertEquals(emptyList<String>(), at20)
        assertEquals(emptyList<String>(), at21) // one drop in 90 is 1.1 %, under tolerance either way
        assertTrue(BasicRecordingRules.summary(p, withOneInterval(33.333f * 1.21f)).contains("drops 1 "))
        assertTrue(BasicRecordingRules.summary(p, withOneInterval(33.333f * 1.2f)).contains("drops 0 "))
    }

    @Test
    fun `summary reports size, durations, frames and drop rate`() {
        val s = BasicRecordingRules.summary(Profile(QUALITY_1080P, fhd, 30), recording(dropEvery = 10, durationScale = 1.1f))
        assertEquals("1920x1080@30fps · duration 3299ms (expected 3000ms from 90 results) · frames 90 · drops 8 (8.89%)", s)
    }
}
