package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class DepthSupportTest {
    private val size = LiveSize(320, 240)

    @Test fun `capability and at least one size are both required for LIVE`() {
        assertFalse(DepthSupport(false, listOf(size), false, true).available)
        assertFalse(DepthSupport(null, listOf(size), false, true).available)
        assertFalse(DepthSupport(true, emptyList(), false, true).available)
        assertTrue(DepthSupport(true, listOf(size), null, true).available)
        assertTrue(DepthSupport(true, listOf(size), true, false).available)
    }

    @Test fun `exclusive and unknown never claim simultaneous color support`() {
        assertTrue(DepthSupport(true, listOf(size), true, true).colorSummary.startsWith("No:"))
        assertTrue(DepthSupport(true, listOf(size), null, true).colorSummary.startsWith("Unknown:"))
        assertTrue(DepthSupport(true, listOf(size), false, true).colorSummary.contains("combination must still be checked"))
        assertTrue(DepthSupport(true, listOf(size), false, false).colorSummary.contains("no color output"))
        assertTrue(DepthSupport(true, listOf(size), false, null).colorSummary.startsWith("Unknown:"))
        assertTrue(DepthSupport(false, emptyList(), null, true).colorSummary.startsWith("Not applicable:"))
    }

    @Test fun `depth rows survive text and JSON export with read errors`() {
        val support = DepthSupport(true, listOf(size), true, true, listOf("vendor key unavailable"))
        val snapshot = CameraProbeSnapshot("now", emptyList(), listOf(CameraProbeEntry("0", null, "Camera 0",
            listOf(ProbeSection("Depth", support.probeRows())))), emptyList())
        assertTrue(CameraProbeText.render(snapshot).contains("320x240"))
        assertTrue(CameraProbeText.render(snapshot).contains("interleave"))
        assertTrue(snapshot.toJsonMap().toString().contains("vendor key unavailable"))
        assertTrue(support.probeRows(false).last { it.key == "LIVE depth map" }.value.contains("Physical camera"))
    }
}
