package dev.halcamera.cts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenCycleTest {
    @Test
    fun `summary lists the phases that were measured under the given open label`() {
        assertEquals("open 40.0 ms · configure 30.5 ms · first frame 120.0 ms · close 20.0 ms", OpenCycle(40.0, 30.5, 120.0, 20.0).summary())
        assertEquals("reopen 40.0 ms · close 20.0 ms", OpenCycle(openMs = 40.0, closeMs = 20.0).summary("reopen"))
        assertNull(OpenCycle().summary())
    }

    @Test
    fun `failures name the error, the missing frame and the slow close`() {
        assertEquals(emptyList<String>(), OpenCycle(40.0, 30.0, 120.0, 20.0).failures())
        assertEquals(listOf("IllegalStateException: Timeout waiting for the camera to open"), OpenCycle(error = "IllegalStateException: Timeout waiting for the camera to open").failures())
        assertEquals(listOf("No capture result was completed"), OpenCycle(openMs = 40.0, configureMs = 30.0).failures())
        assertEquals(listOf("Timeout waiting for the camera to close"), OpenCycle(40.0, 30.0, 120.0, 3000.0, closedInTime = false).failures())
    }
}
