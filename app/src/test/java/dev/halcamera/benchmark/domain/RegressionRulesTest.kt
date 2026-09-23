package dev.halcamera.benchmark.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class RegressionRulesTest {
    @Test fun everyBenchmarkMetricHasARule() {
        for (id in BenchmarkMetrics.ALL) assertNotNull("rule for $id", RegressionRules.rule(id))
    }

    @Test fun countRulesHaveNoPercentageAndLatencyRulesDo() {
        for (r in RegressionRules.rules.values) {
            when (r.kind) {
                RuleKind.COUNT -> assertNull(r.metricId, r.deltaPct)
                RuleKind.LATENCY -> assertNotNull(r.metricId, r.deltaPct)
            }
        }
    }

    @Test fun tableValuesFromPlanSection72() {
        fun r(id: String) = RegressionRules.rule(id)!!
        assertEquals(15.0, r("1.1").deltaPct!!, 0.0); assertEquals(10.0, r("1.1").noiseFloor, 0.0)
        assertEquals(15.0, r("1.2").deltaPct!!, 0.0); assertEquals(5.0, r("1.2").noiseFloor, 0.0)
        assertEquals(15.0, r("2.2").deltaPct!!, 0.0); assertEquals(10.0, r("2.2").noiseFloor, 0.0)
        assertEquals(10.0, r("H.1").deltaPct!!, 0.0); assertEquals(2.0, r("H.1").noiseFloor, 0.0)
        assertEquals(15.0, r("H.3").deltaPct!!, 0.0); assertEquals(5.0, r("H.3").noiseFloor, 0.0)
        assertEquals(20.0, r("H.10").deltaPct!!, 0.0); assertEquals(1.0, r("H.10").noiseFloor, 0.0)
        assertEquals(30.0, r("H.6").deltaPct!!, 0.0); assertEquals(200.0, r("H.6").noiseFloor, 0.0)
        assertEquals(2.0, r("H.5").noiseFloor, 0.0)
        assertEquals(1.0, r("H.9").noiseFloor, 0.0)
        assertEquals("regression-rule-v2", RegressionRules.VERSION)
    }

    @Test fun theRecordingRulesAddTheOnlyHigherIsBetterMetric() {
        fun r(id: String) = RegressionRules.rule(id)!!
        assertEquals(15.0, r("3.1").deltaPct!!, 0.0); assertEquals(10.0, r("3.1").noiseFloor, 0.0)
        // stop() finalizes the file, so its noise floor is the widest in the table.
        assertEquals(15.0, r("3.6").deltaPct!!, 0.0); assertEquals(20.0, r("3.6").noiseFloor, 0.0)
        assertEquals(20.0, r("3.7").deltaPct!!, 0.0); assertEquals(1.0, r("3.7").noiseFloor, 0.0)
        assertEquals(2.0, r("3.2").noiseFloor, 0.0)
        assertEquals(RuleKind.COUNT, r("3.2").kind)
        // Steady fps is the one metric whose larger value is the better one.
        assertEquals(Direction.HIGHER_IS_BETTER, r("3.4").direction)
        assertEquals(5.0, r("3.4").deltaPct!!, 0.0); assertEquals(1.0, r("3.4").noiseFloor, 0.0)
        assertEquals(
            listOf("3.4"),
            RegressionRules.rules.values.filter { it.direction == Direction.HIGHER_IS_BETTER }.map { it.metricId }
        )
    }

    @Test fun aHigherIsBetterMetricRegressesWhenItFalls() {
        val rule = RegressionRules.rule("3.4")!!
        // 30 fps down to 28: a 6.7 % drop and 2 fps, past both thresholds.
        assertEquals(RegressionState.REGRESSED, RegressionDetector.state(rule, 30.0, 28.0))
        assertEquals(RegressionState.IMPROVED, RegressionDetector.state(rule, 28.0, 30.0))
        // Within the noise floor of 1 fps, a 3.3 % change is not a regression.
        assertEquals(RegressionState.STABLE, RegressionDetector.state(rule, 30.0, 29.0))
        // Below the percentage even though the absolute change clears the floor.
        assertEquals(RegressionState.STABLE, RegressionDetector.state(rule, 60.0, 58.0))
    }

    @Test fun countRuleWithPercentageIsRejected() {
        try {
            RegressionRule("x", RuleKind.COUNT, Direction.LOWER_IS_BETTER, 10.0, 1.0)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { }
        try {
            RegressionRule("y", RuleKind.LATENCY, Direction.LOWER_IS_BETTER, null, 1.0)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { }
    }
}
