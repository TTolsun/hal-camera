package dev.halcamera.cts.switching

import dev.halcamera.cts.Dim
import dev.halcamera.cts.OpenCycle
import dev.halcamera.cts.Verdict
import dev.halcamera.cts.recording.BasicRecordingRules.Profile
import dev.halcamera.cts.recording.BasicRecordingRules.QUALITY_1080P
import dev.halcamera.cts.recording.BasicRecordingRules.QUALITY_480P
import dev.halcamera.cts.recording.BasicRecordingRules.QUALITY_HIGH
import dev.halcamera.cts.recording.BasicRecordingRules.Recording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchingRulesTest {
    private val fhd = Dim(1920, 1080)
    private val profile = Profile(QUALITY_1080P, fhd, 30)

    private fun recording(size: Dim? = fhd, durationMs: Long = 3050, exists: Boolean = true, frames: Int = 90) =
        Recording(exists, size, durationMs * 1000, List(frames) { it * 33_333L }, frames.toLong())

    @Test
    fun `order visits every camera in turn, rounds times`() {
        val visits = SwitchingRules.order(listOf("0", "1", "3"), 2)
        assertEquals(listOf(1 to "0", 1 to "1", 1 to "3", 2 to "0", 2 to "1", 2 to "3"), visits.map { it.round to it.cameraId })
        assertEquals(emptyList<SwitchingRules.Visit>(), SwitchingRules.order(emptyList(), 5))
        assertEquals("round_3", SwitchingRules.visitStepId(3))
    }

    @Test
    fun `a visit passes when the pass reached a frame and closed in time`() {
        assertEquals(Verdict.PASS to listOf("open 40.0 ms · configure 30.0 ms · first frame 120.0 ms · close 20.0 ms"), SwitchingRules.judgeVisit(OpenCycle(40.0, 30.0, 120.0, 20.0)))
        val (verdict, details) = SwitchingRules.judgeVisit(OpenCycle(openMs = 40.0, error = "IllegalStateException: Timeout waiting for the session to configure"))
        assertEquals(Verdict.FAIL, verdict)
        assertEquals(listOf("open 40.0 ms", "IllegalStateException: Timeout waiting for the session to configure"), details)
    }

    @Test
    fun `the recording profile is the first present in CTS order`() {
        val high = Profile(QUALITY_HIGH, Dim(3840, 2160), 30)
        assertEquals(high, SwitchingRules.recordingProfile(mapOf(QUALITY_480P to Profile(QUALITY_480P, Dim(720, 480), 30), QUALITY_HIGH to high)))
        assertEquals(profile, SwitchingRules.recordingProfile(mapOf(QUALITY_1080P to profile)))
        assertNull(SwitchingRules.recordingProfile(emptyMap()))
    }

    @Test
    fun `recording is skipped without a numeric id or a profile`() {
        assertEquals("CamcorderProfile needs a numeric camera id", SwitchingRules.recordingSkipReason("abc", false, profile))
        assertEquals("Camera 4 has no CamcorderProfile", SwitchingRules.recordingSkipReason("4", true, null))
        assertNull(SwitchingRules.recordingSkipReason("0", true, profile))
    }

    @Test
    fun `validateRecording checks the file, the track, the size and the length`() {
        assertEquals(emptyList<String>(), SwitchingRules.validateRecording("0", profile, recording()))
        assertEquals(listOf("No video is recorded"), SwitchingRules.validateRecording("0", profile, recording(exists = false)))
        assertEquals(listOf("Cannot find video track!"), SwitchingRules.validateRecording("0", profile, recording(size = null)))
        assertEquals(listOf("Video size doesn't match, expected 1920x1080 got 1280x720"), SwitchingRules.validateRecording("0", profile, recording(size = Dim(1280, 720))))
        val short = SwitchingRules.validateRecording("0", profile, recording(durationMs = 1000)).single()
        assertTrue(short, short.startsWith("Camera 0: Video duration doesn't match: recorded 1000.0ms, expected [2400.0,3600."))
    }

    @Test
    fun `the recording summary names the profile and the counts`() {
        assertEquals("1080P 1920x1080@30fps · duration 3050ms · frames 90 · results 90", SwitchingRules.recordingSummary(profile, recording()))
    }
}
