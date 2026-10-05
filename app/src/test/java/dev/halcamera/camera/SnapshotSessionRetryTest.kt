package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class SnapshotSessionRetryTest {
    @Test fun `JPEG rejection releases its output and builds one plain recording session`() {
        val retry = SnapshotSessionRetry()
        val events = mutableListOf<String>()
        var jpegOpen = true
        var recordingAlive = true
        fun oldSessionClosed() { if (!retry.replaced) recordingAlive = false }
        fun configureFailed() = retry.onRejected(true, true, false) {
            events += VideoSnapshotPlan.REFUSED_REASON
            jpegOpen = false
            events += "release JPEG"
            oldSessionClosed()
            events += "configure preview + encoder"
        }
        assertTrue(configureFailed())
        oldSessionClosed()
        assertTrue(configureFailed())
        assertEquals(listOf(VideoSnapshotPlan.REFUSED_REASON, "release JPEG", "configure preview + encoder"), events)
        assertFalse(jpegOpen)
        assertTrue(recordingAlive)
    }

    @Test fun `a rejected plain session fails instead of retrying forever`() {
        val plain = SnapshotSessionRetry()
        assertFalse(plain.onRejected(false, true, false) { fail("Plain session must not retry") })
        assertFalse(plain.replaced)
    }

    @Test fun `stop and camera close do not start a replacement recording`() {
        for ((active, stopping) in listOf(false to false, true to true, false to true)) {
            val retry = SnapshotSessionRetry()
            assertFalse(retry.onRejected(true, active, stopping) { fail("Closed recording must not retry") })
            assertFalse(retry.replaced)
        }
    }

    @Test fun `replacement construction failure keeps the old close callback from finishing twice`() {
        val retry = SnapshotSessionRetry()
        var failures = 0
        try {
            retry.onRejected(true, true, false) { throw IllegalStateException("plain session refused") }
        } catch (_: IllegalStateException) { failures++ }
        // The recorder handles this thrown creation failure; onClosed from the rejected session is obsolete.
        if (!retry.replaced) failures++
        assertEquals(1, failures)
    }
}
