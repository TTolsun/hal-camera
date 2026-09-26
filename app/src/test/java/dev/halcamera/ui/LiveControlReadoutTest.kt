package dev.halcamera.ui

import dev.halcamera.camera.FlashMode
import dev.halcamera.camera.LiveControlSupport
import dev.halcamera.camera.LiveControls
import org.junit.Assert.assertEquals
import org.junit.Test

/** The LIVE readout leaves out what the buttons already say; these pin which values that is. */
class LiveControlReadoutTest {
    private val support = LiveControlSupport(evRange = -20..20, evStep = 0.1, aeLock = true, afLock = true,
        flash = true, autoFlash = true, alwaysFlash = true)

    @Test
    fun `a held AF lock reads as its outcome because the padlock already says it is held`() {
        assertEquals("AE Locked · AF Focused", LiveControlBar.stateLine(3, 4))
        assertEquals("AE OK · AF No focus", LiveControlBar.stateLine(2, 5))
        assertEquals("AE — · AF —", LiveControlBar.stateLine(null, null))
    }

    @Test
    fun `applied EV appears only when it differs from the button, flash state only while flash is on`() {
        assertEquals(emptyList<String>(), LiveControlBar.appliedExtras(7, 2, LiveControls(evIndex = 7), support))
        assertEquals(listOf("EV +0.5 applied"), LiveControlBar.appliedExtras(5, 2, LiveControls(evIndex = 7), support))
        assertEquals(listOf("Flash Fired"), LiveControlBar.appliedExtras(0, 3, LiveControls(flash = FlashMode.TORCH), support))
    }
}
