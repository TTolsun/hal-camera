package dev.cameradoctor.check

import dev.cameradoctor.check.AutoCheckRunner.Step
import dev.cameradoctor.diagnosis.BaselineValue
import dev.cameradoctor.diagnosis.HealthLevelV2
import dev.cameradoctor.diagnosis.State
import dev.cameradoctor.diagnosis.ThresholdBasis
import dev.cameradoctor.diagnosis.UnknownReason
import dev.cameradoctor.telemetry.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckEvaluatorTest {
    private val session = "check-0-1"
    private val frameNs = 33_333_333L
    private val evaluator = CheckEvaluator()
    private fun ep(primary: Boolean = true) = CameraEndpoint("0", null, if (primary) LensRole.MAIN else LensRole.TELE, 1, true, false, null, 24.0, 1, 3, 1f, 10f)

    /** 10 s of steady 30 fps results inside [from, from + 10 s]. */
    private fun steady(from: Long, frames: Int = 300, gapMs: Double = 55.0, af: Int? = 2): List<Event> = (0 until frames).flatMap { n ->
        val start = from + n * frameNs
        val values = mutableMapOf<String, Any?>("intervalMs" to 33.3, "frameDurationNs" to frameNs, "ae" to 2, "awb" to 2, "iso" to 100, "exposureNs" to 8_000_000L)
        if (af != null) values["af"] = af
        listOf(Event(start, session, "capture_started", frame = n.toLong(), sensorNs = start),
            Event(start + (gapMs * 1e6).toLong(), session, "capture_result", frame = n.toLong(), sensorNs = start, values = values),
            Event(start + 60_000_000L, session, "image_available", sensorNs = start, values = mapOf("stream" to "analysis_acquire_latest")))
    }

    private fun result(observeStart: Long?, failed: Step? = null, open: Double? = 120.0, preview: Double? = 360.0, stills: List<Double> = listOf(400.0, 380.0, 390.0), primary: Boolean = true) =
        AutoCheckRunner.EndpointResult(ep(primary), session, failed?.name, failed, open, 80.0, 10.0, 160.0, preview, 50.0, stills, listOf(300.0, 290.0, 295.0), listOf(400.0, 410.0),
            observeStart, observeStart?.plus(10_000_000_000L), emptyMap())

    @Test fun firstRunHasNoBaselineButAbsoluteJudgementsAndNoScore() {
        val start = 5_000_000_000L
        val ev = evaluator.evaluate(result(start), steady(start), baseline = null, mediaPerformanceClass = 35)
        val byId = ev.states.associateBy { it.id }
        assertEquals(State.PASS, byId["1.6"]!!.final)                        // 360 ms < 500 ms CDD reference
        assertEquals(State.UNKNOWN, byId["1.1"]!!.final)                     // relative-only, no baseline
        assertEquals(UnknownReason.NO_BASELINE, byId["1.1"]!!.unknownReason)
        assertEquals(State.PASS, byId["H.5"]!!.final)
        assertEquals(State.PASS, byId["H.9"]!!.final)
        assertEquals(UnknownReason.NOT_RUN, byId["3.4"]!!.unknownReason)
        assertEquals(UnknownReason.INSUFFICIENT_SAMPLES, byId["2.5"]!!.unknownReason)
        assertNull(ev.health.score)
        assertEquals(HealthLevelV2.NORMAL, ev.health.level)
        assertNotNull(ev.baselineCandidate)
        assertTrue(ev.baselineCandidate!!.containsKey("1.1"))
        assertEquals("normal", ev.diagnosis.rule)
    }

    @Test fun secondRunSlowerThanBaselineIsWarningNotIssue() {
        val start = 5_000_000_000L
        val partial = mapOf("1.1" to BaselineValue(100.0), "1.6" to BaselineValue(300.0), "2.2" to BaselineValue(380.0), "H.3" to BaselineValue(55.0), "H.1" to BaselineValue(33.3))
        // A partial baseline leaves too many relative-only metrics UNKNOWN: coverage < 0.7 and therefore no score.
        assertNull(evaluator.evaluate(result(start, open = 250.0), steady(start), partial, 35).health.score)
        val baseline = partial + mapOf("1.2" to BaselineValue(80.0), "1.3" to BaselineValue(10.0), "1.8" to BaselineValue(160.0), "2.3" to BaselineValue(300.0),
            "H.2" to BaselineValue(34.0), "H.4" to BaselineValue(60.0))
        val ev = evaluator.evaluate(result(start, open = 250.0), steady(start), baseline, 35)
        val s11 = ev.states.first { it.id == "1.1" }
        assertEquals(State.FAIL, s11.final)                                  // +150 % relative
        assertEquals(ThresholdBasis.RELATIVE, s11.thresholdBasis)
        assertEquals(HealthLevelV2.WARNING, ev.health.level)                 // relative FAIL never promotes to ISSUE
        assertEquals("slower_than_baseline", ev.diagnosis.rule)
        assertNotNull(ev.health.score)
    }

    @Test fun cddReferenceExceededUnderSimilarConditionIsWarn() {
        val start = 5_000_000_000L
        val ev = evaluator.evaluate(result(start, preview = 700.0), steady(start), null, 35)
        val s = ev.states.first { it.id == "1.6" }
        assertEquals(State.WARN, s.final); assertEquals(ThresholdBasis.ABSOLUTE_REFERENCE, s.thresholdBasis)
        assertEquals("cdd_reference_exceeded", ev.diagnosis.rule)
        // Same value on a non-primary lens is a heuristic WARN, not a CDD reference.
        val tele = evaluator.evaluate(result(start, preview = 700.0, primary = false), steady(start), null, 35).states.first { it.id == "1.6" }
        assertEquals(ThresholdBasis.HEURISTIC, tele.thresholdBasis)
    }

    @Test fun openTimeoutIsHardFailureAndIssue() {
        val ev = evaluator.evaluate(result(null, failed = Step.OPEN, open = null, preview = null, stills = emptyList()), emptyList(), null, 35)
        val s = ev.states.first { it.id == "1.1" }
        assertTrue(s.isHardFailure)
        assertEquals(HealthLevelV2.ISSUE, ev.health.level)
        assertEquals("hard_failure", ev.diagnosis.rule)
        assertNull(ev.baselineCandidate)
        assertEquals(UnknownReason.NOT_RUN, ev.states.first { it.id == "H.1" }.unknownReason)
    }

    @Test fun fixedFocusIsUnsupportedAndDoesNotBlockBaseline() {
        val start = 5_000_000_000L
        val ev = evaluator.evaluate(result(start), steady(start, af = null), null, 35)
        assertEquals(UnknownReason.UNSUPPORTED, ev.states.first { it.id == "H.7" }.unknownReason)
        assertNotNull(ev.baselineCandidate)
    }

    @Test fun overallIsWorstEndpoint() {
        val start = 5_000_000_000L
        val ok = evaluator.evaluate(result(start), steady(start), null, 35)
        val bad = evaluator.evaluate(result(null, failed = Step.OPEN, open = null, preview = null, stills = emptyList()), emptyList(), null, 35)
        assertEquals(HealthLevelV2.ISSUE, evaluator.overall(listOf(ok, bad)))
        assertEquals(HealthLevelV2.NORMAL, evaluator.overall(listOf(ok)))
    }
}
