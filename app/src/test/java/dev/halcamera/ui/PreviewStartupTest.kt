package dev.halcamera.ui

import org.junit.Assert.*
import org.junit.Test

class PreviewStartupTest {
    @Test fun `configured session stays disabled until a displayed frame`() {
        val startup = PreviewStartup()
        assertTrue(startup.defer("Camera2 · LIVE", true))
        assertEquals("Camera2 · LIVE", startup.frameArrived())
        assertNull(startup.frameArrived())
    }

    @Test fun `frame before engine status does not invent readiness`() {
        val startup = PreviewStartup()
        assertNull(startup.frameArrived())
        assertFalse(startup.defer("CameraX · LIVE", true))
    }

    @Test fun `late frame cannot clear an intervening error`() {
        val startup = PreviewStartup()
        assertTrue(startup.defer("Camera2 · LIVE", true))
        assertFalse(startup.defer("Camera disconnected", false))
        assertNull(startup.frameArrived())
    }

    @Test fun `new mode discards old readiness and waits for its own frame`() {
        val startup = PreviewStartup()
        startup.defer("Old session", true)
        startup.reset()
        assertNull(startup.frameArrived())
        startup.reset()
        assertTrue(startup.defer("New session", true))
        assertEquals("New session", startup.frameArrived())
    }

    @Test fun `capture and save statuses pass through after startup`() {
        val startup = PreviewStartup()
        startup.frameArrived()
        assertFalse(startup.defer("Capturing", false))
        assertFalse(startup.defer("Photo saved", true))
    }
}
