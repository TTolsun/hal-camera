package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class CameraOpenRetryTest {
    @Test fun `a camera still released by another engine is opened again with growing delays`() {
        val retry = CameraOpenRetry(listOf(200L, 400L))
        assertEquals(200L, retry.next(CameraOpenRetry.Cause.DISCONNECTED))
        assertEquals(400L, retry.next(CameraOpenRetry.Cause.IN_USE))
        assertEquals(2, retry.attempts)
    }

    @Test fun `a spent budget ends the open so the caller reports it instead of waiting for the CLI timeout`() {
        val retry = CameraOpenRetry(listOf(200L))
        assertEquals(200L, retry.next(CameraOpenRetry.Cause.MAX_IN_USE))
        assertNull(retry.next(CameraOpenRetry.Cause.MAX_IN_USE))
    }

    @Test fun `a disabled camera or an unknown failure is final at once`() {
        val retry = CameraOpenRetry()
        assertNull(retry.next(CameraOpenRetry.Cause.DISABLED))
        assertNull(retry.next(CameraOpenRetry.Cause.OTHER))
        assertEquals(0, retry.attempts)
    }

    @Test fun `state callback errors map to their causes`() {
        assertEquals(CameraOpenRetry.Cause.IN_USE, CameraOpenRetry.Cause.fromStateError(1))
        assertEquals(CameraOpenRetry.Cause.MAX_IN_USE, CameraOpenRetry.Cause.fromStateError(2))
        assertEquals(CameraOpenRetry.Cause.DISABLED, CameraOpenRetry.Cause.fromStateError(3))
        assertEquals(CameraOpenRetry.Cause.DEVICE_ERROR, CameraOpenRetry.Cause.fromStateError(4))
        assertEquals(CameraOpenRetry.Cause.SERVICE_ERROR, CameraOpenRetry.Cause.fromStateError(5))
        assertEquals(CameraOpenRetry.Cause.OTHER, CameraOpenRetry.Cause.fromStateError(99))
    }

    @Test fun `access exception reasons map to their causes`() {
        assertEquals(CameraOpenRetry.Cause.DISABLED, CameraOpenRetry.Cause.fromAccessReason(1))
        assertEquals(CameraOpenRetry.Cause.DISCONNECTED, CameraOpenRetry.Cause.fromAccessReason(2))
        assertEquals(CameraOpenRetry.Cause.SERVICE_ERROR, CameraOpenRetry.Cause.fromAccessReason(3))
        assertEquals(CameraOpenRetry.Cause.IN_USE, CameraOpenRetry.Cause.fromAccessReason(4))
        assertEquals(CameraOpenRetry.Cause.MAX_IN_USE, CameraOpenRetry.Cause.fromAccessReason(5))
    }

    @Test fun `the default budget stays well inside the 30 second CLI timeout`() {
        assertTrue(CameraOpenRetry.DEFAULT_DELAYS_MS.sum() <= 10_000L)
    }
}
