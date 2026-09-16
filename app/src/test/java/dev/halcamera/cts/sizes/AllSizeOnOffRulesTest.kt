package dev.halcamera.cts.sizes

import dev.halcamera.cts.Dim
import dev.halcamera.cts.OpenCycle
import dev.halcamera.cts.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AllSizeOnOffRulesTest {
    @Test
    fun `plan visits each size once, largest first, and is not bounded`() {
        val sizes = listOf(Dim(1280, 720), Dim(3840, 2160), Dim(1920, 1080), Dim(1280, 720), Dim(1440, 1080))
        assertEquals(listOf(Dim(3840, 2160), Dim(1920, 1080), Dim(1440, 1080), Dim(1280, 720)), AllSizeOnOffRules.plan(sizes))
        assertEquals("3840x2160", AllSizeOnOffRules.stepId(Dim(3840, 2160)))
    }

    @Test
    fun `a camera without colour output or sizes is skipped with the reason`() {
        assertEquals("Camera 2 does not support color outputs, skipping", AllSizeOnOffRules.cameraSkipReason("2", false, listOf(Dim(640, 480))))
        assertEquals("Camera 2 reports no SurfaceHolder output size", AllSizeOnOffRules.cameraSkipReason("2", true, emptyList()))
        assertNull(AllSizeOnOffRules.cameraSkipReason("0", true, listOf(Dim(640, 480))))
    }

    @Test
    fun `a size passes when its pass reached a frame and fails with the pass's reasons`() {
        assertEquals(Verdict.PASS to listOf("open 40.0 ms · configure 30.0 ms · first frame 120.0 ms · close 20.0 ms"), AllSizeOnOffRules.judge(OpenCycle(40.0, 30.0, 120.0, 20.0)))
        val (verdict, details) = AllSizeOnOffRules.judge(OpenCycle(openMs = 40.0, configureMs = 30.0, closeMs = 20.0))
        assertEquals(Verdict.FAIL, verdict)
        assertEquals(listOf("open 40.0 ms · configure 30.0 ms · close 20.0 ms", "No capture result was completed"), details)
    }
}
