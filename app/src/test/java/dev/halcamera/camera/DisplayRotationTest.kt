package dev.halcamera.camera

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayRotationTest {
    @Test fun `surface rotations map to clockwise degrees and back`() {
        listOf(Surface.ROTATION_0 to 0, Surface.ROTATION_90 to 90, Surface.ROTATION_180 to 180, Surface.ROTATION_270 to 270)
            .forEach { (rotation, degrees) ->
                assertEquals(degrees, DisplayRotation.degrees(rotation))
                assertEquals(rotation, DisplayRotation.surfaceRotation(degrees))
            }
    }

    @Test fun `unknown display or angle falls back to natural orientation`() {
        assertEquals(0, DisplayRotation.degrees(null))
        assertEquals(Surface.ROTATION_0, DisplayRotation.surfaceRotation(45))
    }
}
