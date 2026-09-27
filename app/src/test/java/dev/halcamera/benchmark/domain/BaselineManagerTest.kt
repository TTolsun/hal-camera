package dev.halcamera.benchmark.domain

import dev.halcamera.benchmark.domain.BenchmarkRunFixture.metric
import dev.halcamera.benchmark.domain.BenchmarkRunFixture.run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Baseline set rules and reference selection of 7.1, over an in-memory catalog. */
class BaselineManagerTest {

    /** Stands in for files/benchmarks/: runs by id plus the index. Unreadable files are modelled as a null run. */
    private class FakeCatalog(runs: List<BenchmarkRun> = emptyList()) : RunCatalog {
        val runs = LinkedHashMap<String, BenchmarkRun?>().apply { runs.forEach { put(it.runId, it) } }
        var index = BenchmarkIndex()
        var writes = 0

        override fun index(): BenchmarkIndex = index
        override fun saveIndex(index: BenchmarkIndex) { this.index = index; writes++ }
        override fun runIds(): Set<String> = runs.keys
        override fun runIdsNewestFirst(): List<String> = runs.keys.sortedDescending()
        override fun load(runId: String): BenchmarkRun? = runs[runId]
    }

    private fun runAt(id: String, value: Double = 100.0, endpointKey: String = "0", flags: List<ValidityFlag> = emptyList()) =
        run(runId = id, metrics = listOf(metric("1.1", value)), endpointKey = endpointKey, flags = flags)

    @Test fun thereIsNoBaselineUntilOneIsSet() {
        val current = runAt("20260910-110000-000")
        val m = BaselineManager(FakeCatalog(listOf(current)))
        assertEquals(emptyList<String>(), m.members(current))
        assertEquals(emptyList<BenchmarkRun>(), m.baselineRuns(current))
        assertFalse(m.isBaseline(current))
        assertEquals(ComparedTo.NONE, m.resolve(current).comparedTo)
    }

    @Test fun setAddsTheRunAndClearRemovesItFromTheSetOnly() {
        val base = runAt("20260910-100000-000")
        val catalog = FakeCatalog(listOf(base))
        val m = BaselineManager(catalog)

        assertEquals(BaselineManager.SetOutcome.SET, m.set(base))
        assertEquals(listOf(base.runId), m.members(base))
        assertTrue(m.isBaseline(base))

        assertEquals(BaselineManager.SetOutcome.CLEARED, m.clear(base))
        assertEquals(emptyList<String>(), m.members(base))
        // The run file itself is untouched by clearing.
        assertTrue(base.runId in catalog.runIds())
    }

    @Test fun toggleSwitchesBetweenSetAndClear() {
        val base = runAt("20260910-100000-000")
        val m = BaselineManager(FakeCatalog(listOf(base)))
        assertEquals(BaselineManager.SetOutcome.SET, m.toggle(base))
        assertEquals(BaselineManager.SetOutcome.CLEARED, m.toggle(base))
        assertEquals(emptyList<String>(), m.members(base))
    }

    @Test fun anIneligibleRunCannotBecomeTheBaseline() {
        val bad = runAt("20260910-100000-000", flags = listOf(ValidityFlags.THERMAL_HIGH))
        val catalog = FakeCatalog(listOf(bad))
        val m = BaselineManager(catalog)
        assertEquals(BaselineManager.SetOutcome.NOT_ELIGIBLE, m.set(bad))
        assertEquals(emptyList<String>(), m.members(bad))
        assertEquals(0, catalog.writes)
    }

    @Test fun aChargingRunMayStillBecomeTheBaseline() {
        // CHARGING blocks cross-device scoring only (5.3), so in-house regression tracking still accepts it.
        val charging = run(runId = "20260910-100000-000", metrics = listOf(metric("1.1", 100.0)), charging = true, flags = listOf(ValidityFlags.CHARGING))
        val m = BaselineManager(FakeCatalog(listOf(charging)))
        assertEquals(BaselineManager.SetOutcome.SET, m.set(charging))
    }

    @Test fun settingAnotherRunAddsItToTheSet() {
        val first = runAt("20260910-100000-000")
        val second = runAt("20260910-110000-000")
        val catalog = FakeCatalog(listOf(first, second))
        val m = BaselineManager(catalog)
        m.set(first)
        m.set(second)
        // #165: one run could not stand for a camera whose launch alternates between two modes.
        assertEquals(listOf(first.runId, second.runId), m.members(second))
        assertTrue(m.isBaseline(first))
        // Adding a member twice changes nothing and writes nothing.
        val writes = catalog.writes
        m.set(first)
        assertEquals(listOf(first.runId, second.runId), m.members(first))
        assertEquals(writes, catalog.writes)
    }

