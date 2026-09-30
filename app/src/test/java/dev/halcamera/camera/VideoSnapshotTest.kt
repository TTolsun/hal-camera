package dev.halcamera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSnapshotTest {
    private val hd = LiveSize(1280, 720)
    private val full = LiveSize(1920, 1080)
    private val big = LiveSize(4000, 3000)
    private val small = LiveSize(640, 480)

    @Test fun `default size is the largest up to 1080p and the guaranteed size follows the video`() {
        assertEquals(listOf(full, hd), VideoSnapshotPlan.candidates(null, listOf(small, hd, full, big), hd))
        assertEquals(listOf(full), VideoSnapshotPlan.candidates(null, listOf(small, hd, full, big), full))
    }

    @Test fun `a requested size goes first and the guaranteed one is the fallback`() {
        assertEquals(listOf(big, full), VideoSnapshotPlan.candidates(big, listOf(small, hd, full, big), full))
        assertEquals(listOf(hd), VideoSnapshotPlan.candidates(hd, listOf(hd, full), hd))
    }

    @Test fun `no size that fits the video falls back to the smallest one`() {
        assertEquals(listOf(big), VideoSnapshotPlan.candidates(null, listOf(big), small))
        assertEquals(listOf(small), VideoSnapshotPlan.candidates(null, listOf(small, big), LiveSize(320, 240)))
    }

    @Test fun `a camera without JPEG sizes has no candidate`() {
        assertTrue(VideoSnapshotPlan.candidates(null, emptyList(), full).isEmpty())
    }

    @Test fun `status follows the recording and refuses a second snapshot`() {
        assertEquals(SnapshotStatus.NONE, SnapshotStatus.of(false, false, null, false))
        assertEquals(SnapshotStatus.Phase.READY, SnapshotStatus.of(true, false, null, false).phase)
        assertEquals(SnapshotStatus.Phase.BUSY, SnapshotStatus.of(true, false, null, true).phase)
        assertEquals(SnapshotStatus.Phase.BUSY, SnapshotStatus.of(true, true, null, false).phase)
        assertFalse(SnapshotStatus.of(true, false, null, true).canCapture)
        assertTrue(SnapshotStatus.of(true, false, null, false).canCapture)
    }

    @Test fun `an unsupported combination keeps its reason even while busy`() {
        val status = SnapshotStatus.of(true, true, VideoSnapshotPlan.REFUSED_REASON, true)
        assertEquals(SnapshotStatus.Phase.UNSUPPORTED, status.phase)
        assertEquals(VideoSnapshotPlan.REFUSED_REASON, status.reason)
        assertFalse(status.canCapture)
    }
}
