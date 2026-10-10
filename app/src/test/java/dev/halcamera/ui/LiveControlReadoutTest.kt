package dev.halcamera.ui

import dev.halcamera.camera.FlashMode
import dev.halcamera.camera.LiveControls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The LIVE readout leaves out what the buttons already say; these pin which values that is. */
class LiveControlReadoutTest {
    @Test
    fun `a held AF lock reads as its outcome because the padlock already says it is held`() {
        assertEquals("FPS — · EXP — · AE Locked · AF Focus · AWB —",
            LiveMeasurementText.format(mapOf("ae" to 3, "af" to 4)))
        assertEquals("FPS — · EXP — · AE — · AF No focus · AWB —",
            LiveMeasurementText.format(mapOf("af" to 5)))
    }

    @Test
    fun `flash state appears only while flash is on`() {
        assertEquals("Flash Fired", LiveControlBar.flashState(3, LiveControls(flash = FlashMode.TORCH)))
        assertNull(LiveControlBar.flashState(2, LiveControls()))
    }
}
