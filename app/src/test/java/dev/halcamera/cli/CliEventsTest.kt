package dev.halcamera.cli

import dev.halcamera.telemetry.Event
import dev.halcamera.telemetry.FlightRecorder
import org.junit.Assert.*
import org.junit.Test

class CliEventsTest {
    @Test fun independentPipFramesRetainTheirSessionAndSensorIdentity() {
        val targets = mapOf("streams" to listOf("preview"))
        val main = CliEvents.toMap(Event(11, "main", "capture_started", 7, 20, targets))
        val pip = CliEvents.toMap(Event(12, "main:pip:secondary", "capture_started", 7, 30, targets))

        assertEquals("main", main["session_id"])
        assertEquals("main:pip:secondary", pip["session_id"])
        assertEquals(7L, main["frame_number"])
        assertEquals(7L, pip["frame_number"])
        assertEquals("20", main["sensor_timestamp_ns"])
        assertEquals("30", pip["sensor_timestamp_ns"])
        assertEquals(targets, main["values"])
        assertEquals(targets, pip["values"])
    }

    @Test fun nanosecondClocksRemainSeparateAndLosslessBeyondDoublePrecision() {
        val observed = 9_007_199_254_740_993L
        val mapped = CliEvents.toMap(Event(observed, "main", "capture_result", Long.MAX_VALUE, Long.MAX_VALUE))

        assertEquals(observed.toString(), mapped["at_ns"])
        assertEquals(Long.MAX_VALUE.toString(), mapped["sensor_timestamp_ns"])
        assertEquals(Long.MAX_VALUE, mapped["frame_number"])
        assertEquals("capture_result", mapped["kind"])
    }

    @Test fun missingCallbackFieldsRemainNullRatherThanInventingFrameOrSensorTime() {
        val mapped = CliEvents.toMap(Event(45, "main", "closed"))

        assertTrue(mapped.containsKey("frame_number"))
        assertTrue(mapped.containsKey("sensor_timestamp_ns"))
        assertNull(mapped["frame_number"])
        assertNull(mapped["sensor_timestamp_ns"])
        assertEquals("45", mapped["at_ns"])
        assertEquals(emptyMap<String, Any?>(), mapped["values"])
    }

    @Test fun recentEventsKeepTheOneSecondWindowLastHundredAndOriginalOrder() {
        var now = 0L
        val recorder = FlightRecorder({ now })
        recorder.record("old", "capture_started", 0, 1)
        now = 2_000_000_000L
        repeat(105) { frame ->
            recorder.record(if (frame % 2 == 0) "main" else "pip", "capture_result", frame.toLong(), frame.toLong())
            now++
        }

        val mapped = CliEvents.recent(recorder)
        assertEquals(100, mapped.size)
        assertEquals((5L..104L).toList(), mapped.map { it["frame_number"] })
        assertFalse(mapped.any { it["session_id"] == "old" })
        assertEquals("pip", mapped.first()["session_id"])
        assertEquals("main", mapped.last()["session_id"])
    }
}
