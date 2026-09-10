package dev.halcamera.benchmark

import dev.halcamera.benchmark.BenchmarkRunFixture.metric
import dev.halcamera.benchmark.BenchmarkRunFixture.run
import dev.halcamera.metrics.UnknownReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary values of the 7.2 rule table and the pairwise conditions of 7.5. Each case pins one row or one
 * condition so that a later `regression-rule-v2` shows up as a named failing test rather than a drifting number.
 */
class RegressionDetectorTest {

    private fun compare(baseValue: Double?, currentValue: Double?, id: String = "1.1"): MetricComparison =
        RegressionDetector.compare(
            run(runId = "20260910-100000-000", metrics = listOf(metric(id, baseValue))),
            run(runId = "20260910-110000-000", metrics = listOf(metric(id, currentValue)))
        ).metric(id)!!

    private fun state(base: Double, current: Double, id: String = "1.1") = compare(base, current, id).state

    // 7.2 LATENCY: both the percentage and the absolute change must be met.

    @Test fun latencyNeedsBothPercentAndFloor() {
        assertEquals(RegressionState.REGRESSED, state(100.0, 115.0))   // +15 %, +15 ms
        assertEquals(RegressionState.STABLE, state(100.0, 114.9))      // +14.9 % < 15 %
        assertEquals(RegressionState.STABLE, state(50.0, 57.5))        // +15 % but +7.5 ms < 10 ms floor
        assertEquals(RegressionState.IMPROVED, state(100.0, 85.0))
        assertEquals(RegressionState.STABLE, state(100.0, 85.1))
        assertEquals(RegressionState.STABLE, state(50.0, 42.5))        // -15 % but -7.5 ms
    }

    @Test fun configureAndCloseUseTheFiveMillisecondFloor() {
        assertEquals(RegressionState.REGRESSED, state(40.0, 46.0, "1.2"))   // +15 %, +6 ms
        assertEquals(RegressionState.STABLE, state(20.0, 23.0, "1.2"))      // +15 % but +3 ms < 5 ms
        assertEquals(RegressionState.REGRESSED, state(200.0, 230.0, "1.7"))
    }

    @Test fun intervalMetricsUseTenPercentAndTwoMillisecondFloor() {
        assertEquals(RegressionState.REGRESSED, state(33.3, 36.7, "H.1"))   // +10.2 %, +3.4 ms
        assertEquals(RegressionState.STABLE, state(33.3, 34.5, "H.1"))      // +3.6 %
        assertEquals(RegressionState.STABLE, state(15.0, 16.6, "H.2"))      // +10.7 % but +1.6 ms < 2 ms
    }

    @Test fun jitterUsesTwentyPercentAndOneMillisecondFloor() {
        // The worked example of 7.3: +122 % over a 1 ms floor.
        assertEquals(RegressionState.REGRESSED, state(3.2, 7.1, "H.10"))
        assertEquals(RegressionState.STABLE, state(3.2, 3.8, "H.10"))       // +18.8 % < 20 %
        assertEquals(RegressionState.STABLE, state(2.0, 2.5, "H.10"))       // +25 % but +0.5 ms < 1 ms
    }

    @Test fun threeAUsesThirtyPercentAndTwoHundredMillisecondFloor() {
        assertEquals(RegressionState.REGRESSED, state(500.0, 700.0, "H.7")) // +40 %, +200 ms
        assertEquals(RegressionState.STABLE, state(500.0, 690.0, "H.7"))    // +38 % but +190 ms
        assertEquals(RegressionState.STABLE, state(400.0, 520.0, "H.6"))    // +30 % but +120 ms
    }

    @Test fun captureMetricsFollowTheFifteenPercentRow() {
        // The worked example of 7.3: Capture 164 -> 221 ms.
        assertEquals(RegressionState.REGRESSED, state(164.0, 221.0, "2.2"))
        assertEquals(RegressionState.STABLE, state(142.0, 161.0))           // Open +13 % stays stable
    }

    // 7.2 COUNT: the absolute change alone decides.

    @Test fun countRulesUseTheAbsoluteChangeOnly() {
        assertEquals(RegressionState.REGRESSED, state(0.0, 2.0, "H.5"))
        assertEquals(RegressionState.STABLE, state(0.0, 1.0, "H.5"))
        assertEquals(RegressionState.IMPROVED, state(3.0, 1.0, "H.5"))
        assertEquals(RegressionState.REGRESSED, state(0.0, 1.0, "H.9"))     // floor 1
        assertEquals(RegressionState.REGRESSED, state(0.0, 2.0, "2.7"))
    }

    @Test fun aZeroLatencyBaselineFallsBackToTheAbsoluteRule() {
        assertEquals(RegressionState.REGRESSED, state(0.0, 12.0))
        assertEquals(RegressionState.STABLE, state(0.0, 4.0))
        assertNull(compare(0.0, 12.0).deltaPct)
    }

