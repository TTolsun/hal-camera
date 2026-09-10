package dev.halcamera.diagnosis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthComposerTest {
    private fun st(id: String, final: State, basis: ThresholdBasis? = null, reason: UnknownReason? = null) = MetricState(
        id, 1.0, null, 1, State.UNKNOWN, null, null, State.UNKNOWN, null, null, final, basis, null, null, reason
    )
    private val allIds = ThresholdTable.rules.keys
    private fun allPass() = allIds.map { st(it, State.PASS) }

    @Test fun allPassIsNormalAndFullCoverage() {
        val h = HealthComposer().compose(allPass(), hasBaseline = true)
        assertEquals(HealthLevelV2.NORMAL, h.level)
        assertEquals(1.0, h.coverage, 1e-9)
        assertEquals(100, h.score)
    }

    @Test fun relativeFailIsWarningNotIssue() {
        val states = allPass().map { if (it.id == "1.1") st("1.1", State.FAIL, ThresholdBasis.RELATIVE) else it }
        assertEquals(HealthLevelV2.WARNING, HealthComposer().compose(states, true).level)
    }

    @Test fun heuristicFailIsWarningNotIssue() {
        val states = allPass().map { if (it.id == "H.5") st("H.5", State.FAIL, ThresholdBasis.HEURISTIC) else it }
        assertEquals(HealthLevelV2.WARNING, HealthComposer().compose(states, true).level)
    }

    @Test fun hardFailIsIssueAndCapsScore() {
        val states = allPass().map { if (it.id == "1.1") st("1.1", State.FAIL, ThresholdBasis.HARD) else it }
        val h = HealthComposer().compose(states, true)
        assertEquals(HealthLevelV2.ISSUE, h.level)
        assertEquals(59, h.score)
    }

    @Test fun validatedAbsoluteFailIsIssue() {
        val states = allPass().map { if (it.id == "2.2") st("2.2", State.FAIL, ThresholdBasis.ABSOLUTE_VALIDATED) else it }
        assertEquals(HealthLevelV2.ISSUE, HealthComposer().compose(states, true).level)
    }

    @Test fun threeAWarnIsWarning() {
        val states = allPass().map { if (it.id == "H.7") st("H.7", State.WARN, ThresholdBasis.HEURISTIC) else it }
        val h = HealthComposer().compose(states, true)
        assertEquals(HealthLevelV2.WARNING, h.level)
        // 20/3 weight at 0.5 points out of 100 total.
        assertEquals(97, h.score)
    }

    @Test fun noBaselineMeansNoScoreEvenWithFullCoverage() {
        val h = HealthComposer().compose(allPass(), hasBaseline = false)
        assertNull(h.score)
        assertEquals(HealthLevelV2.NORMAL, h.level)
    }

    @Test fun unknownLeavesDenominatorAndCoverageDrops() {
        // Unknown everything except the launch group (25 of 100): coverage 0.25, no score, INSUFFICIENT.
        val states = allIds.map { id ->
            if (ThresholdTable.rules[id]!!.group == "launch") st(id, State.PASS) else st(id, State.UNKNOWN, reason = UnknownReason.NO_BASELINE)
        }
        val h = HealthComposer().compose(states, true)
        assertEquals(0.25, h.coverage, 1e-9)
        assertNull(h.score)
        assertEquals(HealthLevelV2.INSUFFICIENT, h.level)
    }

    @Test fun coverageBoundaryAtSeventyPercent() {
        // 3A group (20) unknown, everything else PASS: coverage 0.8 gives a score; also unknown H.2..H.4 (15) gives 0.65 and no score.
        val s1 = allIds.map { id -> if (ThresholdTable.rules[id]!!.group == "3a") st(id, State.UNKNOWN, reason = UnknownReason.NO_BASELINE) else st(id, State.PASS) }
        assertEquals(100, HealthComposer().compose(s1, true).score)
        val s2 = s1.map { if (it.id in setOf("H.2", "H.3", "H.4")) st(it.id, State.UNKNOWN, reason = UnknownReason.NO_BASELINE) else it }
        val h2 = HealthComposer().compose(s2, true)
        assertEquals(0.65, h2.coverage, 1e-9)
        assertNull(h2.score)
    }

    @Test fun scoreHiddenUnlessEnabled() {
        assertEquals(false, HealthComposer().compose(allPass(), true).scoreVisible)
        assertEquals(true, HealthComposer(scoreEnabled = true).compose(allPass(), true).scoreVisible)
    }
}
