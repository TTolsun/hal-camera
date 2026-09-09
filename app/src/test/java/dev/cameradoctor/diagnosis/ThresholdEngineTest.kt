package dev.cameradoctor.diagnosis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdEngineTest {
    private val engine = ThresholdEngine()
    private val mpcU = DeviceContext(mediaPerformanceClass = 34, primaryCamera = true)
    private val noMpc = DeviceContext(mediaPerformanceClass = 0, primaryCamera = true)

    private fun one(sample: MetricSample, baseline: BaselineValue? = null, device: DeviceContext = noMpc) =
        engine.evaluate(sample, baseline, device)

    // 5.6 hard failure ignores thresholds
    @Test fun hardFailureIsFailWithHardBasis() {
        val s = one(MetricSample("1.1", null, hardFailure = true))
        assertEquals(State.FAIL, s.final)
        assertEquals(ThresholdBasis.HARD, s.thresholdBasis)
        assertTrue(s.isHardFailure)
        assertEquals("watchdog_v0.2", s.absoluteSource)
    }

    // 5.3 no baseline: relative-only metric is UNKNOWN(no_baseline), never PASS
    @Test fun relativeOnlyWithoutBaselineIsUnknownNoBaseline() {
        val s = one(MetricSample("1.1", 120.0, n = 1))
        assertEquals(State.UNKNOWN, s.final)
        assertEquals(UnknownReason.NO_BASELINE, s.unknownReason)
        assertNull(s.thresholdBasis)
    }

    // 6 relative boundaries: +30 WARN, +100 FAIL(relative), noise floor 10 ms
    @Test fun relativeBoundaries() {
        val b = BaselineValue(100.0)
        assertEquals(State.PASS, one(MetricSample("1.2", 129.0, n = 1), b).final)
        val warn = one(MetricSample("1.2", 130.0, n = 1), b)
        assertEquals(State.WARN, warn.final); assertEquals(ThresholdBasis.RELATIVE, warn.thresholdBasis)
        val fail = one(MetricSample("1.2", 200.0, n = 1), b)
        assertEquals(State.FAIL, fail.final); assertEquals(ThresholdBasis.RELATIVE, fail.thresholdBasis)
        assertFalse(fail.isHardFailure)
    }

    @Test fun openLatencyUsesFiftyMsNoiseFloor() {
        // S25+ front camera: 16 ms baseline, 44 ms this run (+170 %) is jitter, not a finding.
        assertEquals(State.PASS, one(MetricSample("1.1", 44.0, n = 1), BaselineValue(16.0)).final)
        assertEquals(State.FAIL, one(MetricSample("1.1", 70.0, n = 1), BaselineValue(16.0)).final)
        assertEquals(State.PASS, one(MetricSample("1.7", 60.0, n = 1), BaselineValue(20.0)).final)
    }

    @Test fun noiseFloorBoundaryIsInclusiveAtTenMs() {
        // Exactly 10 ms over a 20 ms baseline is +50 %: the floor no longer suppresses, so WARN.
        assertEquals(State.WARN, one(MetricSample("H.3", 30.0, n = 30), BaselineValue(20.0)).final)
        assertEquals(State.PASS, one(MetricSample("H.3", 29.99, n = 30), BaselineValue(20.0)).final)
    }

    @Test fun noiseFloorSuppressesRelativeWarnOnTinyValues() {
        // 10 ms -> 13.5 ms is +35 % but only 3.5 ms: no WARN.
        val s = one(MetricSample("H.3", 13.5, n = 30), BaselineValue(10.0))
        assertEquals(State.PASS, s.final)
        assertEquals(35.0, s.deltaPct!!, 0.01)
    }

    // 5.4 worst-of conflicts
    @Test fun absolutePassRelativeWarnIsWarn() {
        val s = one(MetricSample("1.6", 300.0, n = 1), BaselineValue(200.0), mpcU)
        assertEquals(State.PASS, s.absolute); assertEquals(State.WARN, s.relative)
        assertEquals(State.WARN, s.final); assertEquals(ThresholdBasis.RELATIVE, s.thresholdBasis)
    }

    @Test fun absoluteWarnRelativePassIsWarnWithReferenceBasis() {
        val s = one(MetricSample("1.6", 600.0, n = 1), BaselineValue(600.0), mpcU)
        assertEquals(State.WARN, s.absolute); assertEquals(State.PASS, s.relative)
        assertEquals(State.WARN, s.final); assertEquals(ThresholdBasis.ABSOLUTE_REFERENCE, s.thresholdBasis)
    }

    @Test fun absolutePassRelativeUnknownIsPass() {
        val s = one(MetricSample("1.6", 300.0, n = 1), null, mpcU)
        assertEquals(State.PASS, s.final); assertNull(s.unknownReason)
    }

    // 5.2 CDD gate and condition equivalence
    @Test fun cddBoundUnderSimilarConditionIsWarnNotFail() {
        val s = one(MetricSample("1.6", 700.0, n = 1), null, mpcU)
        assertEquals(State.WARN, s.final)
        assertEquals(ThresholdBasis.ABSOLUTE_REFERENCE, s.thresholdBasis)
        assertEquals(CddApplicability.APPLICABLE, s.cddApplicability)
        assertEquals(ConditionEquivalence.SIMILAR, s.conditionEquivalence)
        assertEquals("cdd_2.2.7.2_H-1-6", s.absoluteSource)
    }

    @Test fun cddBoundUnderEquivalentConditionIsValidatedFail() {
        val eq = mpcU.copy(conditionEquivalence = ConditionEquivalence.EQUIVALENT)
        val s = one(MetricSample("2.2", 1000.0, n = 3), null, eq)
        assertEquals(State.FAIL, s.final)
        assertEquals(ThresholdBasis.ABSOLUTE_VALIDATED, s.thresholdBasis)
        assertEquals("cdd_2.2.7.2_H-1-5", s.absoluteSource)
    }

    @Test fun cddNotApplicableBelowU() {
        val t = DeviceContext(mediaPerformanceClass = 33, primaryCamera = true)
        // Non-MPC devices: product heuristic at 1.5x / 2x the CDD value (S25+ sits at 550-620 ms normally).
        assertEquals(State.PASS, one(MetricSample("1.6", 700.0, n = 1), null, t).final)
        val s = one(MetricSample("1.6", 750.0, n = 1), null, t)
        assertEquals(CddApplicability.NOT_APPLICABLE, s.cddApplicability)
        assertEquals(State.WARN, s.final); assertEquals(ThresholdBasis.HEURISTIC, s.thresholdBasis)
        val fail = one(MetricSample("1.6", 1000.0, n = 1), null, t)
        assertEquals(State.FAIL, fail.final); assertEquals(ThresholdBasis.HEURISTIC, fail.thresholdBasis)
    }

    @Test fun cddNotApplicableForNonPrimaryCamera() {
        val tele = DeviceContext(mediaPerformanceClass = 35, primaryCamera = false)
        assertEquals(CddApplicability.NOT_APPLICABLE, one(MetricSample("1.6", 100.0, n = 1), null, tele).cddApplicability)
    }

    @Test fun nonCddMetricHasNullCddFields() {
        val s = one(MetricSample("1.1", 100.0, n = 1), BaselineValue(100.0), mpcU)
        assertNull(s.cddApplicability); assertNull(s.conditionEquivalence)
    }

    // H.1: variable fps [15,30] at 66.7 ms is not a WARN when the frame's own duration is 66.7 ms
    @Test fun variableFpsIntervalMatchingOwnDurationIsPass() {
        val s = one(MetricSample("H.1", 66.7, n = 30, expectedMs = 66.7, fixedCadence = false))
        assertEquals(State.PASS, s.absolute)
        assertEquals(State.UNKNOWN, s.relative)
        assertEquals(State.PASS, s.final)
    }

    @Test fun fixedFpsIntervalBeyondTwentyPercentIsWarn() {
        val s = one(MetricSample("H.1", 41.0, n = 30, expectedMs = 33.3, fixedCadence = true))
        assertEquals(State.WARN, s.final); assertEquals(ThresholdBasis.HEURISTIC, s.thresholdBasis)
        assertEquals(State.PASS, one(MetricSample("H.1", 39.9, n = 30, expectedMs = 33.3, fixedCadence = true)).final)
    }

    @Test fun cadenceWithoutAnyDurationIsUnknownCadenceChanged() {
        val s = one(MetricSample("H.1", 66.7, n = 30, expectedMs = null))
        assertEquals(State.UNKNOWN, s.final)
        assertEquals(UnknownReason.NO_BASELINE, s.unknownReason)
        val withBaseline = one(MetricSample("H.1", 66.7, n = 30, expectedMs = null), BaselineValue(66.0))
        assertEquals(State.PASS, withBaseline.final)
    }

    // H.5 stall counts are heuristic
    @Test fun stallCountBoundaries() {
        assertEquals(State.PASS, one(MetricSample("H.5", 0.0, n = 300)).final)
        val warn = one(MetricSample("H.5", 2.0, n = 300))
        assertEquals(State.WARN, warn.final); assertEquals(ThresholdBasis.HEURISTIC, warn.thresholdBasis)
        val fail = one(MetricSample("H.5", 3.0, n = 300))
        assertEquals(State.FAIL, fail.final); assertEquals(ThresholdBasis.HEURISTIC, fail.thresholdBasis)
        assertEquals("product_stability_v0.2", fail.absoluteSource)
    }

    // 3A never FAILs in v0.2
    @Test fun threeAConvergenceTimeoutIsWarnNotFail() {
        val s = one(MetricSample("H.7", 5000.0, n = 300, timeout = true))
        assertEquals(State.WARN, s.final); assertEquals(ThresholdBasis.HEURISTIC, s.thresholdBasis)
        assertEquals(State.PASS, one(MetricSample("H.7", 1500.0, n = 300)).final)
        assertEquals(State.WARN, one(MetricSample("H.7", 1501.0, n = 300)).final)
        assertEquals(State.WARN, one(MetricSample("H.6", 900.0, n = 300), BaselineValue(500.0)).final)
    }

    @Test fun fixedFocusIsUnknownUnsupported() {
        val s = one(MetricSample("H.7", null, n = 300, unknownReason = UnknownReason.UNSUPPORTED))
        assertEquals(State.UNKNOWN, s.final); assertEquals(UnknownReason.UNSUPPORTED, s.unknownReason)
    }

    // H.9 failures are hard
    @Test fun callbackFailureIsHardFail() {
        val s = one(MetricSample("H.9", 1.0, n = 1))
        assertEquals(State.FAIL, s.final); assertEquals(ThresholdBasis.HARD, s.thresholdBasis); assertTrue(s.isHardFailure)
        assertEquals(State.PASS, one(MetricSample("H.9", 0.0, n = 0)).final)
    }

    @Test fun unknownAlwaysCarriesReason() {
        listOf(
            one(MetricSample("1.1", null)),
            one(MetricSample("3.3", 1.0, n = 1)),
            one(MetricSample("H.7", null, unknownReason = UnknownReason.UNSUPPORTED)),
            one(MetricSample("9.9", 1.0))
        ).forEach { assertEquals(State.UNKNOWN, it.final); assertTrue(it.id, it.unknownReason != null) }
    }

    @Test fun jsonNamesAreSnakeCase() {
        assertEquals("no_baseline", UnknownReason.NO_BASELINE.jsonName)
        assertEquals("absolute_reference", ThresholdBasis.ABSOLUTE_REFERENCE.jsonName)
        val m = one(MetricSample("1.6", 700.0, n = 1), null, mpcU).toJsonMap()
        assertEquals("warn", m["final"]); assertEquals("similar", m["condition_equivalence"]); assertEquals("applicable", m["cdd_applicability"])
    }
}
