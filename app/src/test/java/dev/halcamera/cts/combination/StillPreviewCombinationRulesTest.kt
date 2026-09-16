package dev.halcamera.cts.combination

import dev.halcamera.cts.Dim
import dev.halcamera.cts.Verdict
import dev.halcamera.cts.combination.StillPreviewCombinationRules.Capture
import dev.halcamera.cts.combination.StillPreviewCombinationRules.Combo
import dev.halcamera.cts.combination.StillPreviewCombinationRules.ImageInfo
import dev.halcamera.cts.combination.StillPreviewCombinationRules.JPEG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StillPreviewCombinationRulesTest {
    private val uhdStill = Dim(4000, 3000)
    private val fhdStill = Dim(1920, 1080)
    private val fhd = Dim(1920, 1080)
    private val qcif = Dim(176, 144)

    private fun image(w: Int = 4000, h: Int = 3000, format: Int = JPEG, bytes: Int = 2_000_000, decoded: Dim? = Dim(w, h)) = ImageInfo(w, h, format, bytes, decoded)
    private fun capture(image: ImageInfo? = image(), result: Boolean = true, error: String? = null) =
        Capture(configureMs = 80.0, firstPreviewMs = 150.0, captureMs = 420.0, resultReceived = result, image = image, error = error)

    @Test
    fun `plan loops stills outside previews and skips QCIF with a still above Full HD`() {
        val combos = StillPreviewCombinationRules.plan(listOf(uhdStill, fhdStill), listOf(fhd, qcif))
        assertEquals(listOf(Combo(uhdStill, fhd), Combo(fhdStill, fhd), Combo(fhdStill, qcif)), combos)
        assertEquals("4000x3000/1920x1080", StillPreviewCombinationRules.stepId(combos[0]))
    }

    @Test
    fun `skip reasons cover colour output and empty size lists`() {
        assertEquals("Camera 2 does not support color outputs, skipping", StillPreviewCombinationRules.cameraSkipReason("2", false, listOf(fhdStill), listOf(fhd)))
        assertEquals("Camera 2 reports no JPEG output size", StillPreviewCombinationRules.cameraSkipReason("2", true, emptyList(), listOf(fhd)))
        assertEquals("Camera 2 reports no SurfaceHolder preview size within the preview bound", StillPreviewCombinationRules.cameraSkipReason("2", true, listOf(fhdStill), emptyList()))
        assertNull(StillPreviewCombinationRules.cameraSkipReason("0", true, listOf(fhdStill), listOf(fhd)))
    }

    @Test
    fun `image failures name the missing image, the wrong format, the wrong size and an undecodable blob`() {
        assertEquals(listOf("Unable to get the image after capture"), StillPreviewCombinationRules.imageFailures(uhdStill, null))
        assertEquals(listOf("Image format 35 is not JPEG"), StillPreviewCombinationRules.imageFailures(uhdStill, image(format = 35)))
        assertEquals(
            listOf("Image size 1920x1080 doesn't match the requested size 4000x3000", "Decoded JPEG size 1920x1080 doesn't match the requested size 4000x3000"),
            StillPreviewCombinationRules.imageFailures(uhdStill, image(1920, 1080))
        )
        assertEquals(listOf("JPEG buffer is empty", "JPEG cannot be decoded"), StillPreviewCombinationRules.imageFailures(uhdStill, image(bytes = 0, decoded = null)))
        assertEquals(emptyList<String>(), StillPreviewCombinationRules.imageFailures(uhdStill, image()))
    }

    @Test
    fun `a combination passes with its timings and fails on a missing result or an error`() {
        val combo = Combo(uhdStill, fhd)
        assertEquals(Verdict.PASS to listOf("configure 80.0 ms · first preview 150.0 ms · still 420.0 ms · jpeg 1953 KB"), StillPreviewCombinationRules.judge(combo, capture()))
        val noResult = StillPreviewCombinationRules.judge(combo, capture(result = false))
        assertEquals(Verdict.FAIL, noResult.first)
        assertEquals("Timeout waiting for the still capture result", noResult.second[1])
        val failed = StillPreviewCombinationRules.judge(combo, Capture(configureMs = 80.0, error = "IllegalStateException: Camera session configuration failed"))
        assertEquals(Verdict.FAIL to listOf("configure 80.0 ms", "IllegalStateException: Camera session configuration failed"), failed)
    }
}