    @Test fun deltaPctIsSignedAndRelativeToTheBaseline() {
        assertEquals(13.38, compare(142.0, 161.0).deltaPct!!, 0.01)
        assertEquals(-15.0, compare(100.0, 85.0).deltaPct!!, 0.001)
    }

    // Missing values and 3A timeouts.

    @Test fun aMissingValueOnEitherSideIsUnknown() {
        assertEquals(RegressionState.UNKNOWN, compare(null, 120.0).state)
        assertEquals(RegressionState.UNKNOWN, compare(120.0, null).state)
        assertEquals(UnknownReason.NOT_RUN, compare(120.0, null).unknownReason)
        assertNull(compare(null, 120.0).deltaPct)
    }

    @Test fun theMissingSideExplainsTheMissingValue() {
        // Every metric is stored with NO_BASELINE as its default reason, so the present side never explains a gap.
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("H.4", null, unknownReason = UnknownReason.NOT_MEASURABLE)))
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("H.4", 20.0)))
        val m = RegressionDetector.compare(base, current).metric("H.4")!!
        assertEquals(UnknownReason.NOT_MEASURABLE, m.unknownReason)

        val flipped = RegressionDetector.compare(
            run(runId = "20260910-100000-000", metrics = listOf(metric("H.4", 20.0))),
            run(runId = "20260910-110000-000", metrics = listOf(metric("H.4", null, unknownReason = UnknownReason.INSUFFICIENT_SAMPLES)))
        ).metric("H.4")!!
        assertEquals(UnknownReason.INSUFFICIENT_SAMPLES, flipped.unknownReason)
    }

    @Test fun aTimedOutThreeAMetricIsNotCompared() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("H.7", 13000.0, timeout = true)))
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("H.7", 460.0)))
        val m = RegressionDetector.compare(base, current).metric("H.7")!!
        // The stored value of a timeout is the observation window length, not a convergence time.
        assertEquals(RegressionState.UNKNOWN, m.state)
        assertEquals(UnknownReason.NOT_MEASURABLE, m.unknownReason)
    }

    // 7.1 / 7.2 comparability gates.

    @Test fun noBaselineListsCurrentValuesWithoutAState() {
        val current = run(metrics = listOf(metric("1.1", 120.0)))
        val c = RegressionDetector.compare(null, current)
        assertNull(c.baseRunId)
        assertEquals(UnknownReason.NO_BASELINE, c.metric("1.1")!!.unknownReason)
        assertEquals(120.0, c.metric("1.1")!!.currentValue!!, 0.0)
        assertNull(c.metric("1.1")!!.baselineValue)
        assertFalse(c.hasRegression)
    }

    @Test fun aDifferentMetricDefinitionVersionIsNotCompared() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("1.1", 100.0)), metricDefinitionVersion = "metrics-0.2")
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("1.1", 200.0)))
        val c = RegressionDetector.compare(base, current)
        assertFalse(c.sameContract)
        assertEquals(RegressionState.UNKNOWN, c.metric("1.1")!!.state)
        assertEquals(UnknownReason.CONDITION_MISMATCH, c.metric("1.1")!!.unknownReason)
    }

    @Test fun aDifferentEndpointIsNotCompared() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("1.1", 100.0)), endpointKey = "0")
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("1.1", 200.0)), endpointKey = "2")
        val c = RegressionDetector.compare(base, current)
        assertFalse(c.sameEndpoint)
        assertEquals(RegressionState.UNKNOWN, c.metric("1.1")!!.state)
    }

    @Test fun anIneligibleRunOnEitherSideBlocksTheState() {
        val ineligible = run(runId = "20260910-100000-000", metrics = listOf(metric("1.1", 100.0)), flags = listOf(ValidityFlags.CADENCE_NOT_FIXED))
        val ok = run(runId = "20260910-110000-000", metrics = listOf(metric("1.1", 200.0)))
        assertEquals(RegressionState.UNKNOWN, RegressionDetector.compare(ineligible, ok).metric("1.1")!!.state)
        assertEquals(RegressionState.UNKNOWN, RegressionDetector.compare(ok, ineligible).metric("1.1")!!.state)
        // Charging alone keeps a run comparable (5.3): it only blocks cross-device scoring.
        val charging = run(runId = "20260910-090000-000", metrics = listOf(metric("1.1", 100.0)), charging = true, flags = listOf(ValidityFlags.CHARGING))
        val chargingToo = run(runId = "20260910-110000-000", metrics = listOf(metric("1.1", 200.0)), charging = true, flags = listOf(ValidityFlags.CHARGING))
        assertEquals(RegressionState.REGRESSED, RegressionDetector.compare(charging, chargingToo).metric("1.1")!!.state)
    }

    // 7.5 pairwise conditions.

    @Test fun twoThermalStepsBlockEveryMetricButKeepTheDelta() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("1.1", 100.0)), thermalMax = 1)
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("1.1", 200.0)), thermalMax = 3)
        val c = RegressionDetector.compare(base, current)
        assertEquals(listOf(ConditionMismatch.THERMAL_MAX_DIFFERS), c.conditionMismatches)
        assertEquals(RegressionState.UNKNOWN, c.metric("1.1")!!.state)
        assertEquals(100.0, c.metric("1.1")!!.deltaPct!!, 0.001)
    }

    @Test fun oneThermalStepIsNotAMismatch() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("1.1", 100.0)), thermalMax = 0)
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("1.1", 200.0)), thermalMax = 1)
        val c = RegressionDetector.compare(base, current)
        assertTrue(c.conditionMismatches.isEmpty())
        assertEquals(RegressionState.REGRESSED, c.metric("1.1")!!.state)
    }

    @Test fun anUnknownThermalValueIsNotAMismatch() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("1.1", 100.0)), thermalMax = null)
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("1.1", 100.0)), thermalMax = 3)
        assertTrue(RegressionDetector.compare(base, current).conditionMismatches.isEmpty())
    }

    @Test fun differentPowerSaveModeBlocksEveryMetric() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("1.1", 100.0)), powerSaveMode = false)
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("1.1", 200.0)), powerSaveMode = true, flags = listOf(ValidityFlags.POWER_SAVE_MODE))
        val c = RegressionDetector.compare(base, current)
        assertTrue(ConditionMismatch.POWER_SAVE_DIFFERS in c.conditionMismatches)
        assertEquals(RegressionState.UNKNOWN, c.metric("1.1")!!.state)
    }

    @Test fun differentChargingIsABannerAndKeepsTheStates() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("1.1", 100.0)), charging = false)
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("1.1", 200.0)), charging = true, flags = listOf(ValidityFlags.CHARGING))
        val c = RegressionDetector.compare(base, current)
        assertEquals(listOf(ConditionMismatch.CHARGING_DIFFERS), c.conditionMismatches)
        assertEquals(RegressionState.REGRESSED, c.metric("1.1")!!.state)
        assertTrue(c.hasRegression)
    }

    @Test fun aFourfoldExposureDifferenceBlocksThreeAOnly() {
        val metrics = { v: Double -> listOf(metric("1.1", v), metric("H.6", v), metric("H.7", v), metric("H.8", v)) }
        val base = run(runId = "20260910-100000-000", metrics = metrics(100.0), exposureLoad = 1.0e6)
        val current = run(runId = "20260910-110000-000", metrics = metrics(200.0), exposureLoad = 5.0e6)
        val c = RegressionDetector.compare(base, current)
        assertEquals(listOf(ConditionMismatch.EXPOSURE_DIFFERS), c.conditionMismatches)
        assertEquals(RegressionState.REGRESSED, c.metric("1.1")!!.state)
        listOf("H.6", "H.7", "H.8").forEach {
            assertEquals(it, RegressionState.UNKNOWN, c.metric(it)!!.state)
            assertEquals(it, UnknownReason.CONDITION_MISMATCH, c.metric(it)!!.unknownReason)
        }
    }

    @Test fun anExposureRatioInsideTheBandIsNotAMismatch() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("H.7", 400.0)), exposureLoad = 1.0e6)
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("H.7", 700.0)), exposureLoad = 3.9e6)
        val c = RegressionDetector.compare(base, current)
        assertTrue(c.conditionMismatches.isEmpty())
        assertEquals(RegressionState.REGRESSED, c.metric("H.7")!!.state)
    }

    @Test fun aMissingExposureLoadIsNotAMismatch() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("H.7", 400.0)), exposureLoad = null)
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("H.7", 400.0)), exposureLoad = 5.0e6)
        assertTrue(RegressionDetector.compare(base, current).conditionMismatches.isEmpty())
    }

    // 7.4 identity summary travels with the comparison.

    @Test fun theComparisonCarriesTheIdentityAxes() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("1.1", 100.0)), subject = SubjectLabel("SW41", "9c01d2e"))
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("1.1", 100.0)),
            device = BenchmarkRunFixture.DEVICE.copy(vendorFingerprint = "vendor/other"))
        val id = RegressionDetector.compare(base, current).identity
        assertNotNull(id)
        assertTrue(id!!.sameSystemFingerprint)
        assertEquals(false, id.sameCameraBuild)
        assertEquals(false, id.sameSubjectLabel)
    }

    @Test fun summaryCountsCoverEveryMetricOfBothRuns() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("1.1", 100.0), metric("H.5", 0.0)))
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("1.1", 130.0), metric("H.10", 3.0)))
        val c = RegressionDetector.compare(base, current)
        assertEquals(3, c.metrics.size)
        assertEquals(1, c.regressedCount)   // 1.1 +30 %
        assertEquals(2, c.unknownCount)     // H.5 and H.10 exist on one side only
        assertEquals(RegressionRules.VERSION, c.ruleVersion)
    }
}
