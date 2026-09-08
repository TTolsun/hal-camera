package dev.cameradoctor.diagnosis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricCatalogTest {
    private fun st(id: String, value: Double?, final: State, basis: ThresholdBasis?, bound: Double? = null, delta: Double? = null, reason: UnknownReason? = null) =
        MetricState(id, value, null, 1, State.UNKNOWN, bound, null, State.UNKNOWN, null, delta, final, basis, null, null, reason)

    @Test fun heuristicWarnDoesNotClaimSlowerThanUsual() {
        val line = MetricCatalog.consumerLine(st("1.6", 601.8, State.WARN, ThresholdBasis.HEURISTIC, bound = 500.0, delta = 3.0))
        assertFalse(line, line.contains("평소보다"))
        assertTrue(line, line.contains("기준 500 ms 초과"))
    }

    @Test fun relativeWarnShowsDelta() {
        val line = MetricCatalog.consumerLine(st("H.3", 95.0, State.WARN, ThresholdBasis.RELATIVE, delta = 69.0))
        assertTrue(line, line.contains("평소보다 +69%"))
    }

    @Test fun convergenceTimeoutIsNotShownAsDuration() {
        val line = MetricCatalog.consumerLine(st("H.7", 9921.4, State.WARN, ThresholdBasis.HEURISTIC, bound = 1500.0))
        assertTrue(line, line.contains("5초 내 미완료"))
        assertFalse(line, line.contains("9921"))
        assertTrue(MetricCatalog.expertLine(st("H.7", 9921.4, State.WARN, ThresholdBasis.HEURISTIC, bound = 1500.0)).contains("9921.4 ms"))
    }

    @Test fun convergenceCompletedAtFiveSecondsKeepsItsValue() {
        val line = MetricCatalog.consumerLine(st("H.6", 5000.0, State.WARN, ThresholdBasis.HEURISTIC, bound = 1500.0))
        assertTrue(line, line.contains("5000.0 ms"))
        assertFalse(line, line.contains("미완료"))
    }

    @Test fun consumerLineNeverShowsMetricId() {
        MetricCatalog.run { listOf("1.1", "2.2", "H.5", "H.9").forEach { id -> assertFalse(consumerLine(st(id, 1.0, State.PASS, null)).contains("($id)")) } }
        assertTrue(MetricCatalog.expertLine(st("H.5", 1.0, State.WARN, ThresholdBasis.HEURISTIC, bound = 1.0)).contains("(H.5)"))
    }

    @Test fun unknownCarriesReasonText() {
        assertEquals("○ 초점 맞추기  —  (기기 미지원)", MetricCatalog.consumerLine(st("H.7", null, State.UNKNOWN, null, reason = UnknownReason.UNSUPPORTED)))
    }
}
