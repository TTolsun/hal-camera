package dev.halcamera.ui

import dev.halcamera.camera.FlashMode
import dev.halcamera.camera.LiveControlSupport
import dev.halcamera.camera.LiveControls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The LIVE readout leaves out what the buttons already say; these pin which values that is. */
class LiveControlReadoutTest {
    private val support = LiveControlSupport(evRange = -20..20, evStep = 0.1, aeLock = true, afLock = true,
        flash = true, autoFlash = true, alwaysFlash = true)

    @Test
    fun `a held AF lock reads as its outcome because the padlock already says it is held`() {
        assertEquals("AE Locked", LiveControlBar.aeState(3))
        assertEquals("AF Focused", LiveControlBar.afState(4))
        assertEquals("AF No focus", LiveControlBar.afState(5))
        assertEquals("AE —", LiveControlBar.aeState(null))
    }

    @Test
    fun `applied EV appears only when it differs from the button, flash state only while flash is on`() {
        assertNull(LiveControlBar.evApplied(7, LiveControls(evIndex = 7), support))
        assertEquals("EV +0.5 applied", LiveControlBar.evApplied(5, LiveControls(evIndex = 7), support))
        assertEquals("Flash Fired", LiveControlBar.flashState(3, LiveControls(flash = FlashMode.TORCH)))
        assertNull(LiveControlBar.flashState(2, LiveControls()))
    }
}
