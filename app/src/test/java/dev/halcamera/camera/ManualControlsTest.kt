package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test
import dev.halcamera.ui.ManualControlPanel

class ManualControlsTest {
    private val support = ManualSupport(100..3200, 100_000L..1_000_000_000L, 10f,
        WhiteBalance.entries, 1_000_000_000L / 30)
    private val quick = LiveControlSupport(-4..4, 0.5, true, true, true, true, true)

    @Test fun `manual exposure seeds within sensor and frame constraints`() {
        assertEquals(ManualExposure(3200, 33_333_333L), support.exposure(6400, 50_000_000L))
        assertEquals(ManualExposure(100, 100_000L), support.exposure(50, 1))
        assertNull(ManualSupport().exposure(400, 10_000_000))
    }

    @Test fun `explicit input outside limits is rejected instead of silently clamped`() {
        assertNotNull(support.rejection(ManualControls(exposure = ManualExposure(99, 10_000_000))))
        assertNotNull(support.rejection(ManualControls(exposure = ManualExposure(400, 40_000_000))))
        assertNull(support.rejection(ManualControls(exposure = ManualExposure(400, 10_000_000))))
        assertNotNull(support.rejection(ManualControls(focusDiopters = Float.NaN)))
        assertNotNull(ManualSupport().rejection(ManualControls(focusDiopters = 0f)))
    }

    @Test fun `manual disables conflicting locks EV and flash precapture`() {
        val m = ManualControls(ManualExposure(400, 10_000_000), 2f)
        val c = LiveControls(3, true, true, FlashMode.AUTO, m).coerce(quick, false)
        assertEquals(0, c.evIndex); assertFalse(c.aeLock); assertFalse(c.afLock)
        assertEquals(FlashMode.OFF, c.flash); assertFalse(c.needsPrecapture)
        assertEquals(FlashMode.TORCH, c.copy(flash = FlashMode.TORCH).coerce(quick, false).flash)
    }

    @Test fun `exposure and focus manual modes remain independent`() {
        val c = LiveControls(2, true, true, FlashMode.OFF, ManualControls(focusDiopters = 3f)).coerce(quick, false)
        assertTrue(c.aeLock); assertEquals(2, c.evIndex); assertFalse(c.afLock)
        val exposure = c.copy(manual = ManualControls(exposure = ManualExposure(400, 10_000_000)), afLock = true).coerce(quick, false)
        assertTrue(exposure.afLock); assertFalse(exposure.aeLock)
    }

    @Test fun `custom WB requires manual exposure and valid gains and transform`() {
        val custom = ManualControls(wb = WhiteBalance.CUSTOM)
        assertNotNull(support.rejection(custom))
        assertEquals(WhiteBalance.AUTO, custom.normalized(support).wb)
        val valid = custom.copy(exposure = ManualExposure(400, 10_000_000))
        assertNull(support.rejection(valid))
        assertNotNull(support.rejection(valid.copy(color = ManualColor(gains = listOf(1f)))))
        assertNotNull(support.rejection(valid.copy(color = ManualColor(transform = List(9) { Float.POSITIVE_INFINITY }))))
        assertEquals(WhiteBalance.AUTO, valid.normalized(ManualSupport()).wb)
        val impossibleFrame = support.copy(frameNs = support.exposureNs!!.first - 1)
        val normalized = valid.normalized(impossibleFrame)
        assertNull(normalized.exposure)
        assertEquals(WhiteBalance.AUTO, normalized.wb)
    }

    @Test fun `faster FPS limits exposure and a new camera drops unsupported controls`() {
        val value = ManualControls(ManualExposure(400, 30_000_000), 5f, WhiteBalance.DAYLIGHT)
        assertEquals(16_666_666L, value.normalized(support.copy(frameNs = 16_666_666)).exposure!!.timeNs)
        assertEquals(ManualControls(), value.normalized(ManualSupport()))
        assertNotNull(ManualSupport(camera2 = false).rejection(value))
    }

    @Test fun `shutter input accepts fractions and milliseconds and rejects nonfinite values`() {
        assertEquals(8_000_000L, ManualControlPanel.parseExposure("1/125"))
        assertEquals(8_000_000L, ManualControlPanel.parseExposure("8 ms"))
        listOf("NaN", "Infinity", "-1", "1/0", "abc", "1e300").forEach { assertNull(ManualControlPanel.parseExposure(it)) }
    }
}
