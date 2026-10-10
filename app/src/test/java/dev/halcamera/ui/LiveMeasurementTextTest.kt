package dev.halcamera.ui

import org.junit.Assert.*
import org.junit.Test

class LiveMeasurementTextTest {
    @Test fun absentValuesStayUnknownAndNeverInventZeroReadings() {
        assertEquals("FPS — · EXP — · AE — · AF —", LiveMeasurementText.format(emptyMap()))
    }
    @Test fun exposureIsMeasuredWhileIsoAndEvAreOmitted() {
        val values = mapOf("resultFps" to 29.95, "iso" to 100, "exposureNs" to 10_000_000L,
            "evApplied" to -3, "ae" to 2, "af" to 99)
        assertEquals("FPS 30.0 · EXP 10.00ms · AE OK · AF #99", LiveMeasurementText.format(values))
        assertEquals("FPS 30 / 15 · EXP 10.00ms · AE OK · AF #99", LiveMeasurementText.format(values, "30 / 15"))
    }
    @Test fun stateChangesKeepTheSameFieldOrderAndReservedLabels() {
        val reserved = LiveMeasurementText.reservedLabels(false)
        for (ae in 0..5) for (af in 0..6) {
            val fields = LiveMeasurementText.fields(mapOf("ae" to ae, "af" to af))
            assertEquals(4, fields.size)
            assertTrue(fields[2] in reserved[2]); assertTrue(fields[3] in reserved[3])
        }
    }
}
