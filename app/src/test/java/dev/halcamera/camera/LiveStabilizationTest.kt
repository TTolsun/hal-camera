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

    @Test fun `live status uses fresh results and Auto never assumes EIS is enabled`() {
        val tracker = LiveEisTracker()
        fun result(at: Long?, value: Int?, now: Long) = tracker.update("a", false, LiveStabilization.AUTO, at, value, now, true)
        assertEquals("EIS 미적용", result(0, 0, 0).label)
        assertEquals("EIS 적용됨", result(1, 1, 1).label)
        assertEquals("EIS 적용됨", result(2, 2, 2).label)
        assertNull(result(3, 1, 3).warning)
        assertEquals("EIS 확인 불가", result(3, 1, 1_500_000_003L).label)
        assertEquals("EIS 확인 불가", result(4, null, 4).label)
        assertEquals("EIS 확인 불가", result(5, 99, 5).label)
        assertEquals("EIS 확인 불가", result(7, 1, 6).label)
    }

    @Test fun `mismatch needs a second fresh result after the grace period and clears on recovery`() {
        val tracker = LiveEisTracker()
        fun result(at: Long, value: Int, now: Long = at) = tracker.update("a", false, LiveStabilization.VIDEO, at, value, now, true)
        assertNull(result(0, 0).warning)
        assertNull(result(0, 0, 1_000_000_000L).warning) // polling one frame is not sustained evidence
        assertNotNull(result(1_000_000_000L, 0).warning)
        assertEquals("EIS 미적용", result(1_100_000_000L, 0).label)
        assertNull(result(1_200_000_000L, 1).warning)
        assertNull(result(1_300_000_000L, 0).warning)
    }

    @Test fun `two enabled modes still warn when the reported mode differs`() {
        val tracker = LiveEisTracker()
        tracker.update("a", true, LiveStabilization.PREVIEW, 0, 1, 0, true)
        val result = tracker.update("a", true, LiveStabilization.PREVIEW, 1_000_000_000L, 1, 1_000_000_000L, true)
        assertEquals("EIS 적용됨", result.label)
        assertTrue(result.warning!!.contains("EIS (Preview + Video)"))
        assertTrue(result.warning!!.contains("EIS (Video)"))
    }

    @Test fun `camera recording and request transitions discard previous results`() {
        val tracker = LiveEisTracker()
        assertEquals(1, tracker.update("a", false, LiveStabilization.VIDEO, 0, 1, 0, true).video)
        assertNull(tracker.update("a", true, LiveStabilization.VIDEO, 0, 1, 1, true).video)
        assertEquals(1, tracker.update("a", true, LiveStabilization.VIDEO, 2, 1, 2, true).video)
        assertNull(tracker.update("b", true, LiveStabilization.VIDEO, 2, 1, 3, true).video)
        assertNull(tracker.update("b", true, LiveStabilization.OFF, 3, 1, 4, true).video)
        assertNull(tracker.update("b", true, LiveStabilization.OFF, 5, 0, 5, false).video)
        assertNull(tracker.update("b", true, LiveStabilization.OFF, 5, 0, 6, true).video)
    }

    @Test fun `stale or missing metadata resets pending mismatch`() {
        val tracker = LiveEisTracker()
        tracker.update("a", false, LiveStabilization.OFF, 0, 1, 0, true)
        assertNull(tracker.update("a", false, LiveStabilization.OFF, 1, null, 1, true).warning)
        assertNull(tracker.update("a", false, LiveStabilization.OFF, 1_000_000_000L, 1, 1_000_000_000L, true).warning)
        assertNotNull(tracker.update("a", false, LiveStabilization.OFF, 2_000_000_000L, 1, 2_000_000_000L, true).warning)
        assertNull(tracker.update("a", false, LiveStabilization.OFF, 2_000_000_000L, 1, 4_000_000_000L, true).video)
        assertNull(tracker.update("a", false, LiveStabilization.OFF, 4_100_000_000L, 1, 4_100_000_000L, true).warning)
    }
}
