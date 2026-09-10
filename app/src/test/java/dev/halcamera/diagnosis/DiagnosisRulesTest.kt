package dev.halcamera.diagnosis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosisRulesTest {
    private val rules = DiagnosisRules()
    private fun st(id: String, final: State, basis: ThresholdBasis? = null) = MetricState(
        id, 10.0, null, 30, State.UNKNOWN, null, null, State.UNKNOWN, null, null, final, basis, null, null, null
    )
    private val pass = listOf("H.1", "H.2", "H.3", "H.4", "H.5", "H.9").map { st(it, State.PASS) }
    private fun with(vararg over: MetricState) = pass.map { p -> over.firstOrNull { it.id == p.id } ?: p }

    @Test fun allPassIsNormal() {
        val d = rules.diagnose(pass)
        assertEquals("normal", d.rule); assertEquals(CauseLayer.UNATTRIBUTED, d.causeLayer)
    }

    @Test fun stallOnlyIsSensorStall() {
        val d = rules.diagnose(with(st("H.5", State.WARN, ThresholdBasis.HEURISTIC)))
        assertEquals("sensor_stall", d.rule); assertEquals(CauseLayer.SENSOR_FRONT, d.causeLayer)
    }

    @Test fun partialOnlyIsCallbackDelay() {
        val d = rules.diagnose(with(st("H.3", State.WARN, ThresholdBasis.RELATIVE)))
        assertEquals("callback_delay", d.rule); assertEquals(CauseLayer.FRAMEWORK_CALLBACK, d.causeLayer)
    }

    @Test fun bufferLatencyAloneIsAlsoCallbackDelay() {
        assertEquals("callback_delay", rules.diagnose(with(st("H.4", State.WARN, ThresholdBasis.RELATIVE))).rule)
        assertEquals("pipeline_stall", rules.diagnose(with(st("H.4", State.WARN, ThresholdBasis.RELATIVE), st("H.5", State.FAIL, ThresholdBasis.HEURISTIC))).rule)
    }

    @Test fun bothIsPipelineStall() {
        val d = rules.diagnose(with(st("H.5", State.FAIL, ThresholdBasis.HEURISTIC), st("H.3", State.WARN, ThresholdBasis.RELATIVE)))
        assertEquals("pipeline_stall", d.rule)
    }

    @Test fun hardFailureOutranksEverything() {
        val d = rules.diagnose(with(st("H.9", State.FAIL, ThresholdBasis.HARD), st("H.5", State.FAIL, ThresholdBasis.HEURISTIC)))
        assertEquals("hard_failure", d.rule)
        assertTrue(d.secondaryRules.contains("sensor_stall"))
    }

    @Test fun cadenceChangeWithoutAnomalyIsCadenceChange() {
        val d = rules.diagnose(pass, DiagnosisRules.Context(cadenceChanged = true))
        assertEquals("cadence_change", d.rule)
    }

    @Test fun cddReferenceIsBelowStallButAboveBaseline() {
        val d = rules.diagnose(pass + st("1.6", State.WARN, ThresholdBasis.ABSOLUTE_REFERENCE) + st("1.1", State.WARN, ThresholdBasis.RELATIVE))
        assertEquals("cdd_reference_exceeded", d.rule)
        assertEquals(listOf("slower_than_baseline"), d.secondaryRules)
    }

    @Test fun validatedFailIsBelowSpecAndNeverNamesHal() {
        val d = rules.diagnose(pass + st("2.2", State.FAIL, ThresholdBasis.ABSOLUTE_VALIDATED))
        assertEquals("below_spec", d.rule)
        assertTrue(CauseLayer.values().none { it.name.contains("HAL") })
    }

    @Test fun threeASearchingIsLowestNonNormal() {
        val d = rules.diagnose(pass, DiagnosisRules.Context(threeAStable = false))
        assertEquals("three_a_searching", d.rule)
        assertEquals("three_a_unstable", rules.diagnose(pass + st("H.7", State.WARN, ThresholdBasis.HEURISTIC), DiagnosisRules.Context(threeAStable = false)).rule)
    }

    @Test fun everyRuleHasConsumerText() {
        DiagnosisRules.PRIORITY.forEach { assertTrue(it, DiagnosisRules.CONSUMER_TEXT.containsKey(it)) }
    }
}
