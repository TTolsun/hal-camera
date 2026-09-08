package dev.cameradoctor.diagnosis

import dev.cameradoctor.telemetry.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthMonitorTest {
    private val session = "s"
    private val frameNs = 33_333_333L

    /** Steady 30 fps stream: frame n starts at n*33ms, result arrives resultGapMs later. */
    private fun steady(frames: Int, resultGapMs: Double = 55.0, ae: Int = 2, af: Int = 2, awb: Int = 2): MutableList<Event> {
        val out = mutableListOf<Event>()
        for (n in 0 until frames) {
            val start = n * frameNs
            out += Event(start, session, "capture_started", frame = n.toLong(), sensorNs = start)
            out += Event(start + (resultGapMs * 1e6).toLong(), session, "capture_result", frame = n.toLong(), sensorNs = start,
                values = mapOf("intervalMs" to 33.3, "frameDurationNs" to frameNs, "ae" to ae, "af" to af, "awb" to awb))
        }
        return out
    }

    @Test
    fun steadyStreamIsOk() {
        val events = steady(120)
        val now = events.last().atNs + 1
        val a = HealthMonitor().assess(events, session, now)
        assertEquals(HealthLevel.OK, a.level)
        assertEquals(33.3, a.tRefMs!!, 0.01)
    }

    @Test
    fun normalPipelineDepthIsNotAWarning() {
        // A 55 ms start-to-result gap is the device's normal depth and must not trip the banner by itself.
        val a = HealthMonitor().assess(steady(120, resultGapMs = 55.0), session, 120 * frameNs)
        assertEquals(HealthLevel.OK, a.level)
    }

    @Test
    fun intervalSpikeIsWarningRelativeToBaseline() {
        val events = steady(120)
        val n = 120L; val start = n * frameNs + 40_000_000L
        events += Event(start, session, "capture_started", frame = n, sensorNs = start)
        events += Event(start + 55_000_000L, session, "capture_result", frame = n, sensorNs = start,
            values = mapOf("intervalMs" to 73.3, "ae" to 2, "af" to 2, "awb" to 2))
        val a = HealthMonitor().assess(events, session, events.last().atNs + 1)
        assertEquals(HealthLevel.WARNING, a.level)
        assertTrue(a.headline, a.headline.contains("SENSOR STALL"))
        assertEquals(true, a.values["intervalAnomaly"])
    }

    @Test
    fun callbackDelayIsWarningOnlyAgainstOwnBaseline() {
        val events = steady(120, resultGapMs = 55.0)
        val n = 120L; val start = n * frameNs
        events += Event(start, session, "capture_started", frame = n, sensorNs = start)
        events += Event(start + 95_000_000L, session, "capture_result", frame = n, sensorNs = start,
            values = mapOf("intervalMs" to 33.3, "ae" to 2, "af" to 2, "awb" to 2))
        val a = HealthMonitor().assess(events, session, events.last().atNs + 1)
        assertEquals(HealthLevel.WARNING, a.level)
        assertTrue(a.headline, a.headline.contains("PARTIAL DELAY"))
    }

    @Test
    fun variableFpsCadenceChangeIsWatchNotWarning() {
        // AE lengthens the frame duration to 66.7 ms (15 fps) in low light. The interval matches the frame's own duration,
        // so it is a cadence change, not a stall, even though it exceeds 1.5x the 30 fps baseline.
        val events = steady(120)
        val slow = 66_666_667L
        var start = 120 * frameNs
        for (k in 0 until 20) {
            val n = 120L + k
            events += Event(start, session, "capture_started", frame = n, sensorNs = start)
            events += Event(start + 55_000_000L, session, "capture_result", frame = n, sensorNs = start,
                values = mapOf("intervalMs" to 66.7, "frameDurationNs" to slow, "ae" to 2, "af" to 2, "awb" to 2))
            start += slow
        }
        val a = HealthMonitor().assess(events, session, events.last().atNs + 1)
        assertEquals(a.headline, HealthLevel.WATCH, a.level)
        assertEquals(true, a.values["cadenceChanged"])
        assertEquals(false, a.values["intervalAnomaly"])
    }

    @Test
    fun intervalBeyondOwnFrameDurationIsStillAStall() {
        val events = steady(120)
        val n = 120L; val start = n * frameNs + 40_000_000L
        events += Event(start, session, "capture_started", frame = n, sensorNs = start)
        events += Event(start + 55_000_000L, session, "capture_result", frame = n, sensorNs = start,
            values = mapOf("intervalMs" to 73.3, "frameDurationNs" to frameNs, "ae" to 2, "af" to 2, "awb" to 2))
        val a = HealthMonitor().assess(events, session, events.last().atNs + 1)
        assertEquals(HealthLevel.WARNING, a.level)
    }

    @Test
    fun bothAnomaliesArePipelineStall() {
        val events = steady(120)
        val n = 120L; val start = n * frameNs + 40_000_000L
        events += Event(start, session, "capture_started", frame = n, sensorNs = start)
        events += Event(start + 95_000_000L, session, "capture_result", frame = n, sensorNs = start,
            values = mapOf("intervalMs" to 73.3, "frameDurationNs" to frameNs, "ae" to 2, "af" to 2, "awb" to 2))
        val a = HealthMonitor().assess(events, session, events.last().atNs + 1)
        assertEquals(HealthLevel.WARNING, a.level)
        assertEquals("pipeline_stall", a.values["case"])
        assertEquals(120L, a.values["focusFrame"])
    }

    @Test
    fun caseValuesFollowTheTable() {
        assertEquals("normal", HealthMonitor().assess(steady(120), session, 120 * frameNs).values["case"])
        val events = steady(120)
        val n = 120L; val start = n * frameNs
        events += Event(start, session, "capture_started", frame = n, sensorNs = start)
        events += Event(start + 95_000_000L, session, "capture_result", frame = n, sensorNs = start,
            values = mapOf("intervalMs" to 33.3, "frameDurationNs" to frameNs, "ae" to 2, "af" to 2, "awb" to 2))
        assertEquals("callback_delay", HealthMonitor().assess(events, session, events.last().atNs + 1).values["case"])
    }

    @Test
    fun convergingThreeAIsWatchNotWarning() {
        val a = HealthMonitor().assess(steady(120, ae = 1), session, 120 * frameNs)
        assertEquals(HealthLevel.WATCH, a.level)
    }

    @Test
    fun tooFewFramesIsNoData() {
        val a = HealthMonitor().assess(steady(5), session, 5 * frameNs)
        assertEquals(HealthLevel.NO_DATA, a.level)
    }

    @Test
    fun warningIsHeldBrieflyThenClears() {
        val monitor = HealthMonitor()
        val events = steady(120)
        val n = 120L; val start = n * frameNs + 40_000_000L
        events += Event(start, session, "capture_started", frame = n, sensorNs = start)
        events += Event(start + 55_000_000L, session, "capture_result", frame = n, sensorNs = start,
            values = mapOf("intervalMs" to 73.3, "ae" to 2, "af" to 2, "awb" to 2))
        val spikeAt = events.last().atNs + 1
        assertEquals(HealthLevel.WARNING, monitor.assess(events, session, spikeAt).level)
        // Steady frames keep arriving after the spike. Two seconds later the warning is still held; after the hold it clears.
        fun steadyUntil(t: Long, firstFrame: Long) = (0 until 30).flatMap { i ->
            val s = t - 1_000_000_000L + i * frameNs
            listOf(Event(s, session, "capture_started", frame = firstFrame + i, sensorNs = s),
                Event(s + 55_000_000L, session, "capture_result", frame = firstFrame + i, sensorNs = s, values = mapOf("intervalMs" to 33.3, "ae" to 2, "af" to 2, "awb" to 2)))
        }
        val heldNow = spikeAt + 2_000_000_000L
        assertEquals(HealthLevel.WARNING, monitor.assess(events + steadyUntil(heldNow, 200), session, heldNow).level)
        val clearNow = spikeAt + 4_000_000_000L
        assertEquals(HealthLevel.OK, monitor.assess(events + steadyUntil(heldNow, 200) + steadyUntil(clearNow, 300), session, clearNow).level)
    }
}
