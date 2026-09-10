package dev.halcamera.benchmark

import dev.halcamera.benchmark.BenchmarkRunFixture.metric
import dev.halcamera.benchmark.BenchmarkRunFixture.run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The COMPARE screen of 7.3: two runs side by side, with the verdict spelled out in words. */
class ComparePresenterTest {

    private val base = run(
        runId = "20260909-095100-000",
        metrics = listOf(metric("1.1", 142.0), metric("2.2", 164.0), metric("H.5", 0.0)),
        subject = SubjectLabel("SW41", "9c01d2e")
    )
    private val current = run(
        runId = "20260909-101422-000",
        metrics = listOf(metric("1.1", 161.0), metric("2.2", 221.0), metric("H.5", 0.0)),
        subject = SubjectLabel("SW42_release", "a8f29c1")
    )

    private fun view(
        b: BenchmarkRun = base,
        c: BenchmarkRun = current,
        comparedTo: ComparedTo = ComparedTo.BASELINE
    ) = ComparePresenter.present(b, c, RegressionDetector.compare(b, c), comparedTo)

    private fun row(label: String, b: BenchmarkRun = base, c: BenchmarkRun = current) =
        view(b, c).rows.first { it.label == label }

    // ---- rows ----

    @Test fun rowsFollowTheCatalogOrder() {
        assertEquals(listOf("Open", "Capture", "Stalls"), view().rows.map { it.label })
    }

    @Test fun aMetricNeitherRunMeasuredIsLeftOut() {
        // A row of two dashes says nothing the run's own validity flags do not already say.
        assertTrue(view().rows.none { it.label == "Jitter" })
    }

    @Test fun aMetricOnlyOneRunMeasuredKeepsItsRow() {
        val withoutCapture = run(runId = base.runId, metrics = listOf(metric("1.1", 142.0)))
        val r = row("Capture", b = withoutCapture)
        assertEquals("—", r.base)
        assertEquals("221 ms", r.current)
    }

    // ---- verdicts ----

    @Test fun aRegressionIsNamedNotOnlyMarked() {
        // 2.2 moved +35 %, past the 15 % and 10 ms thresholds of the 7.2 table.
        val r = row("Capture")
        assertEquals("164 ms", r.base)
        assertEquals("221 ms", r.current)
        assertEquals("+35%", r.delta)
        assertEquals("▲ REGRESSED", r.marker)
    }

    @Test fun aChangeBelowTheThresholdIsLeftUnmarked() {
        // 1.1 moved +13 %, which the 15 % rule calls STABLE.
        val r = row("Open")
        assertEquals("+13%", r.delta)
        assertEquals("", r.marker)
    }

    @Test fun anImprovementIsNamedToo() {
        val faster = run(runId = current.runId, metrics = listOf(metric("2.2", 100.0)))
        assertEquals("▼ IMPROVED", row("Capture", c = faster).marker)
    }

    @Test fun aCountMetricComparesAsACountNotAPercentage() {
        assertEquals("0", row("Stalls").delta)
    }

    @Test fun anUnjudgeableMetricCarriesItsReasonInsteadOfAVerdict() {
        // Two thermal steps apart makes the pair incomparable (7.5); the delta stays, the verdict does not.
        val hot = run(runId = current.runId, metrics = current.metrics, thermalMax = 3)
        val r = row("Capture", c = hot)
        assertEquals("조건 불일치", r.marker)
        assertEquals("+35%", r.delta)
    }

    // ---- baseline versus reference (7.1) ----

    @Test fun aReferenceComparisonShowsNoVerdict() {
        // A reference is only the run measured before this one. Hanging REGRESSED off it would report a
        // regression against a configuration nobody chose as the standard.
        val r = view(comparedTo = ComparedTo.PREVIOUS).rows.first { it.label == "Capture" }
        assertEquals("+35%", r.delta)
        assertEquals("", r.marker)
        assertFalse(r.hasVerdict)
    }

    @Test fun aReferenceComparisonIsLabelledAsOne() {
        val v = view(comparedTo = ComparedTo.PREVIOUS)
        assertEquals("PREVIOUS", v.baseHeader)
        assertTrue(v.baseLine.startsWith("previous"))
        assertTrue(v.referenceNote!!.contains("baseline 없음"))
        assertTrue(v.render().contains("PREVIOUS"))
        assertFalse(v.render().contains("REGRESSED"))
    }

    @Test fun aBaselineComparisonKeepsItsVerdicts() {
        val v = view()
        assertEquals("BASELINE", v.baseHeader)
        assertNull(v.referenceNote)
        assertTrue(v.rows.any { it.hasVerdict })
    }

    @Test fun aReferenceComparisonStillSaysWhyADeltaIsUntrustworthy() {
        // The reason a metric could not be judged explains the delta itself, so it survives even without a verdict.
        val hot = run(runId = current.runId, metrics = current.metrics, thermalMax = 3)
        val r = view(c = hot, comparedTo = ComparedTo.PREVIOUS).rows.first { it.label == "Capture" }
        assertEquals("조건 불일치", r.marker)
        assertFalse(r.hasVerdict)
    }

    // ---- header ----

    @Test fun eachRunLineNamesItsSubjectBuildAndThermalPeak() {
        val v = view()
        assertTrue(v.baseLine.startsWith("baseline"))
        assertTrue(v.baseLine.contains("20260909-095100-000"))
        assertTrue(v.baseLine.contains("SW41"))
        assertTrue(v.currentLine.contains("SW42_release"))
        assertTrue(v.currentLine.contains("thermal max 1"))
    }

    @Test fun anUnlabeledRunSaysSoRatherThanShowingAnEmptyColumn() {
        val unlabeled = run(runId = base.runId, metrics = base.metrics, subject = SubjectLabel())
        assertTrue(ComparePresenter.runLine("baseline", unlabeled).contains("(subject 없음)"))
    }

    @Test fun theTitleCarriesTheRuleVersionThatProducedTheVerdicts() {
        assertTrue(view().titleLine.startsWith("COMPARE"))
        assertTrue(view().titleLine.endsWith("rule ${RegressionRules.VERSION}"))
    }

    @Test fun theIdentityLineSummarisesTheFourAxes() {
        val line = view().identityLine!!
        assertTrue(line.contains("Android 동일"))
        assertTrue(line.contains("subject 다름"))
    }

    @Test fun aConditionDifferenceIsShownApartFromTheVerdicts() {
        assertNull(view().conditionLine)
        val charging = run(runId = current.runId, metrics = current.metrics, charging = true)
        assertTrue(view(c = charging).conditionLine!!.contains("충전 상태 다름"))
    }

    @Test fun theRenderedTableHeadsBothValueColumns() {
        val text = view().render()
        assertTrue(text.contains("BASELINE"))
        assertTrue(text.contains("CURRENT"))
        assertTrue(text.lines().any { it.startsWith("Capture") && it.endsWith("▲ REGRESSED") })
    }
}
