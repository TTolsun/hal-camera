package dev.cameradoctor.benchmark

import dev.cameradoctor.benchmark.BenchmarkRunFixture.metric
import dev.cameradoctor.benchmark.BenchmarkRunFixture.run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Baseline pointer rules and reference selection of 7.1, over an in-memory catalog. */
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
        assertNull(m.pointer(current))
        assertNull(m.baselineRun(current))
        assertFalse(m.isBaseline(current))
    }

    @Test fun setPointsAtTheRunAndClearRemovesThePointerOnly() {
        val base = runAt("20260910-100000-000")
        val catalog = FakeCatalog(listOf(base))
        val m = BaselineManager(catalog)

        assertEquals(BaselineManager.SetOutcome.SET, m.set(base))
        assertEquals(base.runId, m.pointer(base))
        assertTrue(m.isBaseline(base))

        assertEquals(BaselineManager.SetOutcome.CLEARED, m.clear(base))
        assertNull(m.pointer(base))
        // The run file itself is untouched by clearing.
        assertTrue(base.runId in catalog.runIds())
    }

    @Test fun toggleSwitchesBetweenSetAndClear() {
        val base = runAt("20260910-100000-000")
        val m = BaselineManager(FakeCatalog(listOf(base)))
        assertEquals(BaselineManager.SetOutcome.SET, m.toggle(base))
        assertEquals(BaselineManager.SetOutcome.CLEARED, m.toggle(base))
        assertNull(m.pointer(base))
    }

    @Test fun anIneligibleRunCannotBecomeTheBaseline() {
        val bad = runAt("20260910-100000-000", flags = listOf(ValidityFlags.THERMAL_HIGH))
        val catalog = FakeCatalog(listOf(bad))
        val m = BaselineManager(catalog)
        assertEquals(BaselineManager.SetOutcome.NOT_ELIGIBLE, m.set(bad))
        assertNull(m.pointer(bad))
        assertEquals(0, catalog.writes)
    }

    @Test fun aChargingRunMayStillBecomeTheBaseline() {
        // CHARGING blocks cross-device scoring only (5.3), so in-house regression tracking still accepts it.
        val charging = run(runId = "20260910-100000-000", metrics = listOf(metric("1.1", 100.0)), charging = true, flags = listOf(ValidityFlags.CHARGING))
        val m = BaselineManager(FakeCatalog(listOf(charging)))
        assertEquals(BaselineManager.SetOutcome.SET, m.set(charging))
    }

    @Test fun settingAnotherRunOverwritesThePointer() {
        val first = runAt("20260910-100000-000")
        val second = runAt("20260910-110000-000")
        val m = BaselineManager(FakeCatalog(listOf(first, second)))
        m.set(first)
        m.set(second)
        assertEquals(second.runId, m.pointer(second))
        assertFalse(m.isBaseline(first))
    }

    @Test fun aDeletedBaselineFileReturnsToNoBaseline() {
        val base = runAt("20260910-100000-000")
        val current = runAt("20260910-110000-000")
        val catalog = FakeCatalog(listOf(base, current))
        val m = BaselineManager(catalog)
        m.set(base)

        catalog.runs.remove(base.runId)

        assertNull(m.pointer(current))
        assertNull(m.baselineRun(current))
        // The stale pointer is dropped from the stored index, not just ignored on read.
        assertTrue(catalog.index.baselines.isEmpty())
    }

    @Test fun anUnreadableBaselineFileYieldsNoBaselineRunButKeepsThePointer() {
        val base = runAt("20260910-100000-000")
        val current = runAt("20260910-110000-000")
        val catalog = FakeCatalog(listOf(base, current))
        val m = BaselineManager(catalog)
        m.set(base)

        catalog.runs[base.runId] = null   // the file exists but does not parse

        assertEquals(base.runId, m.pointer(current))
        assertNull(m.baselineRun(current))
    }

    @Test fun baselinesOfOtherEndpointsAreIndependent() {
        val main = runAt("20260910-100000-000", endpointKey = "0")
        val ultrawide = runAt("20260910-101000-000", endpointKey = "2")
        val m = BaselineManager(FakeCatalog(listOf(main, ultrawide)))
        m.set(main)
        assertEquals(main.runId, m.pointer(main))
        assertNull(m.pointer(ultrawide))
    }

    @Test fun theBaselineSurvivesAFingerprintChange() {
        val base = runAt("20260910-100000-000")
        val newBuild = run(
            runId = "20260910-110000-000", metrics = listOf(metric("1.1", 100.0)),
            device = BenchmarkRunFixture.DEVICE.copy(fingerprint = "samsung/next-build")
        )
        val m = BaselineManager(FakeCatalog(listOf(base, newBuild)))
        m.set(base)
        // Comparing across builds is the point of the product, so a new fingerprint must not drop the pointer.
        assertEquals(base.runId, m.pointer(newBuild))
        assertEquals(base.runId, m.baselineRun(newBuild)?.runId)
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
