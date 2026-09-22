package dev.halcamera.benchmark.domain

import dev.halcamera.benchmark.domain.BenchmarkRunFixture.metric
import dev.halcamera.benchmark.domain.BenchmarkRunFixture.run
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
    ) = ResultPresenter.present(run, comparison, comparedTo, isBaseline, "Galaxy S25+", "Rear main")

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

    @Test fun standaloneRowsKeepTheirOwnStatisticLabels() {
        val view = present(run(metrics = listOf(
            launchMetric("1.1", 142.0, 161.0, n = 9),
            launchMetric("2.2", 164.0, 190.0, n = 20),
            windowMetric("H.1", 33.3)
        )))
        val rows = view.sections.flatMap { it.rows }
        assertEquals("max", rows.first { it.label == "Open" }.statLabel)
        assertEquals("p95", view.sections.first { it.title == "Capture" }.rows.first().statLabel)
        assertTrue(view.sections.first { it.title == "Preview" }.rows.all { it.statLabel.isEmpty() })
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
        assertEquals("▲", vsBaseline.sections.first { it.title == "Capture" }.rows.first().marker)
        // A reference delta carries no state, so it is shown without a marker (8.4).
        val vsPrevious = present(current, c, ComparedTo.PREVIOUS)
        assertEquals("", vsPrevious.sections.first { it.title == "Capture" }.rows.first().marker)
        assertEquals("vs prev", vsPrevious.sections.first().deltaHeader)
    }

    @Test fun anImprovementIsMarkedToo() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("2.2", 221.0)))
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("2.2", 164.0)))
        val c = RegressionDetector.compare(base, current)
        assertEquals("▼", present(current, c, ComparedTo.BASELINE).sections.first { it.title == "Capture" }.rows.first().marker)
    }

    // ---- eligibility headline (5.3, 8.4) ----

    @Test fun everyEligibilityStepHasItsOwnHeadline() {
        assertEquals("Comparable · scorable · thermal 0 → 1 → 1", ResultPresenter.eligibilityLine(run()))

        val charging = run(charging = true, flags = listOf(ValidityFlags.CHARGING))
        assertTrue(ResultPresenter.eligibilityLine(charging).startsWith("Comparable · excluded from scoring (CHARGING)"))

        val hot = run(thermalMax = 3, flags = listOf(ValidityFlags.THERMAL_HIGH))
        val hotLine = ResultPresenter.eligibilityLine(hot)
        assertTrue(hotLine.startsWith("Not comparable (THERMAL_HIGH)"))
        assertTrue(hotLine.endsWith("Set as baseline disabled"))

        val broken = run(flags = listOf(ValidityFlags.HARD_FAILURE))
        assertTrue(ResultPresenter.eligibilityLine(broken).startsWith("Measurement invalid (HARD_FAILURE)"))
    }

    @Test fun anIneligibleRunCannotBeSetAsBaselineButABaselineCanAlwaysBeCleared() {
        val hot = run(thermalMax = 3, flags = listOf(ValidityFlags.THERMAL_HIGH))
        assertFalse(present(hot).baselineButtonEnabled)
        assertEquals("Set as baseline", present(hot).baselineButton)

        val cleared = present(hot, isBaseline = true)
        assertEquals("Clear baseline", cleared.baselineButton)
        assertTrue(cleared.baselineButtonEnabled)
    }

    // ---- comparison headline (7.1, 8.4) ----

    @Test fun theHeadlineNamesTheBaselineAndTheRegressionCount() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("2.2", 164.0)))
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("2.2", 221.0)))
        val v = present(current, RegressionDetector.compare(base, current), ComparedTo.BASELINE)
        assertEquals("▲ 1 degraded   baseline 20260910-100000-000", v.comparisonLine)
        assertNull(v.hint)
    }

    @Test fun withoutABaselineTheHeadlinePointsAtThePreviousRun() {
        val previous = run(runId = "20260910-100000-000", metrics = listOf(metric("2.2", 164.0)))
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("2.2", 170.0)))
        val v = present(current, RegressionDetector.compare(previous, current), ComparedTo.PREVIOUS)
        assertEquals("No baseline · shown vs previous run 20260910-100000-000", v.comparisonLine)
        assertEquals("[ Set as baseline ]을 누르면 이 run이 기준이 됩니다", v.hint)
    }

    @Test fun theFirstRunOfADeviceHasNeitherBaselineNorPrevious() {
        val v = present(run(metrics = listOf(metric("2.2", 164.0))))
        assertEquals("No baseline · no earlier run to compare", v.comparisonLine)
        assertNull(v.identityLine)
        assertEquals("", v.sections.first().deltaHeader)
        // There is no delta column, so no row may print anything in it. A dash under an empty header claims the
        // column exists and that every metric failed to be judged in it, which is a far worse report than the
        // truth, namely that this is simply the first run.
        assertTrue(v.sections.flatMap { it.rows }.all { it.delta.isEmpty() })
        assertFalse(v.render().lines().any { it.trimEnd().endsWith("—") })
    }

    @Test fun theRunThatIsTheBaselineIsNotToldToMakeItselfTheBaseline() {
        // The button reads CLEAR BASELINE at this point, so a hint naming SET AS BASELINE points at nothing.
        // Being measured against a baseline and being one are separate states, and only the first was checked.
        val alone = present(run(metrics = listOf(metric("2.2", 164.0))), isBaseline = true)
        assertEquals("Clear baseline", alone.baselineButton)
        assertNull(alone.hint)

        val previous = run(runId = "20260910-100000-000", metrics = listOf(metric("2.2", 150.0)))
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("2.2", 164.0)))
        val v = present(current, RegressionDetector.compare(previous, current), ComparedTo.PREVIOUS)
        assertEquals("[ Set as baseline ]을 누르면 이 run이 기준이 됩니다", v.hint)
    }

    @Test fun theBaselineItselfIsNotDescribedAsHavingNoBaseline() {
        // The baseline has nothing above it to be measured against, so it falls back to the previous run. The
        // headline read "baseline 없음" while the button beside it read CLEAR BASELINE, which are two claims
        // about the same run that cannot both be true.
        val previous = run(runId = "20260910-100000-000", metrics = listOf(metric("2.2", 150.0)))
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("2.2", 164.0)))
        val c = RegressionDetector.compare(previous, current)
        val asBaseline = present(current, c, ComparedTo.PREVIOUS, isBaseline = true)
        assertEquals("This run is the baseline · shown vs previous run 20260910-100000-000", asBaseline.comparisonLine)
        assertEquals("No baseline · shown vs previous run 20260910-100000-000", present(current, c, ComparedTo.PREVIOUS).comparisonLine)

        val alone = present(run(metrics = listOf(metric("2.2", 164.0))), isBaseline = true)
        assertEquals("This run is the baseline · no earlier run to compare", alone.comparisonLine)
    }

    // ---- a comparison that did not happen (PR #21 review) ----

    @Test fun aComparisonWithNoJudgedMetricSaysSoInsteadOfReportingNoRegression() {
        // The current run is eligible; the baseline became ineligible under a changed flag table.
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("2.2", 164.0)), flags = listOf(ValidityFlags.CADENCE_NOT_FIXED))
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("2.2", 221.0)))
        val c = RegressionDetector.compare(base, current)
        assertEquals(0, c.judgedCount)

        val v = present(current, c, ComparedTo.BASELINE)
        assertEquals("No verdict   baseline 20260910-100000-000 · no metric met the comparison conditions", v.comparisonLine)
        // The row keeps its delta but says why there is no verdict.
        val row = v.sections.first { it.title == "Capture" }.rows.first()
        assertEquals("조건 불일치", row.note)
        assertEquals("+35%", row.delta)
        assertEquals("", row.marker)
        assertTrue(v.render(), v.render().contains("조건 불일치"))
    }

    @Test fun onlyTheMetricsThatCouldNotBeJudgedCarryANote() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("1.1", 100.0), metric("1.2", null)))
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("1.1", 100.0), metric("1.2", 40.0)))
        val rows = present(current, RegressionDetector.compare(base, current), ComparedTo.BASELINE)
            .sections.first { it.title == "Launch" }.rows
        assertEquals("", rows.first { it.label == "Open" }.note)
        assertEquals("미실행", rows.first { it.label == "Configure" }.note)
    }

    // ---- pairwise condition banner (7.5) ----

    @Test fun aChargingDifferenceIsShownEvenThoughEveryVerdictStands() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("2.2", 164.0)), charging = true, flags = listOf(ValidityFlags.CHARGING))
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("2.2", 221.0)), charging = false)
        val c = RegressionDetector.compare(base, current)
        val v = present(current, c, ComparedTo.BASELINE)
        // The current run alone is fully eligible, so its own headline says nothing about charging.
        assertEquals("Comparable · scorable · thermal 0 → 1 → 1", v.eligibilityLine)
        assertEquals("비교 시점 조건 차이: 충전 상태 다름", v.conditionLine)
        assertEquals("▲", v.sections.first { it.title == "Capture" }.rows.first().marker)
        assertTrue(v.render(), v.render().contains("비교 시점 조건 차이: 충전 상태 다름"))
    }

    @Test fun severalConditionDifferencesAreListedTogether() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("H.7", 400.0)), thermalMax = 0, exposureLoad = 1.0e6)
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("H.7", 400.0)), thermalMax = 3, exposureLoad = 5.0e6,
            flags = listOf(ValidityFlags.THERMAL_HIGH))
        val v = present(current, RegressionDetector.compare(base, current), ComparedTo.BASELINE)
        assertEquals("비교 시점 조건 차이: thermal 최고값 2단계 이상 차이 · 노출 부하 4배 이상 차이 (3A 제외)", v.conditionLine)
    }

    @Test fun withoutAConditionDifferenceThereIsNoBanner() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("2.2", 164.0)))
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("2.2", 170.0)))
        assertNull(present(current, RegressionDetector.compare(base, current), ComparedTo.BASELINE).conditionLine)
    }

    // ---- identity summary (7.4) ----

    @Test fun theIdentityLineSummarisesFourAxes() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("1.1", 100.0)), subject = SubjectLabel("SW41", "9c01d2e"))
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("1.1", 100.0)),
            device = BenchmarkRunFixture.DEVICE.copy(vendorFingerprint = "vendor/other"))
        val v = present(current, RegressionDetector.compare(base, current), ComparedTo.BASELINE)
        assertEquals("Android same · Camera build differs · App same · subject differs", v.identityLine)
    }

    @Test fun theSubjectAxisIsDroppedWhenNeitherSideIsLabelled() {
        val unlabeled = SubjectLabel()
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("1.1", 100.0)), subject = unlabeled)
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("1.1", 100.0)), subject = unlabeled)
        val v = present(current, RegressionDetector.compare(base, current), ComparedTo.BASELINE)
        assertEquals("Android same · Camera build same · App same", v.identityLine)
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
        assertTrue(text, text.startsWith("Camera benchmark\nGalaxy S25+ · Rear main · camera2-standard-v1 · warm reopen\n"))
        assertTrue(text, text.contains("2026-09-10 11:00"))
        val open = text.lines().first { it.contains("Open") }
        val interval = text.lines().first { it.contains("Interval p50") }
        val stalls = text.lines().first { it.contains("Stalls") }
        // Every value column ends at the same offset, whether or not the row has a second statistic.
        val valueEnd = open.indexOf("142 ms") + "142 ms".length
        assertEquals(valueEnd, interval.indexOf("33.3 ms") + "33.3 ms".length)
        assertEquals(valueEnd, stalls.indexOf("0") + 1)
        // The second statistic ends one stat column further along.
        assertEquals(valueEnd + 8, open.indexOf("161 ms") + "161 ms".length)
        // The four columns have to fit the card, which holds about 45 cells of a real monospace face at 10sp.
        // Without this the table can grow past the screen again and put the verdict markers out of sight.
        present(current).sections.forEach { assertTrue(it.title, ResultPresenter.headerLine(it).length <= 45) }
        // Column names appear once, above the first section.
        assertTrue(text, text.lines().first { it.startsWith("Launch") }.contains("p50"))
        assertEquals("Preview", text.lines().first { it.startsWith("Preview") })
        // An empty trailing column leaves no stray spaces.
        assertEquals(interval, interval.trimEnd())
    }

    @Test fun sectionsFollowTheCatalogOrderAndSkipEmptyOnes() {
        val current = run(metrics = listOf(metric("2.2", 164.0), launchMetric("1.1", 142.0, 161.0)))
        val titles = present(current).sections.map { it.title }
        assertEquals(listOf("Launch", "Capture"), titles)
    }

    // ---- verdict-first headline and key metric bars (mockup v7) ----

    @Test fun theHeadlineLeadsWithTheVerdict() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(metric("2.2", 164.0)))
        val current = run(runId = "20260910-110000-000", metrics = listOf(metric("2.2", 221.0)))
        val c = RegressionDetector.compare(base, current)

        val bad = ResultPresenter.headline(current, c, ComparedTo.BASELINE, isBaseline = false, endpointName = "Rear main")
        assertEquals("1 metric degraded", bad.text)
        assertEquals(Tone.BAD, bad.tone)
        assertTrue(bad.sub, bad.sub.contains("Rear main"))

        val clean = RegressionDetector.compare(base, run(runId = "20260910-110000-000", metrics = listOf(metric("2.2", 165.0))))
        assertEquals(Tone.GOOD, ResultPresenter.headline(current, clean, ComparedTo.BASELINE, false, "Rear main").tone)

        val first = ResultPresenter.headline(current, null, ComparedTo.NONE, isBaseline = false, endpointName = "Rear main")
        assertEquals("First run", first.text)
        assertTrue(first.sub, first.sub.startsWith("Baseline으로 지정하면"))

        val asBaseline = ResultPresenter.headline(current, null, ComparedTo.NONE, isBaseline = true, endpointName = "Rear main")
        assertEquals("This run is the baseline", asBaseline.text)

        val previous = ResultPresenter.headline(current, c, ComparedTo.PREVIOUS, isBaseline = false, endpointName = "Rear main")
        assertEquals(Tone.NEUTRAL, previous.tone)
    }

    @Test fun barsShareOneScaleWithTheBaselineTick() {
        val base = run(runId = "20260910-100000-000", metrics = listOf(
            launchMetric("1.1", 100.0, 120.0), launchMetric("1.6", 400.0, 420.0),
            launchMetric("2.2", 480.0, 500.0), windowMetric("H.1", 33.3)))
        val current = run(runId = "20260910-110000-000", metrics = listOf(
            launchMetric("1.1", 128.0, 140.0), launchMetric("1.6", 412.0, 430.0),
            launchMetric("2.2", 486.0, 510.0), windowMetric("H.1", 33.6)))
        val bars = ResultPresenter.metricBars(current, RegressionDetector.compare(base, current), ComparedTo.BASELINE)
            .flatMap { it.bars }

        val open = bars.first { it.label == "Open" }
        assertEquals("128 ms", open.valueText)
        assertEquals("+28 ms", open.deltaText)
        // The larger of the two values sits at 80% of the bar, so both the fill and the tick stay on screen.
        assertEquals(0.8, open.fraction, 1e-9)
        assertEquals(0.8 * 100.0 / 128.0, open.baseFraction!!, 1e-9)
    }

    @Test fun frameRateLeadsThePreviewSectionAsHOneUpsideDown() {
        val current = run(metrics = listOf(windowMetric("H.1", 33.6)))
        val preview = ResultPresenter.metricBars(current, null, ComparedTo.NONE).single { it.title == "Preview" }
        // 1000 / 33.6, and it comes before the interval row it is derived from.
        assertEquals("Frame rate", preview.bars.first().label)
        assertEquals("29.8 fps", preview.bars.first().valueText)
        assertEquals("Interval p50", preview.bars[1].label)
    }

    @Test fun barsWithoutAComparisonCarryNoDeltaOrTick() {
        val current = run(metrics = listOf(launchMetric("1.1", 128.0, 140.0)))
        val bars = ResultPresenter.metricBars(current, null, ComparedTo.NONE).flatMap { it.bars }
        assertEquals(1, bars.size)
        assertNull(bars.single().deltaText)
        assertNull(bars.single().baseFraction)
        assertEquals(Tone.NEUTRAL, bars.single().tone)
    }

    // ---- Results list badge ----

    @Test fun aCleanRunCarriesNoStatusBadgeInTheList() {
        assertNull(ResultPresenter.shortStatus(run()))
        assertEquals("점수 제외", ResultPresenter.shortStatus(run(charging = true, flags = listOf(ValidityFlags.CHARGING))))
        assertEquals("비교 불가", ResultPresenter.shortStatus(run(thermalMax = 3, flags = listOf(ValidityFlags.THERMAL_HIGH))))
        assertEquals("측정 무효", ResultPresenter.shortStatus(run(flags = listOf(ValidityFlags.HARD_FAILURE))))
    }

    // ---- every metric as a bar ----

    @Test fun theShownNumberIsTheMedianAndSaysSo() {
        // The value a sampled latency stores is its median (BenchmarkEvaluator sets value = p50), so the row
        // must not borrow statHeader, which names the second statistic the old table put beside it.
        val current = run(metrics = listOf(
            launchMetric("1.1", 142.0, 161.0, n = 9),
            launchMetric("2.2", 164.0, 190.0, n = 25),
            windowMetric("H.1", 33.3),
            countMetric("H.5", 0)
        ))
        val bars = ResultPresenter.metricBars(current, null, ComparedTo.NONE).flatMap { it.bars }
        assertEquals("median", bars.first { it.label == "Open" }.statLabel)
        assertEquals("median", bars.first { it.label == "Capture" }.statLabel)
        // These two already name their statistic, and a count has none.
        assertEquals("", bars.first { it.label == "Interval p50" }.statLabel)
        assertEquals("", bars.first { it.label == "Stalls" }.statLabel)
        assertEquals("0", bars.first { it.label == "Stalls" }.valueText)
    }

    @Test fun barsCoverEveryMeasuredMetricGroupedByCategory() {
        val current = run(metrics = listOf(
            launchMetric("1.1", 142.0, 161.0),
            launchMetric("2.2", 164.0, 190.0),
            windowMetric("H.1", 33.3)
        ))
        val sections = ResultPresenter.metricBars(current, null, ComparedTo.NONE)
        assertEquals(listOf("Launch", "Preview", "Capture"), sections.map { it.title })
        // A metric the run did not measure is left out rather than drawn as an empty bar.
        assertEquals(1, sections.first { it.title == "Launch" }.bars.size)
    }

    @Test fun aTimedOutThreeAMetricGetsNoBar() {
        val timedOut = metric("H.7", 1500.0).copy(timeout = true)
        val bars = ResultPresenter.metricBars(run(metrics = listOf(timedOut)), null, ComparedTo.NONE).flatMap { it.bars }
        val af = bars.single()
        assertEquals("timeout", af.statLabel)
        assertEquals("—", af.valueText)
        assertEquals(0.0, af.fraction, 0.0)
    }

    @Test fun runFactsNameFlagsInWordsAndDropTheFilePath() {
        val charging = run(charging = true, flags = listOf(ValidityFlags.CHARGING, ValidityFlags.LABEL_MISSING))
        val facts = ResultPresenter.runFacts(charging, "samsung SM-S936N", "Rear main", "/data/files/20260923-013403-785.json")
        assertEquals("samsung SM-S936N", facts.first { it.first == "기기" }.second)
        assertEquals("충전 중 · 빌드 이름 없음", facts.first { it.first == "참고 사항" }.second)
        assertEquals("20260923-013403-785.json", facts.first { it.first == "파일" }.second)
        assertEquals("SW42", facts.first { it.first == "측정 대상" }.second)

        val unlabelled = run(subject = SubjectLabel())
        val without = ResultPresenter.runFacts(unlabelled, "samsung SM-S936N", "Rear main", null)
        assertEquals("입력하지 않음", without.first { it.first == "측정 대상" }.second)
        assertTrue(without.none { it.first == "파일" })
    }
}
