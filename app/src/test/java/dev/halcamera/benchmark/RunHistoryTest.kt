package dev.halcamera.benchmark

import dev.halcamera.benchmark.BenchmarkRunFixture.metric
import dev.halcamera.benchmark.BenchmarkRunFixture.run
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter

class RunHistoryTest {
    private class Catalog(val entries: Map<String, BenchmarkRun?>) : RunCatalog {
        override fun index() = BenchmarkIndex()
        override fun saveIndex(index: BenchmarkIndex) = Unit
        override fun runIds() = entries.keys
        override fun runIdsNewestFirst() = entries.keys.toList()
        override fun load(runId: String) = entries[runId]
    }

    @Test fun historySortsAndFiltersWithoutKeepingRawBuffers() {
        val clean = run(runId = "003", metrics = listOf(metric("2.2", 120.0).copy(samples = listOf(120.0))))
            .copy(events = listOf(mapOf("huge" to "event")))
        val charging = run(runId = "002", flags = listOf(ValidityFlags.CHARGING))
        val invalid = run(runId = "001", flags = listOf(ValidityFlags.ABORTED), endpointKey = "1")
        val index = RunIndex.load(Catalog(linkedMapOf("001" to invalid, "003" to clean, "002" to charging, "bad" to null, "wrong-id" to clean)))
        assertEquals(listOf("003", "002", "001"), index.filtered(RunFilter.ALL).map { it.runId })
        assertEquals(listOf("003", "002"), index.filtered(RunFilter.COMPARISON).map { it.runId })
        assertEquals(listOf("003"), index.filtered(RunFilter.SCORING).map { it.runId })
        assertEquals(listOf("001"), index.filtered(RunFilter.ALL, endpointKey = "1").map { it.runId })
        assertTrue(index.filtered(RunFilter.ALL, profileId = "missing").isEmpty())
        assertEquals(listOf("bad", "wrong-id"), index.unreadableIds)
        assertTrue(index.runs.first().events.isEmpty())
        assertNull(index.runs.first().metrics.first().samples)
        assertEquals(RegressionDetector.exposureLoad(clean), RegressionDetector.exposureLoad(index.runs.first()))
    }

    @Test fun deletionClearsEveryPointerToDeletedRunOnlyAfterRemovalSucceeds() {
        val index = BenchmarkIndex(mapOf("a|0" to "first", "a|1" to "second", "b|0" to "first"))
        var saved: BenchmarkIndex? = null
        assertFalse(RunDeletion.delete("first", index, { false }, { saved = it }))
        assertNull(saved)
        assertTrue(RunDeletion.delete("first", index, { true }, { saved = it }))
        assertEquals(mapOf("a|1" to "second"), saved!!.baselines)
        saved = null
        assertTrue(RunDeletion.delete("third", index, { true }, { saved = it }))
        assertNull(saved)
    }

    @Test fun selectedComparisonDoesNotInventABaselineOrCallItPrevious() {
        val base = run(runId = "base", metrics = listOf(metric("1.1", 100.0)))
        val current = run(runId = "current", metrics = listOf(metric("1.1", 300.0)))
        val cmp = RegressionDetector.compare(base, current)
        assertTrue(cmp.hasRegression)
        val selected = ComparePresenter.present(base, current, cmp, ComparedTo.PREVIOUS, selectedReference = true)
        assertEquals("SELECTED", selected.baseHeader)
        assertTrue(selected.rows.none { it.hasVerdict })
        assertTrue(selected.baseLine.startsWith("selected"))
        assertFalse(selected.referenceNote!!.contains("baseline 없음"))
        val baseline = ComparePresenter.present(base, current, cmp, ComparedTo.BASELINE, selectedReference = true)
        assertEquals("BASELINE", baseline.baseHeader)
        assertTrue(baseline.rows.any { it.hasVerdict })
    }

    @Test fun csvEscapesMultilineLabelsAndPreservesNullsAndNumbers() {
        val output = StringWriter()
        BenchmarkCsv.write(sequenceOf(run(
            subject = SubjectLabel("SW,\"42\"\n한글", "=1+1"),
            metrics = listOf(metric("2.2", null), metric("1.1", 123.5))
        )), output)
        val csv = output.toString()
        assertTrue(csv.startsWith(BenchmarkCsv.headers.joinToString(",") + "\r\n"))
        assertTrue(csv.contains("\"SW,\"\"42\"\"\n한글\""))
        assertTrue(csv.contains("\"'=1+1\""))
        assertTrue(csv.contains("\"123.5\""))
        assertTrue(csv.contains("\"2.2\",\"capture\",\"ms\",,,,,,\"0\""))
        assertFalse(csv.contains("null"))
        assertEquals("\"-2.0\"", BenchmarkCsv.cell(-2.0))
    }

    @Test fun csvKeepsAbortedRunsWithoutMetricsAndEmptyExportsHaveAHeader() {
        val empty = StringWriter()
        BenchmarkCsv.write(emptySequence(), empty)
        assertEquals(1, empty.toString().split("\r\n").count { it.isNotEmpty() })
        val aborted = StringWriter()
        BenchmarkCsv.write(sequenceOf(run(flags = listOf(ValidityFlags.ABORTED))), aborted)
        assertEquals(2, aborted.toString().split("\r\n").count { it.isNotEmpty() })
        assertTrue(aborted.toString().contains("ABORTED"))
    }
}
