package dev.halcamera.cts.snapshot

import dev.halcamera.cts.Dim
import dev.halcamera.cts.Verdict
import dev.halcamera.cts.combination.StillPreviewCombinationRules.ImageInfo
import dev.halcamera.cts.combination.StillPreviewCombinationRules.JPEG
import dev.halcamera.cts.recording.BasicRecordingRules
import dev.halcamera.cts.recording.BasicRecordingRules.Profile
import dev.halcamera.cts.recording.BasicRecordingRules.QUALITY_1080P
import dev.halcamera.cts.recording.BasicRecordingRules.QUALITY_2160P
import dev.halcamera.cts.recording.BasicRecordingRules.QUALITY_480P
import dev.halcamera.cts.recording.BasicRecordingRules.QUALITY_HIGH
import dev.halcamera.cts.recording.BasicRecordingRules.Recording
import dev.halcamera.cts.snapshot.VideoSnapshotRules.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class VideoSnapshotRulesTest {
    private val fhd = Dim(1920, 1080)
    private val uhd = Dim(3840, 2160)
    private val hd = Dim(1280, 720)
    private val sd = Dim(720, 480)
    private val stills = listOf(Dim(4000, 3000), Dim(3840, 2160), Dim(1920, 1080), Dim(640, 480))

    private fun info(legacy: Boolean = false, videoSizes: List<Dim> = listOf(uhd, fhd, hd, sd), fps: List<Pair<Int, Int>> = listOf(30 to 30)) =
        BasicRecordingRules.CameraInfo("0", true, false, legacy, listOf(fhd, hd, sd), videoSizes, fps, mapOf(fhd to 33_333_333L, hd to 33_333_333L, sd to 33_333_333L))

    private val profiles = mapOf(
        QUALITY_HIGH to Profile(QUALITY_HIGH, uhd, 30),
        QUALITY_2160P to Profile(QUALITY_2160P, uhd, 30),
        QUALITY_1080P to Profile(QUALITY_1080P, fhd, 30),
        QUALITY_480P to Profile(QUALITY_480P, sd, 30)
    )

    /** A clean recording at [fps] for [seconds]: one sample per frame period, the container duration matching. */
    private fun recording(size: Dim = uhd, fps: Int = 30, seconds: Int = 25, dropEvery: Int = 0): Recording {
        val frames = fps * seconds
        val period = 1_000_000L / fps
        val times = ArrayList<Long>()
        var t = 0L
        for (i in 0 until frames) { times += t; t += if (dropEvery > 0 && i % dropEvery == dropEvery - 1) period * 2 else period }
        return Recording(true, size, t, times, frames.toLong())
    }

    @Test
    fun `the profile is the first testBasicRecording would record`() {
        assertEquals(QUALITY_HIGH, VideoSnapshotRules.chooseProfile(info(), profiles)?.quality)
        // HIGH and 2160P are not supported video sizes here, so 1080P is the first recordable plan.
        assertEquals(QUALITY_1080P, VideoSnapshotRules.chooseProfile(info(videoSizes = listOf(fhd, hd, sd)), profiles)?.quality)
        assertNull(VideoSnapshotRules.chooseProfile(info(), emptyMap()))
    }

    @Test
    fun `no profile reason carries the first plan's own explanation`() {
        val lines = VideoSnapshotRules.noProfileReason(info(fps = listOf(15 to 15)), profiles)
        assertEquals("No CamcorderProfile can be recorded on camera 0", lines[0])
        assertEquals("Frame rate range [30, 30] (for profile ID 1) must be one of the camera device available FPS range!", lines[1])
        assertEquals(listOf("No CamcorderProfile can be recorded on camera 0", "CamcorderProfile HIGH is not present"), VideoSnapshotRules.noProfileReason(info(), emptyMap()))
    }

    @Test
    fun `snapshot size is the largest JPEG, or within the video on LEGACY`() {
        assertEquals(Dim(4000, 3000), VideoSnapshotRules.snapshotSize(false, fhd, stills))
        assertEquals(Dim(1920, 1080), VideoSnapshotRules.snapshotSize(true, fhd, stills))
        assertEquals(Dim(640, 480), VideoSnapshotRules.snapshotSize(true, Dim(320, 240), stills))
        assertNull(VideoSnapshotRules.snapshotSize(false, fhd, emptyList()))
    }

    @Test
    fun `the snapshot moment stays inside the window and follows the random source`() {
        assertEquals(VideoSnapshotRules.SNAPSHOT_EARLIEST_MS, VideoSnapshotRules.snapshotAtMs(object : Random() { override fun nextDouble() = 0.0 }))
        assertEquals(VideoSnapshotRules.SNAPSHOT_LATEST_MS, VideoSnapshotRules.snapshotAtMs(object : Random() { override fun nextDouble() = 1.0 }))
        repeat(50) {
            val at = VideoSnapshotRules.snapshotAtMs(Random(it.toLong()))
            assertTrue("$at", at in VideoSnapshotRules.SNAPSHOT_EARLIEST_MS..VideoSnapshotRules.SNAPSHOT_LATEST_MS)
        }
    }

    @Test
    fun `frame drop tolerance widens above 15 megapixels`() {
        assertEquals(8f, VideoSnapshotRules.frameDropTolerance(Dim(4000, 3000)))
        assertEquals(12f, VideoSnapshotRules.frameDropTolerance(Dim(5000, 4000)))
    }

    @Test
    fun `the video row uses validateRecording with the snapshot tolerance`() {
        val profile = profiles.getValue(QUALITY_HIGH)
        val (verdict, details) = VideoSnapshotRules.judgeVideo("0", false, profile, recording(), Dim(4000, 3000))
        assertEquals(Verdict.PASS, verdict)
        assertTrue(details[0], details[0].startsWith("3840x2160@30fps · duration 2499"))
        // One drop in every 15 frames is 6.7 %: over testBasicRecording's 5 % but under the snapshot test's 8 %.
        assertEquals(Verdict.PASS, VideoSnapshotRules.judgeVideo("0", false, profile, recording(dropEvery = 15), Dim(4000, 3000)).first)
        val bad = VideoSnapshotRules.judgeVideo("0", false, profile, recording(dropEvery = 10), Dim(4000, 3000))
        assertEquals(Verdict.FAIL, bad.first)
        assertTrue(bad.second[1], bad.second[1].contains("tolerance 8.0%"))
    }

    @Test
    fun `the snapshot row passes with a sound JPEG and names what went wrong otherwise`() {
        val size = Dim(4000, 3000)
        val ok = Snapshot(12_345, requestedAtMs = 12_350.0, captureMs = 610.0, resultReceived = true, image = ImageInfo(4000, 3000, JPEG, 3_000_000, size))
        assertEquals(Verdict.PASS to listOf("4000x3000 planned at 12345 ms · requested at 12350.0 ms · jpeg after 610.0 ms · jpeg 2929 KB"), VideoSnapshotRules.judgeSnapshot(size, ok))
        val never = VideoSnapshotRules.judgeSnapshot(size, Snapshot(12_345))
        assertEquals(Verdict.FAIL to listOf("4000x3000 planned at 12345 ms", "Video snapshot was never requested"), never)
        val late = VideoSnapshotRules.judgeSnapshot(size, ok.copy(resultReceived = false, image = null, captureMs = null))
        assertEquals(listOf("Timeout waiting for the video snapshot result", "Unable to get the image after capture"), late.second.drop(1))
        val error = VideoSnapshotRules.judgeSnapshot(size, Snapshot(12_345, requestedAtMs = 12_350.0, error = "IllegalStateException: Capture failed: reason 0, frame 400"))
        assertEquals(Verdict.FAIL to listOf("4000x3000 planned at 12345 ms · requested at 12350.0 ms", "IllegalStateException: Capture failed: reason 0, frame 400"), error)
    }
}
