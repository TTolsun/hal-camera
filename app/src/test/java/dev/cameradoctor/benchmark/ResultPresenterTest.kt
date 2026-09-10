package dev.cameradoctor.benchmark

import dev.cameradoctor.benchmark.BenchmarkRunFixture.metric
import dev.cameradoctor.benchmark.BenchmarkRunFixture.run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The result screen contract of 8.4: which statistic the second column shows, what the headline says, and when SET AS BASELINE is disabled. */
class ResultPresenterTest {

    private fun launchMetric(id: String, value: Double, max: Double, n: Int = 9) = metric(id, value).copy(
        p50 = value, p95 = max, max = max, sampleCount = n, samples = List(n) { value }
    )

    private fun windowMetric(id: String, value: Double, n: Int = 298) = metric(id, value).copy(
        p50 = value, p95 = value, max = value + 2.0, sampleCount = n, samples = null
    )

    private fun countMetric(id: String, count: Int) = metric(id, count.toDouble()).copy(
        p50 = null, p95 = null, min = null, max = null, sampleCount = 300, samples = null
    ).let { it.copy(unit = "count") }

    private fun present(
        run: BenchmarkRun,
        comparison: RunComparison? = null,
        comparedTo: ComparedTo = ComparedTo.NONE,
        isBaseline: Boolean = false
    ) = ResultPresenter.present(run, comparison, comparedTo, isBaseline, "Galaxy S25+", "후면 메인")

    // ---- second statistic column (8.4) ----

    @Test fun aBoundedMetricShowsMaxBelowTwentySamples() {
        val m = launchMetric("1.1", 142.0, 161.0, n = 9)
        assertEquals("max", ResultPresenter.statHeader(m))
        assertEquals(161.0, ResultPresenter.statValue(m)!!, 0.0)
    }

    @Test fun aBoundedMetricShowsP95FromTwentySamples() {
        val m = launchMetric("1.1", 142.0, 161.0, n = 20).copy(p95 = 155.0)
        assertEquals("p95", ResultPresenter.statHeader(m))
        assertEquals(155.0, ResultPresenter.statValue(m)!!, 0.0)
    }

    @Test fun anObservationWindowMetricHasNoSecondStatistic() {
        // H.1 is already a percentile of its own distribution; a second one beside it would read as a percentile of a percentile.
        assertNull(ResultPresenter.statHeader(windowMetric("H.1", 33.3)))
        assertNull(ResultPresenter.statHeader(countMetric("H.5", 0)))
    }

    // ---- number formatting ----

    @Test fun cadenceMetricsKeepOneDecimalAndOthersDoNot() {
        assertEquals("33.3 ms", ResultPresenter.format(windowMetric("H.1", 33.3), 33.3))
        assertEquals("3.8 ms", ResultPresenter.format(windowMetric("H.10", 3.8), 3.8))
        assertEquals("142 ms", ResultPresenter.format(launchMetric("1.1", 142.0, 161.0), 142.0))
        assertEquals("0", ResultPresenter.format(countMetric("H.5", 0), 0.0))
        assertEquals("—", ResultPresenter.format(launchMetric("1.1", 142.0, 161.0), null))
    }

    // ---- delta column ----

