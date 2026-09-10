package dev.halcamera.metrics

import dev.halcamera.telemetry.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricExtractorTest {
    private val session = "s"
    private val frameNs = 33_333_333L
    private val x = MetricExtractor()

    private fun stream(frames: Int, gapMs: Double = 55.0, bufferMs: Double? = 60.0, ae: Int = 2, af: Int = 2, awb: Int = 2,
                       duration: Long? = frameNs, iso: Int = 100, exposureNs: Long = 8_000_000L, afMode: Int? = 4): MutableList<Event> {
        val out = mutableListOf<Event>()
        for (n in 0 until frames) {
            val start = n * frameNs
            out += Event(start, session, "capture_started", frame = n.toLong(), sensorNs = start)
            out += Event(start + (gapMs * 1e6).toLong(), session, "capture_result", frame = n.toLong(), sensorNs = start,
                values = mapOf("frameDurationNs" to duration, "ae" to ae, "af" to af, "afMode" to afMode, "awb" to awb, "iso" to iso, "exposureNs" to exposureNs))
            if (bufferMs != null) out += Event(start + (bufferMs * 1e6).toLong(), session, "image_available", sensorNs = start, values = mapOf("stream" to "yuv"))
        }
        return out
    }

    @Test fun steadyStreamValues() {
        val ev = stream(300)
        val o = x.observe(ev, session, 0, Long.MAX_VALUE)
        val byId = o.samples.associateBy { it.id }
        assertEquals(33.3, byId["H.1"]!!.value!!, 0.05)
        assertEquals(33.3, byId["H.2"]!!.value!!, 0.05)
        assertEquals(55.0, byId["H.3"]!!.value!!, 0.01)
        assertEquals(60.0, byId["H.4"]!!.value!!, 0.01)
        assertEquals(0.0, byId["H.5"]!!.value!!, 0.0)
        assertEquals(0.0, byId["H.6"]!!.value!!, 0.0)
        assertEquals(33.3, byId["H.1"]!!.expectedMs!!, 0.05)
        assertTrue(o.threeAStable)
        assertEquals(100.0 * 8.0, o.exposureLoadP50!!, 0.01)
    }

    @Test fun insufficientSamplesBoundaryAtFifteen() {
        val fourteen = x.observe(stream(14), session, 0, Long.MAX_VALUE).samples
        assertTrue(fourteen.all { it.unknownReason == UnknownReason.INSUFFICIENT_SAMPLES && it.value == null })
        val fifteen = x.observe(stream(15), session, 0, Long.MAX_VALUE).samples
        assertTrue(fifteen.none { it.unknownReason == UnknownReason.INSUFFICIENT_SAMPLES })
    }

    @Test fun stallNeedsOwnDurationExceededNotJustBaseline() {
        // Variable fps: 66.7 ms interval with 66.7 ms own duration is not a stall even against a 33 ms baseline.
        val ev = stream(20, duration = 66_666_667L)
        val frames = x.frames(ev, session, 0, Long.MAX_VALUE).map { it.copy(intervalMs = 66.7) }
        assertEquals(0, x.observe(frames, baselineIntervalMs = 33.3).stallCount)
        // Same interval with a 33 ms own duration is a stall.
        val stalled = frames.map { it.copy(ownDurationMs = 33.3) }
        assertEquals(20, x.observe(stalled, baselineIntervalMs = 33.3).stallCount)
        // No own duration: baseline-only fallback.
        val noDuration = frames.map { it.copy(ownDurationMs = null) }
        assertEquals(20, x.observe(noDuration, baselineIntervalMs = 33.3).stallCount)
        assertEquals(0, x.observe(noDuration, baselineIntervalMs = null).stallCount)
    }

    @Test fun warmupFramesAreExcludedFromStallButNotFromConvergence() {
        // Galaxy S25+ start-up artefact: frame #1 arrives 66.7 ms after frame #0 with a 33.3 ms own duration.
        val ev = stream(60, ae = 1).map { e ->
            if (e.kind == "capture_result" && e.frame == 1L) e.copy(values = e.values + ("intervalMs" to 66.7))
            else if (e.kind == "capture_result" && e.frame!! >= 3) e.copy(values = e.values + ("ae" to 2)) else e
        }
        val raw = x.observe(ev, session, 0, Long.MAX_VALUE, baselineIntervalMs = 33.3)
        assertEquals(1, raw.stallCount)
        val warm = x.observe(ev, session, 0, Long.MAX_VALUE, baselineIntervalMs = 33.3, warmupFrames = 5)
        assertEquals(0, warm.stallCount)
        assertEquals(60, warm.n)
        assertEquals(55, warm.samples.first { it.id == "H.5" }.n)
        assertTrue(x.observe(ev.take(0), session, 0, Long.MAX_VALUE, warmupFrames = 5).samples.all { it.value == null })
        // AE converged at frame 3, inside the warm-up: convergence still measured from the first result.
        assertEquals(3 * frameNs / 1e6, warm.samples.first { it.id == "H.6" }.value!!, 0.01)
    }

    @Test fun convergenceTimeAndTimeout() {
        val ev = stream(60, ae = 1).toMutableList()
        // AE converges at frame 30.
        val fixed = ev.map { e -> if (e.kind == "capture_result" && e.frame!! >= 30) e.copy(values = e.values + ("ae" to 2)) else e }
        val o = x.observe(fixed, session, 0, Long.MAX_VALUE)
        val h6 = o.samples.first { it.id == "H.6" }
        assertEquals(30 * frameNs / 1e6, h6.value!!, 0.01); assertEquals(false, h6.timeout)
        val never = x.observe(ev, session, 0, Long.MAX_VALUE).samples.first { it.id == "H.6" }
        assertEquals(true, never.timeout)
    }

    @Test fun afOffIsUnsupportedButScanningAfConverges() {
        // Galaxy S25+ ultra-wide: AF_STATE stays INACTIVE (0) for all frames, so H.7 is UNSUPPORTED rather than a 10 s timeout.
        val ev = stream(60, af = 0, afMode = 0)
        val h7 = x.observe(ev, session, 0, Long.MAX_VALUE).samples.first { it.id == "H.7" }
        assertEquals(UnknownReason.UNSUPPORTED, h7.unknownReason)
        // A lens that scans (state 1) then locks (2) is supported.
        val scanning = stream(60, af = 1).map { e -> if (e.kind == "capture_result" && e.frame!! >= 10) e.copy(values = e.values + ("af" to 2)) else e }
        assertEquals(10 * frameNs / 1e6, x.observe(scanning, session, 0, Long.MAX_VALUE).samples.first { it.id == "H.7" }.value!!, 0.01)
    }

    @Test fun enabledButInactiveAfIsNotMistakenForFixedFocus() {
        for (mode in listOf(1, 4)) {
            val observation = x.observe(stream(60, af = 0, afMode = mode), session, 0, Long.MAX_VALUE)
            assertTrue(observation.afSupported)
            assertTrue(observation.samples.first { it.id == "H.7" }.timeout)
            assertEquals(false, observation.threeAStable)
        }
    }

    @Test fun missingModeDoesNotProveInactiveAfIsUnsupported() {
        val observation = x.observe(stream(60, af = 0, afMode = null), session, 0, Long.MAX_VALUE)
        assertTrue(observation.afSupported)
        assertTrue(observation.samples.first { it.id == "H.7" }.timeout)
    }

    @Test fun oldEventsUseAfModeFromTheSameFramesObservedRequest() {
        val events = stream(60, af = 0, afMode = null).flatMap { e ->
            if (e.kind != "capture_result") listOf(e)
            else listOf(e,
                Event(e.atNs, session, "request_observed", frame = e.frame, values = mapOf("afMode" to 0)),
                Event(e.atNs, "other", "request_observed", frame = e.frame, values = mapOf("afMode" to 4)))
        }
        val observation = x.observe(events, session, 0, Long.MAX_VALUE)
        assertEquals(false, observation.afSupported)
        assertEquals(UnknownReason.UNSUPPORTED, observation.samples.first { it.id == "H.7" }.unknownReason)
        assertTrue(observation.threeAStable)
    }

    @Test fun effectiveAfModeTakesPriorityOverRequestedMode() {
        val events = stream(60, af = 0, afMode = 4).flatMap { e ->
            if (e.kind != "capture_result") listOf(e)
            else listOf(e, Event(e.atNs, session, "request_observed", frame = e.frame, values = mapOf("afMode" to 0)))
        }
        assertTrue(x.observe(events, session, 0, Long.MAX_VALUE).afSupported)
        assertEquals(UnknownReason.UNSUPPORTED,
            x.observe(stream(60, af = 0, afMode = 5), session, 0, Long.MAX_VALUE).samples.first { it.id == "H.7" }.unknownReason)
    }

    @Test fun missingAfIsUnsupported() {
        val ev = stream(30).map { e -> if (e.kind == "capture_result") e.copy(values = e.values - "af") else e }
        val h7 = x.observe(ev, session, 0, Long.MAX_VALUE).samples.first { it.id == "H.7" }
        assertEquals(UnknownReason.UNSUPPORTED, h7.unknownReason)
    }

    @Test fun maxAggregationSeesSingleSpike() {
        val ev = stream(60)
        val n = 60L; val start = n * frameNs
        ev += Event(start, session, "capture_started", frame = n, sensorNs = start)
        ev += Event(start + 95_000_000L, session, "capture_result", frame = n, sensorNs = start, values = mapOf("frameDurationNs" to frameNs))
        val p50 = x.observe(ev, session, 0, Long.MAX_VALUE).samples.first { it.id == "H.3" }.value!!
        val max = x.observe(ev, session, 0, Long.MAX_VALUE, aggregation = MetricExtractor.Aggregation.MAX).samples.first { it.id == "H.3" }.value!!
        assertEquals(55.0, p50, 0.01); assertEquals(95.0, max, 0.01)
    }

    @Test fun failureSampleCountsBothKinds() {
        val ev = stream(20)
        ev += Event(5, session, "capture_failed", frame = 1)
        ev += Event(6, session, "buffer_lost", frame = 2)
        ev += Event(7, "other", "buffer_lost", frame = 2)
        assertEquals(2.0, x.failureSample(ev, session, 0, Long.MAX_VALUE).value!!, 0.0)
    }

    @Test fun nearestRankPercentile() {
        val xs = (1..9).map { it.toDouble() }
        assertEquals(5.0, MetricExtractor.percentile(xs, 0.5)!!, 0.0)
        assertEquals(9.0, MetricExtractor.percentile(xs, 0.95)!!, 0.0)
        assertNull(MetricExtractor.percentile(emptyList(), 0.5))
    }
}
