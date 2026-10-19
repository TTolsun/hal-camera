package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class PortraitSupportTest {
    private val preview = LiveSize(1440, 1080)
    private val jpeg = LiveSize(4000, 3000)

    @Test fun `declared support also requires both extension output types`() {
        assertFalse(PortraitSupport(null, listOf(preview), listOf(jpeg)).available)
        assertFalse(PortraitSupport(false, listOf(preview), listOf(jpeg)).available)
        assertFalse(PortraitSupport(true, emptyList(), listOf(jpeg)).available)
        assertFalse(PortraitSupport(true, listOf(preview)).available)
        assertFalse(PortraitSupport(true, listOf(preview), listOf(jpeg), "Not a public camera").available)
        assertTrue(PortraitSupport(true, listOf(preview), listOf(jpeg)).available)
    }

    @Test fun `initial pair matches aspect before closeness to preview target and never invents sizes`() {
        val wide = LiveSize(1920, 1080)
        val support = PortraitSupport(true, listOf(wide, preview, LiveSize(640, 480)), listOf(jpeg))
        assertEquals(PortraitConfig(preview, jpeg), support.config(jpeg))
        assertNull(support.config(wide))
        assertNull(support.copy(advertised = false).config(jpeg))
        assertEquals(preview, support.copy(previews = listOf(preview)).config(jpeg)?.preview)
    }

    @Test fun `exports preserve unknown errors and extension resolutions without claiming verified combinations`() {
        val support = PortraitSupport(true, listOf(preview), listOf(jpeg), errors = listOf("vendor metadata incomplete"))
        val snapshot = CameraProbeSnapshot("now", emptyList(), listOf(CameraProbeEntry("0", null, "Camera 0",
            listOf(ProbeSection("Portrait", support.probeRows())))), emptyList())
        val text = CameraProbeText.render(snapshot)
        assertTrue(text.contains("1440x1080")); assertTrue(text.contains("4000x3000"))
        assertTrue(text.contains("combination must still be checked"))
        assertTrue(snapshot.toJsonMap().toString().contains("vendor metadata incomplete"))
        assertEquals("Unknown", PortraitSupport(null).probeRows().first().value)
        assertTrue(PortraitSupport(true, listOf(preview)).unavailableReason!!.contains("JPEG"))
    }
}
