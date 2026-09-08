package dev.cameradoctor.telemetry

import org.junit.Assert.*
import org.junit.Test

class FlightRecorderTest {
    private var now = 0L
    private val sec = 1_000_000_000L
    @Test fun retainsExactIncidentWindowAndDoesNotIncludeLateFrames() {
        val recorder = FlightRecorder({ now })
        for (i in 0..20) { now = i * sec; recorder.record("x", "result", i.toLong()) }
        assertTrue(recorder.trigger("one"))
        assertFalse(recorder.trigger("two"))
        for (i in 21..26) { now = i * sec; recorder.record("x", "result", i.toLong()) }
        val incident = recorder.finish()!!
        assertEquals(10 * sec, incident.events.first().atNs)
        assertEquals(25 * sec, incident.events.last().atNs)
        assertEquals("completed", incident.finishReason)
        assertNull(recorder.finish())
    }
    @Test fun snapshotExpiresWithoutNewCallbacks() {
        val recorder = FlightRecorder({ now })
        recorder.record("x", "result")
        now = 31 * sec
        assertTrue(recorder.snapshot().isEmpty())
    }
    @Test fun preservesPinnedPreWindowEvenWhenRingOverflows() {
        val recorder = FlightRecorder({ now }, maxEvents = 4)
        repeat(4) { recorder.record("x", "result", it.toLong()) }
        recorder.trigger("capacity")
        now = 5 * sec
        repeat(3) { recorder.record("x", "image", it.toLong()) }
        val incident = recorder.finish()!!
        assertEquals(8, incident.events.size)
        assertEquals(0L, incident.events.first().frame)
        assertTrue(incident.capacityEvictions > 0)
        assertFalse(incident.incidentTruncated)
    }
    @Test fun pauseReturnsExplicitPartialIncident() {
        val recorder = FlightRecorder({ now })
        recorder.trigger("pause")
        now = sec
        assertNull(recorder.finish())
        assertEquals("activity_stopped", recorder.finish("activity_stopped")!!.finishReason)
        assertTrue(recorder.trigger("again"))
    }
    @Test fun boundsPinnedMemoryAndReportsTruncation() {
        val recorder = FlightRecorder({ now }, maxEvents = 2)
        recorder.trigger("overflow")
        repeat(20) { recorder.record("x", "result") }
        now = 5 * sec
        val incident = recorder.finish()!!
        assertEquals(4, incident.events.size)
        assertTrue(incident.incidentTruncated)
    }
    @Test fun concurrentCallbacksAreNotLost() {
        val recorder = FlightRecorder({ 0L }, maxEvents = 4000)
        val threads = (1..4).map { index -> Thread { repeat(500) { recorder.record("$index", "result") } }.apply { start() } }
        threads.forEach { it.join() }
        assertEquals(2000, recorder.snapshot().size)
    }
    @Test fun missingAndNonmonotonicSensorTimesDoNotBecomeFps() {
        val tracker = FrameTracker()
        assertNull(tracker.add(100, null).fps)
        assertNull(tracker.add(101, sec).fps)
        val measured = tracker.add(104, sec + 40_000_000)
        assertEquals(25.0, measured.fps!!, 0.001)
        assertEquals(2L, measured.resultGap)
        assertNull(tracker.add(105, sec - 1).fps)
    }
}
