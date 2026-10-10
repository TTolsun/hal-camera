package dev.halcamera.ui

import org.junit.Assert.*
import org.junit.Test

class LiveMeasurementTextTest {
    @Test fun absentValuesStayUnknownAndNeverInventZeroReadings() {
        assertEquals("FPS — · ISO — · Exp — · AE — · AF —", LiveMeasurementText.format(emptyMap()))
    }
    @Test fun dualFpsKeepsTheSameExposureAndStateFormatting() {
        val values = mapOf("resultFps" to 29.95, "iso" to 100, "exposureNs" to 10_000_000L, "ae" to 2, "af" to 99)
        assertEquals("FPS 30.0 · ISO 100 · Exp 10.00ms · AE OK · AF #99", LiveMeasurementText.format(values))
        assertEquals("FPS 30 / 15 · ISO 100 · Exp 10.00ms · AE OK · AF #99", LiveMeasurementText.format(values, "30 / 15"))
    }
}