    @Test fun latencyDeltaIsAPercentAndCountDeltaIsADifference() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("2.2", 164.0), metric("H.5", 0.0)))
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("2.2", 221.0), metric("H.5", 3.0)))
        val c = RegressionDetector.compare(base, current)
        assertEquals("+35%", ResultPresenter.delta(current.metric("2.2")!!, c.metric("2.2")))
        assertEquals("+3", ResultPresenter.delta(current.metric("H.5")!!, c.metric("H.5")))
    }

    @Test fun aMissingValueShowsADashAndNoComparisonShowsNothing() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("2.2", null)))
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("2.2", 221.0)))
        val c = RegressionDetector.compare(base, current)
        assertEquals("—", ResultPresenter.delta(current.metric("2.2")!!, c.metric("2.2")))
        assertEquals("", ResultPresenter.delta(current.metric("2.2")!!, null))
    }

    @Test fun theRegressionMarkerOnlyAppearsAgainstABaseline() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("2.2", 164.0)))
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("2.2", 221.0)))
        val c = RegressionDetector.compare(base, current)

        val vsBaseline = present(current, c, ComparedTo.BASELINE)
        assertEquals("▲", vsBaseline.sections.first { it.title == "CAPTURE" }.rows.first().marker)
        // A reference delta carries no state, so it is shown without a marker (8.4).
        val vsPrevious = present(current, c, ComparedTo.PREVIOUS)
        assertEquals("", vsPrevious.sections.first { it.title == "CAPTURE" }.rows.first().marker)
        assertEquals("vs previous", vsPrevious.sections.first().deltaHeader)
    }

    @Test fun anImprovementIsMarkedToo() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("2.2", 221.0)))
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("2.2", 164.0)))
        val c = RegressionDetector.compare(base, current)
        assertEquals("▼", present(current, c, ComparedTo.BASELINE).sections.first { it.title == "CAPTURE" }.rows.first().marker)
    }

    // ---- eligibility headline (5.3, 8.4) ----

    @Test fun everyEligibilityStepHasItsOwnHeadline() {
        assertEquals("비교 가능 · 점수 가능 · thermal 0 → 1 → 1", ResultPresenter.eligibilityLine(run()))

        val charging = run(charging = true, flags = listOf(ValidityFlags.CHARGING))
        assertTrue(ResultPresenter.eligibilityLine(charging).startsWith("비교 가능 · 점수 제외 (CHARGING)"))

        val hot = run(thermalMax = 3, flags = listOf(ValidityFlags.THERMAL_HIGH))
        val hotLine = ResultPresenter.eligibilityLine(hot)
        assertTrue(hotLine.startsWith("비교 불가 (THERMAL_HIGH)"))
        assertTrue(hotLine.endsWith("SET AS BASELINE 비활성"))

        val broken = run(flags = listOf(ValidityFlags.HARD_FAILURE))
        assertTrue(ResultPresenter.eligibilityLine(broken).startsWith("측정 무효 (HARD_FAILURE)"))
    }

    @Test fun anIneligibleRunCannotBeSetAsBaselineButABaselineCanAlwaysBeCleared() {
        val hot = run(thermalMax = 3, flags = listOf(ValidityFlags.THERMAL_HIGH))
        assertFalse(present(hot).baselineButtonEnabled)
        assertEquals("SET AS BASELINE", present(hot).baselineButton)

        val cleared = present(hot, isBaseline = true)
        assertEquals("CLEAR BASELINE", cleared.baselineButton)
        assertTrue(cleared.baselineButtonEnabled)
    }

    // ---- comparison headline (7.1, 8.4) ----

    @Test fun theHeadlineNamesTheBaselineAndTheRegressionCount() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("2.2", 164.0)))
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("2.2", 221.0)))
        val v = present(current, RegressionDetector.compare(base, current), ComparedTo.BASELINE)
        assertEquals("▲ 1 REGRESSED   baseline 20260910-100000-000", v.comparisonLine)
        assertNull(v.hint)
    }

    @Test fun withoutABaselineTheHeadlinePointsAtThePreviousRun() {
        val previous = run(runId = "20260910-100000-000", metrics = listOf(metric("2.2", 164.0)))
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("2.2", 170.0)))
        val v = present(current, RegressionDetector.compare(previous, current), ComparedTo.PREVIOUS)
        assertEquals("baseline 없음 · 이전 run 20260910-100000-000 대비 표시", v.comparisonLine)
        assertEquals("[ SET AS BASELINE ]을 누르면 이 run이 기준이 됩니다", v.hint)
    }

    @Test fun theFirstRunOfADeviceHasNeitherBaselineNorPrevious() {
        val v = present(run(metrics = listOf(metric("2.2", 164.0))))
        assertEquals("baseline 없음 · 비교할 이전 run이 없습니다", v.comparisonLine)
        assertNull(v.identityLine)
        assertEquals("", v.sections.first().deltaHeader)
    }

    // ---- identity summary (7.4) ----

    @Test fun theIdentityLineSummarisesFourAxes() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("1.1", 100.0)), subject = SubjectLabel("SW41", "9c01d2e"))
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("1.1", 100.0)),
            device = BenchmarkRunFixture.DEVICE.copy(vendorFingerprint = "vendor/other"))
        val v = present(current, RegressionDetector.compare(base, current), ComparedTo.BASELINE)
        assertEquals("Android 동일 · Camera build 다름 · 앱 동일 · subject 다름", v.identityLine)
    }

    @Test fun theSubjectAxisIsDroppedWhenNeitherSideIsLabelled() {
        val unlabeled = SubjectLabel()
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("1.1", 100.0)), subject = unlabeled)
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("1.1", 100.0)), subject = unlabeled)
        val v = present(current, RegressionDetector.compare(base, current), ComparedTo.BASELINE)
        assertEquals("Android 동일 · Camera build 동일 · 앱 동일", v.identityLine)
    }

    // ---- 3A line ----

    @Test fun theThreeALineNamesATimeoutInsteadOfShowingTheWindowLength() {
        val r = run(metrics = listOf(metric("H.6", 420.0), metric("H.7", 13000.0, timeout = true), metric("H.8", 380.0)))
        assertEquals("3A (informational)\n  AE / AF / AWB   420 / timeout / 380 ms", ResultPresenter.threeALine(r))
        assertNull(ResultPresenter.threeALine(run(metrics = listOf(metric("H.6", null)))))
    }

    // ---- whole view ----

    @Test fun theRenderedTableKeepsItsColumnsAligned() {
        val current = run(
            runId = "20260910-110000-000",
            metrics = listOf(launchMetric("1.1", 142.0, 161.0), windowMetric("H.1", 33.3), countMetric("H.5", 0))
        )
        val text = present(current).render()
        assertTrue(text, text.startsWith("CAMERA BENCHMARK\nGalaxy S25+ · 후면 메인 · camera2-standard-v1 · warm reopen\n"))
        assertTrue(text, text.contains("2026-09-10 11:00"))
        val open = text.lines().first { it.contains("Open") }
        val interval = text.lines().first { it.contains("Interval p50") }
        val stalls = text.lines().first { it.contains("Stalls") }
        // Every value column ends at the same offset, whether or not the row has a second statistic.
        val valueEnd = open.indexOf("142 ms") + "142 ms".length
        assertEquals(valueEnd, interval.indexOf("33.3 ms") + "33.3 ms".length)
        assertEquals(valueEnd, stalls.indexOf("0") + 1)
        assertEquals(valueEnd + 9, open.indexOf("161 ms") + "161 ms".length)
        // Column names appear once, above the first section.
        assertTrue(text, text.lines().first { it.startsWith("LAUNCH") }.contains("p50"))
        assertEquals("PREVIEW", text.lines().first { it.startsWith("PREVIEW") })
        // An empty trailing column leaves no stray spaces.
        assertEquals(interval, interval.trimEnd())
    }

    @Test fun sectionsFollowTheCatalogOrderAndSkipEmptyOnes() {
        val current = run(metrics = listOf(metric("2.2", 164.0), launchMetric("1.1", 142.0, 161.0)))
        val titles = present(current).sections.map { it.title }
        assertEquals(listOf("LAUNCH", "CAPTURE"), titles)
    }
}
