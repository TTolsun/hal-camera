package dev.halcamera.ui

import dev.halcamera.telemetry.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LIVE numbers of 8.1. What is asserted here is that the readout reports and never judges: there is no state,
 * no level and no threshold in [LiveReading], and the only reference it carries is the session's own median.
 */
class LiveReadoutTest {
    private val session = "s"
    private val frameNs = 33_333_333L

    /** [frames] frames at a steady cadence, each with a partial gap and a buffer arrival. */
    private fun stream(frames: Int, gapMs: Double = 5.0, bufferMs: Double = 9.0): List<Event> {
        val out = mutableListOf<Event>()
        for (n in 0 until frames) {
            val start = n * frameNs
            out += Event(start, session, "capture_started", frame = n.toLong(), sensorNs = start)
            out += Event(start + (gapMs * 1e6).toLong(), session, "capture_result", frame = n.toLong(), sensorNs = start,
                values = mapOf("frameDurationNs" to frameNs, "ae" to 2, "af" to 2, "afMode" to 4, "awb" to 2,
                    "iso" to 100, "exposureNs" to 8_000_000L))
            out += Event(start + (bufferMs * 1e6).toLong(), session, "image_available", sensorNs = start,
                values = mapOf("stream" to "yuv"))
        }
        return out
    }

    private fun now(frames: Int) = frames * frameNs + frameNs

    @Test fun beforeEnoughFramesThereIsNoReferenceButTheLatestFrameStillReads() {
        // A reference drawn from four frames would move every time one arrived, which is worse than none.
        val r = LiveReadout().read(stream(4), session, now(4))
        assertNull(r.intervalRefMs)
        assertFalse(r.hasReference)
        assertEquals(4, r.baselineFrames)
        assertEquals(33.3, r.intervalMs!!, 0.1)
        assertEquals(5.0, r.partialMs!!, 0.1)
    }

    @Test fun theReferenceIsTheSessionMedianOfTheOlderFrames() {
        val frames = 60
        val r = LiveReadout().read(stream(frames), session, now(frames))
        assertTrue(r.hasReference)
        assertEquals(33.3, r.intervalRefMs!!, 0.1)
        assertEquals(5.0, r.baselinePartialMs!!, 0.1)
        assertEquals(9.0, r.baselineBufferMs!!, 0.1)
        // A steady stream stalls nowhere, and the count is of what happened rather than of what it means.
        assertEquals(0, r.stalls)
    }

    @Test fun aLateFrameIsCountedAndNotJudged() {
        val events = stream(60).toMutableList()
        val late = 61 * frameNs
        events += Event(late, session, "capture_started", frame = 61L, sensorNs = late)
        events += Event(late + 5_000_000L, session, "capture_result", frame = 61L, sensorNs = late,
            values = mapOf("frameDurationNs" to frameNs, "ae" to 2, "af" to 2, "afMode" to 4, "awb" to 2))
        val r = LiveReadout().read(events, session, late + frameNs)
        assertEquals(1, r.stalls)
        // The interval is reported as measured; nothing in the reading says whether it is acceptable.
        assertEquals(66.7, r.intervalMs!!, 0.5)
    }

    @Test fun everyRowOfThePanelPutsItsValueInTheSameColumn() {
        // The block is monospace, so the columns only line up if every label is padded to the same cell count.
        // Korean labels break this silently: padEnd counts one char where the face draws two cells.
        val lines = LiveReadout.panelText(LiveReadout().read(stream(60), session, now(60))).lines()
        assertEquals(12, lines.size)
        lines.forEach { line ->
            assertTrue(line, line.length > LiveReadout.LABEL_WIDTH)
            assertEquals(line, ' ', line[LiveReadout.LABEL_WIDTH - 1])
            assertTrue(line, line.all { it.code < 128 })
        }
    }

    @Test fun aValueThatCannotBeReadYetIsADashAndNeverAZero() {
        val lines = LiveReadout.panelText(LiveReadout().read(emptyList(), session, 0)).lines()
        assertTrue(lines[0].endsWith("—"))
        assertTrue(lines.first { it.startsWith("interval ref p50") }.endsWith("—"))
        // A count of nothing is genuinely nothing, so those two stay numeric.
        assertTrue(lines.first { it.startsWith("stalls") }.endsWith("0"))
        assertTrue(lines.first { it.startsWith("ref frames") }.endsWith("0"))
    }

    @Test fun theStripCaptionKeepsTheSignOfEachOffset() {
        assertEquals("#12  START +0  PARTIAL +5.0  BUFFER +9.0 ms", LiveReadout.stripText(12L, 5.0, 9.0))
        assertEquals("#—  START +0  PARTIAL —  BUFFER — ms", LiveReadout.stripText(null, null, null))
    }
}
