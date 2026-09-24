package dev.halcamera.metrics

import dev.halcamera.telemetry.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recording cadence metrics 3.2, 3.4 and 3.7 (docs/METRICS.md 3). Every case is built from telemetry events
 * exactly as the engine records them, so the tests also pin down which events count as a recording frame.
 */
class RecordMetricsTest {

    private val session = "bm-1"
    private val tag = "record-0"
    private val frameNs = 33_333_333L
    private val expectedMs = 1000.0 / 30

    /**
     * [seconds] of results at the profile's 30 fps. [anomalyAt] lengthens one interval, [dropAt] leaves out a
     * result so its frame number is missing, and [durationAfter] changes the reported frame duration from that
     * frame on, the way an AE change does.
     */
    private fun recording(
        seconds: Double, anomalyAt: Int? = null, anomalyNs: Long = 80_000_000L,
        dropAt: Int? = null, durationAfter: Int? = null, durationNs: Long = 66_666_666L,
        eventTag: String = tag
    ): List<Event> {
        val count = (seconds * 30).toInt()
        val out = ArrayList<Event>(count)
        var sensor = 1_000_000_000L
        for (n in 0 until count) {
            if (n > 0) sensor += if (anomalyAt == n) anomalyNs else frameNs
            if (dropAt == n) continue
            val duration = if (durationAfter != null && n >= durationAfter) durationNs else frameNs
            out += Event(
                atNs = sensor + 20_000_000L, session = session, kind = "capture_result",
                frame = n.toLong(), sensorNs = sensor,
                values = mapOf("requestTag" to eventTag, "frameDurationNs" to duration)
            )
        }
        return out
    }

    private fun cadence(events: List<Event>, expected: Double? = expectedMs) =
        RecordMetrics.cadence(RecordMetrics.frames(events, session, tag), expected)

    @Test
    fun `a steady nine second recording reports thirty fps, no anomaly and almost no jitter`() {
        val c = cadence(recording(9.0))
        assertEquals(0, c.anomalyCount)
        assertNull(c.anomalyUnknownReason)
        // Nine seconds of recording is 270 frames spanning 8.97 s of sensor time, and the three-second
        // start-up skip leaves five complete windows. The trailing 0.97 s is dropped.
        assertEquals(5, c.windows)
        assertEquals(30.0, c.windowFpsP50!!, 0.001)
        assertEquals(30.0, c.windowFpsMin!!, 0.001)
        assertEquals(0.0, c.jitterStdDevMs!!, 0.001)
        assertEquals(33.333, c.jitterP95Ms!!, 0.01)
        assertEquals(0, c.excludedIntervals)
        assertEquals(c.intervals.size, c.comparedIntervals)
    }

    @Test
    fun `one long interval is counted as one anomaly`() {
        val c = cadence(recording(9.0, anomalyAt = 100))
        // 80 ms is above 1.5 x 33.3 ms, so exactly one interval crosses the line.
        assertEquals(1, c.anomalyCount)
        assertTrue("the long interval widens the jitter", c.jitterStdDevMs!! > 0.0)
        // The window the long frame falls in comes up short, while the median window is unaffected.
        assertTrue(c.windowFpsMin!! < 30.0)
        assertEquals(30.0, c.windowFpsP50!!, 0.001)
    }

    @Test
    fun `an interval just under the ratio is not an anomaly`() {
        // 1.4 x the reference interval: slow, but not what METRICS.md 3.2 counts.
        val c = cadence(recording(9.0, anomalyAt = 100, anomalyNs = (frameNs * 1.4).toLong()))
        assertEquals(0, c.anomalyCount)
    }

    @Test
    fun `a missing result is excluded from the comparison instead of counted as an anomaly`() {
        val c = cadence(recording(9.0, dropAt = 100))
        // The pair across the hole has non-consecutive frame numbers, so it is not compared at all: a result
        // the measurement lost is not evidence that the camera was late.
        assertEquals(0, c.anomalyCount)
        assertEquals(1, c.excludedIntervals)
        assertEquals(c.intervals.size, c.comparedIntervals)
    }

