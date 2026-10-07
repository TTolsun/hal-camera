package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class CameraReleaseWaitTest {
    @Test fun `the released camera becoming available ends the wait`() {
        val wait = CameraReleaseWait("2")
        assertTrue(wait.available("2"))
        assertEquals(CameraReleaseWait.Outcome.AVAILABLE, wait.outcome)
    }

    @Test fun `other cameras becoming available keep waiting`() {
        val wait = CameraReleaseWait("2")
        assertFalse(wait.available("0"))
        assertFalse(wait.available("1"))
        assertNull(wait.outcome)
    }

    @Test fun `a camera still held when the limit passes opens anyway`() {
        val wait = CameraReleaseWait("2")
        assertTrue(wait.timedOut())
        assertEquals(CameraReleaseWait.Outcome.TIMEOUT, wait.outcome)
        assertFalse("a late release must not open a second time", wait.available("2"))
    }

    @Test fun `the limit after the release does not open a second time`() {
        val wait = CameraReleaseWait("2")
        assertTrue(wait.available("2"))
        assertFalse(wait.timedOut())
        assertFalse(wait.available("2"))
        assertEquals(CameraReleaseWait.Outcome.AVAILABLE, wait.outcome)
    }

    @Test fun `closing the engine ends the wait without opening`() {
        val wait = CameraReleaseWait("2")
        assertTrue(wait.cancel())
        assertFalse(wait.available("2"))
        assertFalse(wait.timedOut())
        assertEquals(CameraReleaseWait.Outcome.CANCELLED, wait.outcome)
    }

    @Test fun `the limit stays below the retry budget it replaces`() {
        assertTrue(CameraReleaseWait.LIMIT_MS <= CameraOpenRetry.DEFAULT_DELAYS_MS.sum())
    }
}
