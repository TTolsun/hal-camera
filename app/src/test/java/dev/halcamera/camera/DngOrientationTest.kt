package dev.halcamera.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class DngOrientationTest {
    @Test fun `DNG rotation retains TIFF orientation values across EXIF implementation changes`() {
        // TIFF orientation values are a file-format contract, independent of either EXIF library.
        mapOf(0 to 1, 90 to 6, 180 to 3, 270 to 8, 360 to 1, -90 to 8, 450 to 6).forEach { (degrees, tag) ->
            assertEquals(tag, DngOutput.exifOrientation(degrees))
        }
    }
}
