package dev.halcamera.benchmark

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
        assertEquals("regression-rule-v1", RegressionRules.VERSION)
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
