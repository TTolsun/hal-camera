package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class LiveStabilizationTest {
    @Test fun `unsupported modes and pre API 33 preview stabilization are hidden`() {
        assertEquals(listOf(LiveStabilization.AUTO, LiveStabilization.OFF), LiveStabilization.supported(emptyList(), emptyList(), 36))
        val modes = LiveStabilization.supported(listOf(0, 1), listOf(0, 1, 2, 99), 32)
        assertEquals(listOf(LiveStabilization.AUTO, LiveStabilization.OFF, LiveStabilization.OIS, LiveStabilization.VIDEO), modes)
        assertTrue(LiveStabilization.PREVIEW in LiveStabilization.supported(listOf(0), listOf(0, 2), 33))
    }

    @Test fun `explicit requests never enable independent OIS and EIS together and Auto leaves defaults alone`() {
        assertNull(LiveStabilization.AUTO.optical)
        assertNull(LiveStabilization.AUTO.video)
        LiveStabilization.entries.forEach { assertFalse(it.optical == 1 && it.video != 0) }
        assertEquals(0, LiveStabilization.OFF.optical)
        assertEquals(0, LiveStabilization.OFF.video)
        assertEquals(2, LiveStabilization.PREVIEW.video)
    }

    @Test fun `camera and engine support reject carried over unsupported settings`() {
        val support = LiveStreamSupport(listOf(LiveSize(1280, 720)), emptyList(), emptyList(), emptyList(), emptyList())
        val requested = support.defaults().copy(stabilization = LiveStabilization.OIS)
        assertNotNull(support.rejection(requested))
        assertNull(support.copy(stabilization = LiveStabilization.entries).rejection(requested))
        assertNull(support.rejection(requested.copy(stabilization = LiveStabilization.AUTO)))
        assertEquals("OIS", requested.metadata()["stabilization"])
        val output = java.io.ByteArrayOutputStream()
        java.io.ObjectOutputStream(output).use { it.writeObject(requested) }
        java.io.ObjectInputStream(java.io.ByteArrayInputStream(output.toByteArray())).use { assertEquals(requested, it.readObject()) }
    }

    @Test fun `missing and unknown result metadata never masquerade as Off`() {
        assertEquals("OIS Unavailable · EIS Unavailable", LiveStabilization.observed(null, null))
        assertEquals("OIS On · EIS Preview", LiveStabilization.observed(1, 2))
        assertEquals("OIS Unknown (5) · EIS Off", LiveStabilization.observed(5, 0))
    }
}