    @Test fun aRunIsJudgedAgainstEveryMemberButItself() {
        val a = runAt("20260910-100000-000")
        val b = runAt("20260910-101000-000")
        val current = runAt("20260910-110000-000")
        val m = BaselineManager(FakeCatalog(listOf(a, b, current)))
        m.set(a)
        m.set(b)

        val resolved = m.resolve(current)
        assertEquals(ComparedTo.BASELINE, resolved.comparedTo)
        assertEquals(listOf(a.runId, b.runId), resolved.runs.map { it.runId })
        assertEquals(listOf(a.runId, b.runId), resolved.comparison(current).baseRunIds)
        // A member is measured against the others, which is what tells whether it belongs in the set.
        assertEquals(listOf(b.runId), m.resolve(a).runs.map { it.runId })
    }

    @Test fun theOnlyMemberFallsBackToThePreviousRun() {
        val older = runAt("20260910-090000-000")
        val base = runAt("20260910-100000-000")
        val m = BaselineManager(FakeCatalog(listOf(older, base)))
        m.set(base)
        val resolved = m.resolve(base)
        assertEquals(ComparedTo.PREVIOUS, resolved.comparedTo)
        assertEquals(listOf(older.runId), resolved.runs.map { it.runId })
    }

    @Test fun aDeletedBaselineFileReturnsToNoBaseline() {
        val base = runAt("20260910-100000-000")
        val current = runAt("20260910-110000-000")
        val catalog = FakeCatalog(listOf(base, current))
        val m = BaselineManager(catalog)
        m.set(base)

        catalog.runs.remove(base.runId)

        assertEquals(emptyList<String>(), m.members(current))
        assertEquals(emptyList<BenchmarkRun>(), m.baselineRuns(current))
        // The stale id is dropped from the stored index, not just ignored on read.
        assertTrue(catalog.index.baselines.isEmpty())
    }

    @Test fun aDeletedMemberLeavesTheRestOfTheSet() {
        val a = runAt("20260910-100000-000")
        val b = runAt("20260910-101000-000")
        val current = runAt("20260910-110000-000")
        val catalog = FakeCatalog(listOf(a, b, current))
        val m = BaselineManager(catalog)
        m.set(a)
        m.set(b)

        catalog.runs.remove(a.runId)

        assertEquals(listOf(b.runId), m.members(current))
        assertEquals(listOf(b.runId), catalog.index.baselines(current.contract.comparisonContractId, "0"))
    }

    @Test fun anUnreadableBaselineFileYieldsNoBaselineRunButKeepsItsId() {
        val base = runAt("20260910-100000-000")
        val current = runAt("20260910-110000-000")
        val catalog = FakeCatalog(listOf(base, current))
        val m = BaselineManager(catalog)
        m.set(base)

        catalog.runs[base.runId] = null   // the file exists but does not parse

        assertEquals(listOf(base.runId), m.members(current))
        assertEquals(emptyList<BenchmarkRun>(), m.baselineRuns(current))
    }

    @Test fun baselinesOfOtherEndpointsAreIndependent() {
        val main = runAt("20260910-100000-000", endpointKey = "0")
        val ultrawide = runAt("20260910-101000-000", endpointKey = "2")
        val m = BaselineManager(FakeCatalog(listOf(main, ultrawide)))
        m.set(main)
        assertEquals(listOf(main.runId), m.members(main))
        assertEquals(emptyList<String>(), m.members(ultrawide))
    }

    @Test fun theBaselineSurvivesAFingerprintChange() {
        val base = runAt("20260910-100000-000")
        val newBuild = run(
            runId = "20260910-110000-000", metrics = listOf(metric("1.1", 100.0)),
            device = BenchmarkRunFixture.DEVICE.copy(fingerprint = "samsung/next-build")
        )
        val m = BaselineManager(FakeCatalog(listOf(base, newBuild)))
        m.set(base)
        // Comparing across builds is the point of the product, so a new fingerprint must not drop the set.
        assertEquals(listOf(base.runId), m.members(newBuild))
        assertEquals(listOf(base.runId), m.baselineRuns(newBuild).map { it.runId })
    }

    @Test fun referenceIsTheMostRecentEligibleEarlierRun() {
        val older = runAt("20260910-090000-000")
        val recent = runAt("20260910-100000-000")
        val current = runAt("20260910-110000-000")
        val later = runAt("20260910-120000-000")
        val m = BaselineManager(FakeCatalog(listOf(older, recent, current, later)))
        assertEquals(recent.runId, m.reference(current)?.runId)
    }

    @Test fun referenceSkipsIneligibleAndForeignRuns() {
        val ok = runAt("20260910-080000-000")
        val ineligible = runAt("20260910-090000-000", flags = listOf(ValidityFlags.INSUFFICIENT_SAMPLES))
        val otherEndpoint = runAt("20260910-100000-000", endpointKey = "2")
        val current = runAt("20260910-110000-000")
        val m = BaselineManager(FakeCatalog(listOf(ok, ineligible, otherEndpoint, current)))
        assertEquals(ok.runId, m.reference(current)?.runId)
    }

    @Test fun theFirstRunHasNoReference() {
        val current = runAt("20260910-110000-000")
        assertNull(BaselineManager(FakeCatalog(listOf(current))).reference(current))
    }
}
