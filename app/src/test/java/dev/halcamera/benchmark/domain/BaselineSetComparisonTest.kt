package dev.halcamera.benchmark.domain

import dev.halcamera.benchmark.domain.BenchmarkRunFixture.metric
import dev.halcamera.benchmark.domain.BenchmarkRunFixture.run
import dev.halcamera.metrics.UnknownReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A run judged against a baseline set (#165): REGRESSED only past the worst member, IMPROVED only past the best,
 * STABLE anywhere inside the range. The pairwise rules of 7.2 and 7.5 are pinned in [RegressionDetectorTest];
 * these cases pin how a set combines them.
 */
class BaselineSetComparisonTest {

    private fun member(i: Int, vararg metrics: BenchmarkMetric, thermalMax: Int? = 1, charging: Boolean? = false) =
        run(runId = "20260910-10${i}000-000", metrics = metrics.toList(), thermalMax = thermalMax, charging = charging)

    private fun current(vararg metrics: BenchmarkMetric, thermalMax: Int? = 1) =
        run(runId = "20260910-110000-000", metrics = metrics.toList(), thermalMax = thermalMax)

    /** 1.3 in S25+ normal runs: a slow mode near 260 ms and a fast mode near 170 ms (#162 diagnosis 1). */
    private val twoModes = listOf(member(1, metric("1.3", 262.0)), member(2, metric("1.3", 171.0)), member(3, metric("1.3", 258.0)))

    private fun judge(bases: List<BenchmarkRun>, value: Double, id: String = "1.3") =
        RegressionDetector.compare(bases, current(metric(id, value))).metric(id)!!

    @Test fun eitherLaunchModeIsStableAgainstASetHoldingBoth() {
        // Against the fast-mode run alone, a slow-mode run was REGRESSED; against the set it sits inside the range.
        assertEquals(RegressionState.REGRESSED, judge(listOf(twoModes[1]), 262.0).state)
        assertEquals(RegressionState.STABLE, judge(twoModes, 262.0).state)
        assertEquals(RegressionState.STABLE, judge(twoModes, 168.0).state)
    }

    @Test fun regressionIsMeasuredFromTheWorstMember() {
        val m = judge(twoModes, 302.0)
        assertEquals(RegressionState.REGRESSED, m.state)
        // The tick and the delta stand for the edge the verdict used.
        assertEquals(262.0, m.baselineValue!!, 0.0)
        assertEquals(171.0, m.baselineBest!!, 0.0)
        assertEquals((302.0 - 262.0) / 262.0 * 100.0, m.deltaPct!!, 1e-9)
        assertEquals(RegressionState.STABLE, judge(twoModes, 301.0).state)   // +14.9 % from the worst member
    }

    @Test fun improvementIsMeasuredFromTheBestMember() {
        assertEquals(RegressionState.IMPROVED, judge(twoModes, 145.0).state)
        // 20% better than the worst member but inside the range: not an improvement.
        assertEquals(RegressionState.STABLE, judge(twoModes, 209.0).state)
    }

    @Test fun higherIsBetterUsesTheLowestMemberAsTheWorst() {
        val bases = listOf(member(1, metric("3.4", 30.0)), member(2, metric("3.4", 29.0)))
        val m = judge(bases, 27.5, "3.4")
        assertEquals(29.0, m.baselineValue!!, 0.0)
        assertEquals(RegressionState.REGRESSED, m.state)
        assertEquals(RegressionState.STABLE, judge(bases, 30.5, "3.4").state)
    }

    @Test fun aSetOfOneIsThePairwiseComparison() {
        val base = member(1, metric("1.1", 100.0))
        for (v in listOf(84.0, 85.0, 100.0, 114.9, 115.0, 130.0)) {
            val pair = RegressionDetector.compare(base, current(metric("1.1", v))).metric("1.1")!!
            val set = RegressionDetector.compare(listOf(base), current(metric("1.1", v))).metric("1.1")!!
            assertEquals("$v", pair, set)
        }
    }

    @Test fun aMemberWithoutTheMetricIsLeftOutOfTheRange() {
        val bases = listOf(member(1, metric("1.1", 100.0)), member(2, metric("1.1", null)))
        val m = judge(bases, 116.0, "1.1")
        assertEquals(100.0, m.baselineValue!!, 0.0)
        assertEquals(RegressionState.REGRESSED, m.state)
    }

    @Test fun noMemberWithTheMetricIsNoVerdict() {
        val bases = listOf(member(1, metric("1.1", null, unknownReason = UnknownReason.UNSUPPORTED)))
        val m = judge(bases, 116.0, "1.1")
        assertEquals(RegressionState.UNKNOWN, m.state)
        assertEquals(UnknownReason.UNSUPPORTED, m.unknownReason)
    }

    @Test fun aMemberThatTimedOutMakesTheMetricNotMeasurable() {
        val bases = listOf(member(1, metric("H.7", 500.0)), member(2, metric("H.7", 13000.0, timeout = true)))
        val m = judge(bases, 520.0, "H.7")
        assertEquals(RegressionState.UNKNOWN, m.state)
        assertEquals(UnknownReason.NOT_MEASURABLE, m.unknownReason)
    }

    @Test fun aConditionThatDiffersFromAnyMemberDiffersFromTheSet() {
        val bases = listOf(member(1, metric("1.1", 100.0), thermalMax = 0), member(2, metric("1.1", 100.0), thermalMax = 2))
        val c = RegressionDetector.compare(bases, current(metric("1.1", 200.0), thermalMax = 0))
        assertEquals(listOf(ConditionMismatch.THERMAL_MAX_DIFFERS), c.conditionMismatches)
        assertEquals(UnknownReason.CONDITION_MISMATCH, c.metric("1.1")!!.unknownReason)
        assertEquals(0, c.judgedCount)
    }

    @Test fun mismatchesOfSeveralMembersAreListedOnceInTableOrder() {
        val bases = listOf(
            member(1, metric("1.1", 100.0), charging = true),
            member(2, metric("1.1", 100.0), charging = true, thermalMax = 3)
        )
        val c = RegressionDetector.compare(bases, current(metric("1.1", 100.0)))
        assertEquals(listOf(ConditionMismatch.THERMAL_MAX_DIFFERS, ConditionMismatch.CHARGING_DIFFERS), c.conditionMismatches)
    }

    @Test fun anIneligibleMemberBlocksTheWholeComparison() {
        val bad = run(runId = "20260910-102000-000", metrics = listOf(metric("1.1", 100.0)), flags = listOf(ValidityFlags.THERMAL_HIGH))
        val c = RegressionDetector.compare(listOf(member(1, metric("1.1", 100.0)), bad), current(metric("1.1", 300.0)))
        assertEquals(UnknownReason.CONDITION_MISMATCH, c.metric("1.1")!!.unknownReason)
    }

    @Test fun theBuildIsTheSameOnlyWhenItIsTheSameAsEveryMember() {
        val other = run(
            runId = "20260910-102000-000", metrics = listOf(metric("1.1", 100.0)),
            device = BenchmarkRunFixture.DEVICE.copy(fingerprint = "samsung/next-build")
        )
        val c = RegressionDetector.compare(listOf(member(1, metric("1.1", 100.0)), other), current(metric("1.1", 100.0)))
        assertEquals(false, c.identity!!.sameSystemFingerprint)
        assertTrue(c.identity!!.sameAppVersion)
    }
}