    @Test
    fun `a changed frame duration leaves 3 point 2 not measurable instead of reporting zero`() {
        // Every frame runs at 66.7 ms, so no segment matches the profile's 33.3 ms reference.
        val c = cadence(recording(9.0, durationAfter = 0))
        assertNull(c.anomalyCount)
        assertEquals(UnknownReason.CADENCE_CHANGED, c.anomalyUnknownReason)
        assertEquals(0, c.comparedIntervals)
        // The other two metrics still have their frames; only the fixed-cadence judgement is unavailable.
        assertTrue(c.windows > 0)
        assertEquals(0.0, c.jitterStdDevMs!!, 0.001)
    }

    @Test
    fun `only the segment at the reference duration is judged`() {
        val c = cadence(recording(9.0, durationAfter = 120))
        assertEquals(0, c.anomalyCount)
        // Frames 0 to 119 are judged; the rest run at another duration and are left out of the count.
        assertTrue("the changed segment is not compared", c.comparedIntervals < c.intervals.size)
        assertEquals(120, c.comparedIntervals)
    }

    @Test
    fun `a recording shorter than the skip window leaves 3 point 4 not measurable`() {
        val c = cadence(recording(2.5))
        assertEquals(0, c.windows)
        assertNull(c.windowFpsP50)
        assertNull(c.windowFpsMin)
        // The intervals are still there, so the jitter and the anomaly count are not lost with it.
        assertEquals(0, c.anomalyCount)
        assertTrue(c.intervals.isNotEmpty())
    }

    @Test
    fun `the trailing partial window is dropped`() {
        // 5.6 s: 3 s skip, then two complete windows and 0.6 s that would read as 18 fps if it were counted.
        val c = cadence(recording(5.6))
        assertEquals(2, c.windows)
        assertEquals(30.0, c.windowFpsMin!!, 0.001)
    }

    @Test
    fun `frames are selected by request tag, not by time`() {
        val mine = recording(9.0)
        val preview = recording(9.0, eventTag = "record-prep-0")
        val nextCycle = recording(9.0, eventTag = "record-1")
        val all = (preview + mine + nextCycle).sortedBy { it.atNs }
        assertEquals(RecordMetrics.frames(mine, session, tag).size, RecordMetrics.frames(all, session, tag).size)
        assertEquals(0, RecordMetrics.frames(all, "other-session", tag).size)
    }

    @Test
    fun `a profile without a fixed recording rate does not judge 3 point 2 against a guess`() {
        val c = cadence(recording(9.0), expected = null)
        assertNull(c.anomalyCount)
        assertEquals(UnknownReason.CADENCE_CHANGED, c.anomalyUnknownReason)
        // The measurements that do not need a reference are still reported.
        assertEquals(5, c.windows)
        assertEquals(0.0, c.jitterStdDevMs!!, 0.001)
    }

    @Test
    fun `an empty cycle reports not measurable everywhere rather than zero`() {
        val c = cadence(emptyList())
        assertEquals(0, c.frames)
        assertNull(c.anomalyCount)
        assertEquals(UnknownReason.NOT_MEASURABLE, c.anomalyUnknownReason)
        assertNull(c.windowFpsP50)
        assertNull(c.jitterStdDevMs)
        assertNull(c.jitterP95Ms)
        assertEquals(0, c.windows)
    }

    @Test
    fun `jitter is the population standard deviation of the valid intervals`() {
        // Two intervals of 30 ms and two of 40 ms: mean 35, ddof = 0 standard deviation exactly 5.
        val frames = listOf(0L, 30L, 60L, 100L, 140L).mapIndexed { i, ms ->
            RecordFrame(i.toLong(), ms * 1_000_000, frameNs)
        }
        val c = RecordMetrics.cadence(frames, expectedMs)
        assertEquals(5.0, c.jitterStdDevMs!!, 0.001)
        assertEquals(40.0, c.jitterP95Ms!!, 0.001)
        assertEquals(listOf(30.0, 30.0, 40.0, 40.0), c.intervals)
    }
}
