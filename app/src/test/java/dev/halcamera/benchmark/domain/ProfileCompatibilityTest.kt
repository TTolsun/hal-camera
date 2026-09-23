package dev.halcamera.benchmark.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static preflight verdicts (docs/PLAN-BenchMarker-v0.3.md 3.6). The API 35 exact query needs a device and is
 * covered by the M2 on-device checklist instead.
 */
class ProfileCompatibilityTest {

    private val profile = BenchmarkProfile.CAMERA2_STANDARD_V1
    private val recording = BenchmarkProfile.CAMERA2_STANDARD_V2

    private fun inputs(
        previewSizes: List<String> = listOf("1920x1080", "1280x720"),
        yuvSizes: List<String> = listOf("1920x1080", "640x480"),
        jpegSizes: List<String> = listOf("4000x3000", "1920x1080"),
        recordSizes: List<String> = listOf("1920x1080", "1280x720"),
        fpsRanges: List<String> = listOf("[10, 30]", "[30, 30]"),
        displayWidth: Int? = 1440,
        displayHeight: Int? = 3120,
        previewMin: Long? = 33_333_333L,
        yuvMin: Long? = 33_333_333L
    ) = ProfileCompatibility.Inputs(
        previewSizes, yuvSizes, jpegSizes, recordSizes, fpsRanges, hardwareLevel = 3,
        displayWidth = displayWidth, displayHeight = displayHeight,
        previewMinFrameDurationNs = previewMin, yuvMinFrameDurationNs = yuvMin
    )

    @Test
    fun `a device that offers every stream is supported`() {
        val c = ProfileCompatibility.evaluateStatic(profile, inputs())
        assertTrue(c.supported)
        assertEquals(emptyList<String>(), c.reasons)
        assertEquals(ProfileCompatibility.METHOD_STATIC_TABLE, c.method)
        assertEquals(true, c.frameBudgetOk)
    }

    @Test
    fun `the fps range is compared without depending on Range toString spacing`() {
        assertTrue(ProfileCompatibility.evaluateStatic(profile, inputs(fpsRanges = listOf("[30,30]"))).supported)
        assertTrue(ProfileCompatibility.evaluateStatic(profile, inputs(fpsRanges = listOf("[30, 30]"))).supported)
        val missing = ProfileCompatibility.evaluateStatic(profile, inputs(fpsRanges = listOf("[15, 30]")))
        assertFalse(missing.supported)
        assertEquals(listOf(ProfileCompatibility.REASON_FPS_RANGE), missing.reasons)
    }

    @Test
    fun `each missing stream size names its own reason code`() {
        assertEquals(
            listOf(ProfileCompatibility.REASON_PREVIEW_SIZE),
            ProfileCompatibility.evaluateStatic(profile, inputs(previewSizes = listOf("1280x720"))).reasons
        )
        assertEquals(
            listOf(ProfileCompatibility.REASON_YUV_SIZE),
            ProfileCompatibility.evaluateStatic(profile, inputs(yuvSizes = listOf("640x480"))).reasons
        )
        assertEquals(
            listOf(ProfileCompatibility.REASON_JPEG_SIZE),
            ProfileCompatibility.evaluateStatic(profile, inputs(jpegSizes = listOf("4000x3000"))).reasons
        )
    }

    @Test
    fun `every failing check is reported, not just the first`() {
        val c = ProfileCompatibility.evaluateStatic(
            profile, inputs(previewSizes = listOf("1280x720"), yuvSizes = listOf("640x480"), fpsRanges = listOf("[15, 30]"))
        )
        assertFalse(c.supported)
        assertEquals(
            listOf(
                ProfileCompatibility.REASON_PREVIEW_SIZE,
                ProfileCompatibility.REASON_YUV_SIZE,
                ProfileCompatibility.REASON_FPS_RANGE
            ),
            c.reasons
        )
    }

    @Test
    fun `a display below 1080p leaves the PREVIEW grade too small for the combination`() {
        val c = ProfileCompatibility.evaluateStatic(profile, inputs(displayWidth = 720, displayHeight = 1280))
        assertFalse(c.supported)
        assertEquals(listOf(ProfileCompatibility.REASON_STREAM_COMBINATION), c.reasons)
    }

    @Test
    fun `an unknown display size leaves the verdict to the size lists`() {
        assertTrue(ProfileCompatibility.evaluateStatic(profile, inputs(displayWidth = null, displayHeight = null)).supported)
    }

    @Test
    fun `a landscape display of the same pixels is judged the same way`() {
        assertTrue(ProfileCompatibility.evaluateStatic(profile, inputs(displayWidth = 3120, displayHeight = 1440)).supported)
    }

    @Test
    fun `a recording profile also needs its record size in the MediaRecorder list`() {
        assertTrue(ProfileCompatibility.evaluateStatic(recording, inputs()).supported)
        val missing = ProfileCompatibility.evaluateStatic(recording, inputs(recordSizes = listOf("1280x720")))
        assertFalse(missing.supported)
        assertEquals(listOf(ProfileCompatibility.REASON_RECORD_SIZE), missing.reasons)
    }

    @Test
    fun `a profile that does not record ignores the MediaRecorder list entirely`() {
        // camera2-standard-v1 never configures a recording stream, so an empty list is not a reason to refuse it.
        assertTrue(ProfileCompatibility.evaluateStatic(profile, inputs(recordSizes = emptyList())).supported)
    }

    @Test
    fun `the frame budget is recorded but never changes the verdict`() {
        val slow = ProfileCompatibility.evaluateStatic(profile, inputs(yuvMin = 50_000_000L))
        assertTrue("frame budget is informational only (3.6)", slow.supported)
        assertEquals(false, slow.frameBudgetOk)
        assertNull(ProfileCompatibility.evaluateStatic(profile, inputs(previewMin = null)).frameBudgetOk)
    }
}
