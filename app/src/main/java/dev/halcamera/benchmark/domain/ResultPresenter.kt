package dev.halcamera.benchmark.domain

import dev.halcamera.metrics.UnknownReason
import dev.halcamera.metrics.jsonName
import java.util.Locale

/** Which run the delta column is measured against (docs/PLAN-BenchMarker-v0.3.md 7.1, 8.4). */
enum class ComparedTo { BASELINE, PREVIOUS, NONE }

/** How a headline or delta should be coloured; the screen maps GOOD/BAD to its status colours. */
enum class Tone { GOOD, BAD, NEUTRAL }

/**
 * The one-line verdict at the top of the result screen. [sub] carries what the verdict was measured against,
 * so the big line stays a verdict and nothing else.
 */
data class ResultHeadline(val text: String, val sub: String, val tone: Tone)

/**
 * One of the metrics shown with a bar on the result screen. [fraction] and [baseFraction] are 0..1 positions on
 * the scale that every bar of the same unit in the same section shares, so the baseline tick and the bars of the
 * neighbouring rows are both comparable by eye; [baseFraction] is null when there is nothing to compare against.
 *
 * [ownScale] marks a row whose unit appears once in its section — a frame rate among intervals, a stall count
 * among latencies. Its length shares a scale with nobody, so it says nothing about the rows around it, and the
 * screen has to admit that rather than let the bar imply otherwise.
 */
data class KeyMetric(
    val label: String,
    val statLabel: String,
    val valueText: String,
    val deltaText: String?,
    val tone: Tone,
    val fraction: Double,
    val baseFraction: Double?,
    val ownScale: Boolean = false,
    /**
     * Why the row carries no verdict or no bar, in a word or two: a condition mismatch, a unit that changed, a
     * metric only the reference measured. The compare chart used to say this; the bars have to say it now.
     */
    val note: String? = null,
    /** The Camera2 span the metric measures, printed small beside the name; see [MetricInfo.span]. */
    val span: String = "",
    /** A count has no bar: a track and a tick for "0 of 0" said nothing and took a line. */
    val count: Boolean = false,
    /**
     * The reference lies past the end of the track. The scale follows this run's values, so one reference far
     * off (a 13 s AF baseline) no longer squashes every bar of the section to a dot; its tick sits at the end
     * with an arrow instead.
     */
    val baseBeyond: Boolean = false
)

/** One category of [KeyMetric] bars on the result screen. */
data class MetricBarSection(val title: String, val bars: List<KeyMetric>)

/**
 * One metric line of the result table. Empty strings are columns this metric does not have.
 * [note] says why a metric has no verdict, so an UNKNOWN row is never indistinguishable from a stable one.
 */
data class ResultRow(
    val label: String,
    val value: String,
    val stat: String,
    val delta: String,
    val marker: String,
    val note: String = "",
    val statLabel: String = ""
)

data class ResultSection(
    val title: String,
    val valueHeader: String,
    val statHeader: String,
    val deltaHeader: String,
    val rows: List<ResultRow>
)

/**
 * The result screen as text (8.4). [threeALine] is the informational 3A line; [hint] is shown only when there is
 * no baseline yet.
 */
data class ResultView(
    val titleLine: String,
    val subLine: String,
    val eligibilityLine: String,
    val comparisonLine: String,
    val identityLine: String?,
    /** Condition differences between the two runs (7.5). Kept apart from the run's own eligibility line. */
    val conditionLine: String?,
    val hint: String?,
    val sections: List<ResultSection>,
    val threeALine: String?,
    val baselineButton: String,
    val baselineButtonEnabled: Boolean,
    val scoreLine: String? = null
) {
    fun render(): String = buildString {
        appendLine("Camera benchmark")
        appendLine(titleLine)
        appendLine(subLine)
        appendLine(eligibilityLine)
        scoreLine?.let { appendLine(it) }
        appendLine()
        appendLine(comparisonLine)
        identityLine?.let { appendLine("                $it") }
        conditionLine?.let { appendLine("                $it") }
        hint?.let { appendLine(it) }
        appendLine()
        sections.forEach { section ->
            appendLine(ResultPresenter.headerLine(section))
            section.rows.forEach { appendLine(ResultPresenter.rowLine(it)) }
        }
        threeALine?.let { appendLine(it) }
    }
}

/**
 * Turns a finished run and its comparison into the result screen of 8.4. Pure so the layout rules — which
 * statistic the second column shows, what the eligibility headline says, when `SET AS BASELINE` is disabled —
 * are testable without a device.
 */
object ResultPresenter {

    /** Below this sample count nearest-rank p95 is just max, and calling it p95 suggests a stable percentile (8.4). */
    const val P95_MIN_SAMPLES = 20

    private val ORDER = listOf(Category.LAUNCH, Category.PREVIEW, Category.CAPTURE, Category.STABILITY, Category.RECORD)

    /** Category names as the screen prints them: first letter capital, the rest lower, no underscores. */
    fun categoryLabel(category: Category): String =
        if (category == Category.THREE_A) "3A"
        else category.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    /**
     * A validity flag in words. The codes are the stored contract and stay in the JSON and the CSV, but on
     * screen "CHARGING" asks the reader to know the flag table to learn that the phone was plugged in.
     */
    fun flagText(code: String): String = when (code) {
        "ABORTED" -> "실행 중단됨"
        "HARD_FAILURE" -> "측정 실패"
        "PROFILE_UNSUPPORTED" -> "이 카메라가 지원하지 않는 설정"
        "INSUFFICIENT_SAMPLES" -> "표본 부족"
        "CADENCE_NOT_FIXED" -> "프레임 간격이 고정되지 않음"
        "THERMAL_HIGH" -> "발열 높음"
        "POWER_SAVE_MODE" -> "절전 모드"
        "CHARGING" -> "충전 중"
        "BATTERY_LOW" -> "배터리 부족"
        "PROFILE_DRAFT" -> "초안 profile"
        "DEBUGGABLE_BUILD" -> "디버그 빌드"
        "RECORD_NOT_MEASURED" -> "녹화 측정이 부족함"
        "PREFLIGHT_MISMATCH" -> "사전 점검과 실제 설정이 다름"
        "THERMAL_CHANGED" -> "실행 중 발열 단계 변함"
        "LABEL_MISSING" -> "빌드 이름 없음"
        else -> code
    }

    /**
     * The run's identity as label and value pairs, for the fold under the metrics.
     *
     * It replaces seven sentences that repeated each other — the eligibility line named the same flags the
     * summary listed again as codes — with one row per fact, so a reader can find the one they came for.
     */
    fun runFacts(run: BenchmarkRun, deviceName: String, endpointName: String, file: String?): List<Pair<String, String>> {
        val launch = run.metric("1.1")?.sampleCount ?: 0
        val still = run.metric("2.2")?.sampleCount ?: 0
        val thermal = listOfNotNull(run.env.thermalStart, run.env.thermalMax, run.env.thermalEnd)
        return listOfNotNull(
            "기기" to deviceName,
            "카메라" to "$endpointName · ID ${run.endpoint.logicalCameraId}",
            localTime(run.runId)?.let { "측정 시각" to it },
            run.device.buildDisplay.takeIf { it.isNotBlank() }?.let { "OS 빌드" to it },
            "측정 대상" to (listOfNotNull(
                run.subject.subjectBuildLabel?.takeIf { it.isNotBlank() },
                run.subject.subjectCommit?.takeIf { it.isNotBlank() }
            ).joinToString(" · ").ifEmpty { "입력하지 않음" }),
            "표본" to "열기 ${launch}회 · 촬영 ${still}회",
            thermal.takeIf { it.size == 3 }?.let { "발열 단계" to it.joinToString(" → ") },
            run.validity.flags.takeIf { it.isNotEmpty() }?.let { flags ->
                "참고 사항" to flags.joinToString(" · ") { flagText(it) }
            },
            file?.let { "파일" to it.substringAfterLast('/').substringAfterLast('\\') }
        )
    }

    /**
     * What the screen calls the run its ticks stand for: Baseline, 이전 run, or 비교 대상 for the run picked first in
     * run history. "선택한 run" is the run the reader opened, the bars, so the ticks cannot use it too.
     */
    fun referenceName(comparedTo: ComparedTo, selectedReference: Boolean): String = when {
        comparedTo == ComparedTo.BASELINE -> "Baseline"
        selectedReference -> "비교 대상"
        else -> "이전 run"
    }

    /**
     * The run the ticks stand for, as label and value pairs for the same 실행 정보 fold. The compare screen
     * carried these; with that screen gone, the result card is the only place they can live.
     *
     * The labels use the name the rest of the screen uses ("Baseline", "Baseline OS"), not a new word for it.
     */
    fun referenceFacts(reference: BenchmarkRun, comparison: RunComparison?, role: String): List<Pair<String, String>> {
        val thermal = listOfNotNull(reference.env.thermalStart, reference.env.thermalMax, reference.env.thermalEnd)
        return listOfNotNull(
            role to listOfNotNull(
                localTime(reference.runId) ?: reference.runId,
                reference.subject.subjectBuildLabel?.takeIf { it.isNotBlank() },
                reference.subject.subjectCommit?.takeIf { it.isNotBlank() }
            ).joinToString(" · "),
            reference.device.buildDisplay.takeIf { it.isNotBlank() }?.let { "$role OS" to it },
            thermal.takeIf { it.size == 3 }?.let { "$role 발열" to it.joinToString(" → ") },
            comparison?.identity?.let { "빌드 비교" to identityLine(it) }
        )
    }

    fun scoreLine(run: BenchmarkRun): String? {
        if (run.scoringRuleVersion != ScoreComposer.VERSION || run.endpointScore == null) return null
        val score = ScoreComposer.compose(run, S25PlusScoreDraft.calibration) ?: return null
        val categoryText = score.categories.entries.joinToString(" · ") {
            "${categoryLabel(it.key)} ${String.format(Locale.US, "%.0f", it.value)}"
        }
        return "Camera Endpoint Score ${score.total} / 1000\nInternal draft · ${ScoreComposer.VERSION}\n$categoryText\nTracks changes on the same model and camera · not a cross-device ranking"
    }

    /** The score total alone, for the headline card; null under the same conditions as [scoreLine]. */
    fun scoreValue(run: BenchmarkRun): Int? {
        if (run.scoringRuleVersion != ScoreComposer.VERSION || run.endpointScore == null) return null
        return ScoreComposer.compose(run, S25PlusScoreDraft.calibration)?.total
    }

    fun present(
        run: BenchmarkRun,
        comparison: RunComparison?,
        comparedTo: ComparedTo,
        isBaseline: Boolean,
        deviceName: String,
        endpointName: String
    ): ResultView {
        // Column names appear once, above the first section (8.4): every section below shares the same columns.
        val sections = ORDER.mapNotNull { section(it, run, comparison, comparedTo) }
            .mapIndexed { i, s -> if (i == 0) s else s.copy(valueHeader = "", statHeader = "", deltaHeader = "") }
        return ResultView(
            titleLine = listOf(deviceName, endpointName, run.profile.id, run.profile.launchMode.jsonName.replace('_', ' ')).joinToString(" · "),
            subLine = listOfNotNull(
                localTime(run.runId), run.device.buildDisplay.takeIf { it.isNotBlank() },
                run.subject.subjectBuildLabel?.takeIf { it.isNotBlank() } ?: "(subject 없음)"
            ).joinToString(" · "),
            eligibilityLine = eligibilityLine(run),
            scoreLine = scoreLine(run),
            comparisonLine = comparisonLine(run, comparison, comparedTo, isBaseline),
            identityLine = comparison?.identity?.let(::identityLine),
            conditionLine = comparison?.let(::conditionLine),
            // The hint names a button that is no longer on the screen once this run is itself the baseline: the
            // button reads CLEAR BASELINE by then, so telling the reader to press SET AS BASELINE describes
            // nothing they can do. Being compared against a baseline and being one are separate states.
            hint = if (comparedTo == ComparedTo.BASELINE || isBaseline) null
            else "[ baseline으로 지정 ]을 누르면 이 run이 baseline이 됩니다",
            sections = sections,
            threeALine = threeALine(run),
            baselineButton = if (isBaseline) "baseline 해제" else "baseline으로 지정",
            // Clearing must stay possible even if the run later became ineligible under a changed flag table.
            baselineButtonEnabled = isBaseline || run.validity.comparisonEligible
        )
    }

    // ---- headline and key metrics ----

    /**
     * The verdict-first headline. It says one thing in large type — degraded, clean, or why there is no verdict —
     * and moves everything the verdict was measured against into [ResultHeadline.sub].
     */
    /**
     * The camera sits on its own line under what the verdict was measured against. Run on after " · " it wrapped
     * in the middle of its own name ("… · Camera ·" / "0 (Wide · Rear)").
     */
    fun headline(
        run: BenchmarkRun,
        comparison: RunComparison?,
        comparedTo: ComparedTo,
        isBaseline: Boolean,
        endpointName: String,
        /** Run history's 두 실행 비교: the reference was picked by hand, not found as a baseline or previous run. */
        selectedReference: Boolean = false
    ): ResultHeadline {
        val vs = comparison?.baseRunId?.let { localTime(it) ?: it }
        return when {
            !run.validity.measurementValid ->
                ResultHeadline("Measurement invalid", eligibilityLine(run), Tone.BAD)
            comparison == null || comparedTo == ComparedTo.NONE ->
                if (isBaseline) ResultHeadline("This run is the baseline", "비교할 이전 run이 없습니다\n$endpointName", Tone.NEUTRAL)
                else ResultHeadline("First run", "Baseline으로 지정하면 다음 run부터 비교합니다\n$endpointName", Tone.NEUTRAL)
            selectedReference && comparedTo != ComparedTo.BASELINE ->
                ResultHeadline("No verdict", "비교 대상($vs) 대비\nbaseline이 아니라서 판정하지 않습니다", Tone.NEUTRAL)
            comparedTo == ComparedTo.PREVIOUS ->
                ResultHeadline(
                    if (isBaseline) "This run is the baseline" else "No baseline",
                    "이전 run($vs) 대비 표시 · baseline 없이는 판정하지 않습니다",
                    Tone.NEUTRAL
                )
            comparison.judgedCount == 0 ->
                ResultHeadline("No verdict", "비교 조건을 만족하는 지표가 없습니다 · baseline $vs", Tone.NEUTRAL)
            comparison.hasRegression ->
                ResultHeadline(
                    "${comparison.regressedCount} ${if (comparison.regressedCount == 1) "metric" else "metrics"} degraded",
                    "baseline($vs) 대비\n$endpointName",
                    Tone.BAD
                )
            else -> ResultHeadline("No degradation", "baseline($vs) 대비\n$endpointName", Tone.GOOD)
        }
    }

    /**
     * Every measured metric as a bar, grouped by category, in catalog order.
     *
     * The result screen draws these instead of a table behind a fold: a number beside its baseline is the
     * question the screen exists to answer, and a row of digits answers it worse than a bar does. A metric
     * neither run measured is left out. One only the reference measured keeps its row with the reference tick
     * and no fill: this card is the only comparison view now, and the compare chart it replaced listed such rows.
     *
     * Every bar of the same unit inside a section shares one scale. While each bar scaled itself against its own
     * baseline, an 11 ms row and a 561 ms row were drawn the same length, which is the opposite of what a column
     * of bars promises the eye (#137). The sharing stops at the section and at the unit: a frame rate and a
     * millisecond do not belong on one axis, and Preview's 33 ms intervals would vanish beside Capture's
     * hundreds of milliseconds.
     */
    fun metricBars(
        run: BenchmarkRun,
        comparison: RunComparison?,
        comparedTo: ComparedTo,
        /** The run the ticks stand for; only needed to notice a metric whose unit changed between the two. */
        reference: BenchmarkRun? = null,
        /** The reference was picked in run history, so notes call it "비교 대상" rather than the previous run. */
        selectedReference: Boolean = false
    ): List<MetricBarSection> {
        val withDelta = comparedTo != ComparedTo.NONE
        // Colour is a verdict, and only a baseline is judged against (7.1). Against the previous run or a run
        // picked in history the bars stay neutral, as the compare chart did; they used to turn red there too.
        val judged = comparedTo == ComparedTo.BASELINE
        // The note calls the reference by the name the rest of the screen uses. "기준 run은 timeout" brought in a word
        // the screen used nowhere else, and the reader had to work out that it meant the baseline.
        val referenceName = referenceName(comparedTo, selectedReference)
        return (ORDER + Category.THREE_A).mapNotNull { category ->
            val measured = BenchmarkMetricCatalog.ids.mapNotNull { id ->
                val info = BenchmarkMetricCatalog.info(id) ?: return@mapNotNull null
                if (info.category != category) return@mapNotNull null
                val metric = run.metric(id)
                val cmp = comparison?.metric(id)
                val refMetric = reference?.metric(id)
                when {
                    metric?.value == null -> {
                        // Only the reference measured it. Dropping the row would hide that this run lost a metric.
                        val base = cmp?.baselineValue?.takeIf { withDelta } ?: return@mapNotNull null
                        BarInput(info.short, "", null, base, info.unit, cmp, fine(info.id, info.category),
                            note = "${referenceName}에만 있음", span = info.span, id = info.id)
                    }
                    // A timed-out 3A metric stores the observation window as its value (plan chapter 13), so a bar
                    // would compare a window against a convergence. It keeps its row and says so instead.
                    metric.timeout -> BarInput(info.short, "timeout", null, null, info.unit, null, false, span = info.span)
                    // A reference that timed out stored its observation window, not a convergence (plan chapter 13).
                    // Drawing it as a tick compared 562 ms of AF against a 13 s window on a Galaxy S25+ and printed
                    // "-12448 ms · -96%"; the row keeps this run's bar and says why there is nothing to compare.
                    refMetric?.timeout == true ->
                        BarInput(info.short, "", metric.value, null, info.unit, null,
                            fine(info.id, info.category), note = "${referenceName}은 timeout", span = info.span, id = info.id)
                    // History can pair runs of different contracts; a tick in another unit would be a lie.
                    refMetric != null && refMetric.unit != metric.unit ->
                        BarInput(info.short, "", metric.value, null, info.unit, null,
                            fine(info.id, info.category), note = "단위 다름", span = info.span, id = info.id)
                    // No per-row "median": nearly every value is one, and the card's legend says so once.
                    else -> BarInput(info.short, "", metric.value,
                        cmp?.baselineValue, info.unit, cmp, fine(info.id, info.category), span = info.span, id = info.id)
                }
            }
            // Frame rate leads the preview section: it is H.1 turned upside down, and "29.8 fps" answers the
            // question the interval only implies. The verdict still belongs to H.1, whose row follows it.
            val inputs = if (category == Category.PREVIEW) listOfNotNull(frameRate(run, comparison)) + measured else measured
            if (inputs.isEmpty()) return@mapNotNull null
            val scales = scales(inputs)
            val drawn = inputs.filter { it.value != null || it.base != null }.groupingBy { it.scaleKey }.eachCount()
            MetricBarSection(categoryLabel(category), inputs.map { input ->
                bar(input, scales[input.scaleKey] ?: 1.0, ownScale = (drawn[input.scaleKey] ?: 0) < 2, withDelta = withDelta,
                    judged = judged, comparedTo = comparedTo)
            })
        }
    }

    private fun frameRate(run: BenchmarkRun, comparison: RunComparison?): BarInput? {
        val fps = run.metric("H.1")?.value?.takeIf { it > 0 }?.let { 1000.0 / it } ?: return null
        val cmp = comparison?.metric("H.1")
        val baseFps = cmp?.baselineValue?.takeIf { it > 0 }?.let { 1000.0 / it }
        return BarInput("Frame rate", "", fps, baseFps, "fps", cmp, false, span = "1000 / Interval p50")
    }

    /** A bar before its section's scale is known. [value] is null only for a timed-out row, which draws no fill. */
    private class BarInput(
        val label: String,
        val statLabel: String,
        val value: Double?,
        val base: Double?,
        val unit: String,
        val cmp: MetricComparison?,
        val fine: Boolean,
        val note: String? = null,
        val span: String = "",
        val id: String = ""
    ) {
        /**
         * Which bars share a scale. A jitter is a spread of nanoseconds, five orders of magnitude below the 33 ms
         * intervals of its section; on their axis its bar and tick were both invisible, so it gets its own.
         */
        val scaleKey: String get() = if (id in SPREAD_METRICS) "$unit-spread" else unit
    }

    /**
     * One scale per unit in a section: the largest value or baseline tick of the group sits at 80% of the bar,
     * so however far the two runs are apart, no fill and no tick can be pushed off the track. A group whose
     * values are all zero would make the scale 0 and every fraction NaN, so it falls back to empty bars.
     */
    private fun scales(inputs: List<BarInput>): Map<String, Double> =
        inputs.filter { it.value != null || it.base != null }.groupBy { it.scaleKey }.mapValues { (_, group) ->
            // This run's values set the scale; a reference only when this run has none in the group. A far-off
            // reference used to set it and flatten every bar beside it (AE 404 ms drawn as a dot next to a 13 s
            // AF baseline).
            val values = group.mapNotNull { it.value }
            val top = if (values.isNotEmpty()) values.max() else group.maxOf { it.base ?: 0.0 }
            (top / 0.8).takeIf { it > 0 } ?: 1.0
        }

    private fun bar(
        input: BarInput,
        scale: Double,
        ownScale: Boolean,
        withDelta: Boolean,
        judged: Boolean,
        comparedTo: ComparedTo
    ): KeyMetric {
        val isCount = input.unit == "count"
        val value = input.value
            ?: return KeyMetric(input.label, input.statLabel, "—", null, Tone.NEUTRAL, 0.0,
                input.base?.let { (it / scale).coerceIn(0.0, 1.0) }, ownScale = input.base != null && ownScale,
                note = input.note, span = input.span, count = isCount,
                baseBeyond = input.base?.let { it / scale > 1.0 } ?: false)
        val base = input.base
        val cmp = input.cmp
        val delta = if (!withDelta || base == null) null else {
            val d = value - base
            // The percentage the compare chart drew, so dropping that chart loses nothing. A count has none: a
            // percentage of a small count says more about the count than about the change (7.2).
            val pct = if (input.unit == "count" || base <= 0.0) null else (value - base) / base * 100.0
            // Extra decimals only when the percentage says something moved; otherwise "+0.04 ms · 0%" dresses up noise.
            val moved = pct != null && kotlin.math.abs(pct) >= 0.5
            val amount = when (input.unit) {
                "fps" -> signedAmount(d, 1, moved)?.let { "$it fps" }
                // A count difference is already the whole story, and "+3 count" reads as a unit nobody uses.
                "count" -> if (d == 0.0) null else String.format(Locale.US, "%+.0f", d)
                // A change of a fraction of a millisecond is the whole point of a jitter row; "+0 ms" is not.
                "ms" -> signedAmount(d, if (input.fine) 1 else 0, moved)?.let { "$it ms" }
                else -> signedAmount(d, 0, moved)?.let { "$it ${input.unit}" }
            }
            when {
                pct == null -> amount
                amount == null -> percent(pct)
                else -> "$amount · ${percent(pct)}"
            }
        }
        val tone = when {
            !withDelta || !judged || cmp == null -> Tone.NEUTRAL
            cmp.state == RegressionState.REGRESSED -> Tone.BAD
            cmp.state == RegressionState.IMPROVED -> Tone.GOOD
            else -> Tone.NEUTRAL
        }
        val note = input.note ?: if (withDelta) noteFor(cmp, comparedTo).takeIf { it.isNotEmpty() } else null
        return KeyMetric(
            label = input.label,
            statLabel = input.statLabel,
            valueText = formatValue(value, input.unit, input.fine),
            deltaText = delta,
            tone = tone,
            fraction = (value / scale).coerceIn(0.0, 1.0),
            baseFraction = base?.let { (it / scale).coerceIn(0.0, 1.0) },
            ownScale = ownScale,
            note = note,
            span = input.span,
            count = isCount,
            baseBeyond = base != null && base / scale > 1.0
        )
    }

    /**
     * A signed change for the delta line, or null when it should be left out.
     *
     * "-0 ms" beside "-5%" read as a contradiction once the percentage sat next to it (10.3 ms to 10.0 ms). When
     * the percentage shows a change ([moved]), the amount takes more decimals, up to two, until it shows too
     * ("-0.3 ms"); if even that rounds to zero, as a record jitter of hundredths of a millisecond does, the amount
     * is dropped and the percentage speaks alone. When nothing moved, a zero is printed unsigned.
     */
    private fun signedAmount(d: Double, decimals: Int, moved: Boolean): String? {
        fun zero(places: Int) = kotlin.math.abs(d) < 0.5 * Math.pow(10.0, -places.toDouble())
        var places = decimals
        if (moved) while (places < 2 && zero(places)) places++
        return when {
            !zero(places) -> String.format(Locale.US, "%+.${places}f", d)
            moved -> null
            else -> String.format(Locale.US, "%.${decimals}f", 0.0)
        }
    }

    /** "+4%", "-19%", and "0%" rather than "-0%" for a change that rounds away. */
    private fun percent(pct: Double): String =
        if (kotlin.math.abs(pct) < 0.5) "0%" else String.format(Locale.US, "%+.0f%%", pct)

    // ---- headline ----

    /**
     * The three eligibility steps of 5.3 in one line. The blocking flags are named because "비교 불가" alone does
     * not tell the developer whether to re-run in a cooler state or to fix the measurement.
     */
    /**
     * The eligibility of a run in two or three words, for the Results list. The full line names the blocking
     * flags, which is what the result screen needs; a list row only has to say whether this run is worth
     * opening, so a clean run gets no badge at all.
     */
    fun shortStatus(run: BenchmarkRun): String? = when {
        !run.validity.measurementValid -> "측정 무효"
        !run.validity.comparisonEligible -> "비교 불가"
        !run.validity.scoringEligible -> "점수 제외"
        else -> null
    }

    fun eligibilityLine(run: BenchmarkRun): String {
        val v = run.validity
        val flags = v.flags.mapNotNull { ValidityFlags.byCode(it) }
        fun codes(select: (ValidityFlag) -> Boolean) =
            flags.filter(select).joinToString(", ") { it.code }.ifEmpty { v.flags.joinToString(", ") }
        val head = when {
            !v.measurementValid -> "Measurement invalid (${codes { it.blocksMeasurement }})"
            !v.comparisonEligible -> "Not comparable (${codes { it.blocksComparison }})"
            !v.scoringEligible -> "Comparable · excluded from scoring (${codes { it.blocksScoring }})"
            else -> "Comparable · scorable"
        }
        val thermal = listOf(run.env.thermalStart, run.env.thermalMax, run.env.thermalEnd)
        val thermalText = if (thermal.any { it == null }) null else thermal.joinToString(" → ")
        val suffix = if (v.comparisonEligible) null else "baseline 지정 불가"
        return listOfNotNull(head, thermalText?.let { "thermal $it" }, suffix).joinToString(" · ")
    }

    /**
     * A run measured against a reference is not always a run without a baseline: the baseline itself has nothing
     * to compare against but its predecessor, so [isBaseline] decides which of the two facts the line reports.
     */
    fun comparisonLine(
        run: BenchmarkRun,
        comparison: RunComparison?,
        comparedTo: ComparedTo,
        isBaseline: Boolean = false
    ): String = when {
        comparison == null || comparedTo == ComparedTo.NONE ->
            if (isBaseline) "This run is the baseline · no earlier run to compare"
            else "No baseline · no earlier run to compare"
        // No metric could be judged: saying "no degradation" here would read as a clean result rather than as a
        // comparison that never happened (PR #21 review).
        comparison.judgedCount == 0 && comparedTo == ComparedTo.BASELINE ->
            "No verdict   baseline ${comparison.baseRunId} · no metric met the comparison conditions"
        comparedTo == ComparedTo.PREVIOUS ->
            if (isBaseline) "This run is the baseline · shown vs previous run ${comparison.baseRunId}"
            else "No baseline · shown vs previous run ${comparison.baseRunId}"
        comparison.hasRegression -> "▲ ${comparison.regressedCount} degraded   baseline ${comparison.baseRunId}"
        else -> "No degradation   baseline ${comparison.baseRunId}"
    }

    /**
     * 7.5 condition differences. These belong to the pair, not to either run, so they cannot appear in the
     * eligibility line: two runs that are each perfectly eligible can still be incomparable, and a charging
     * difference keeps every verdict while being the only thing that explains a shifted number.
     */
    fun conditionLine(comparison: RunComparison): String? {
        if (comparison.conditionMismatches.isEmpty()) return null
        return "비교 시점 조건 차이: " + comparison.conditionMismatches.joinToString(" · ", transform = ::conditionText)
    }

    fun conditionText(m: ConditionMismatch): String = when (m) {
        ConditionMismatch.THERMAL_MAX_DIFFERS -> "thermal 최고값 2단계 이상 차이"
        ConditionMismatch.POWER_SAVE_DIFFERS -> "절전 모드 다름"
        ConditionMismatch.CHARGING_DIFFERS -> "충전 상태 다름"
        // "(3A 제외)" read as "3A aside"; what it means is that 3A is not judged under this difference.
        ConditionMismatch.EXPOSURE_DIFFERS -> "노출 부하 4배 이상 차이 → 3A 판정 안 함"
    }

    /** 7.4: four axes summarised in one line; the subject axis is dropped when neither side is labelled. */
    fun identityLine(id: BuildIdentityComparison): String = listOfNotNull(
        "Android " + same(id.sameSystemFingerprint),
        "Camera build " + (id.sameCameraBuild?.let(::same) ?: "unknown"),
        "App " + same(id.sameAppVersion),
        id.sameAppBuild?.let { "App build " + same(it) },
        listOfNotNull(id.sameSubjectLabel, id.sameSubjectCommit).let { axes ->
            if (axes.isEmpty()) null else "subject " + same(axes.all { it })
        }
    ).joinToString(" · ")

    private fun same(v: Boolean) = if (v) "same" else "differs"

    // ---- table ----

    private fun section(category: Category, run: BenchmarkRun, comparison: RunComparison?, comparedTo: ComparedTo): ResultSection? {
        // The catalog is declared in display order, so iterating its ids is the row order of 8.4.
        val metrics = BenchmarkMetricCatalog.ids
            .mapNotNull { run.metric(it) }
            .filter { it.category == category }
        if (metrics.isEmpty()) return null
        val rows = metrics.map { row(it, comparison?.metric(it.id), comparedTo) }
        return ResultSection(
            title = categoryLabel(category),
            valueHeader = "p50",
            statHeader = metrics.mapNotNull { statHeader(it) }.firstOrNull().orEmpty(),
            // Abbreviated so the column and its markers fit the card. Which run the delta is against is spelled
            // out in full on the comparison line above the table, so the header only has to distinguish the two.
            deltaHeader = when (comparedTo) {
                ComparedTo.BASELINE -> "vs base"
                ComparedTo.PREVIOUS -> "vs prev"
                ComparedTo.NONE -> ""
            },
            rows = rows
        )
    }

    private fun row(metric: BenchmarkMetric, comparison: MetricComparison?, comparedTo: ComparedTo): ResultRow {
        val info = BenchmarkMetricCatalog.info(metric.id)
        return ResultRow(
            label = info?.short ?: metric.id,
            value = format(metric, metric.value),
            stat = statHeader(metric)?.let { format(metric, statValue(metric)) }.orEmpty(),
            // With nothing to compare against there is no delta column at all, so the cell has to be empty. A
            // dash would claim the column exists and that every metric happens to be unmeasurable in it, which
            // is a different and much more alarming statement than "this is the first run".
            delta = if (comparedTo == ComparedTo.NONE) "" else delta(metric, comparison),
            // A reference delta carries no state, so it never gets the regression marker (8.4).
            marker = if (comparedTo == ComparedTo.BASELINE) marker(comparison?.state) else "",
            note = noteFor(comparison, comparedTo),
            statLabel = statHeader(metric).orEmpty()
        )
    }

    /**
     * Why this row has no verdict. Without it an UNKNOWN row and a STABLE row look the same, and the reason a
     * comparison did not happen is exactly what tells the developer whether to re-run or to look at the code.
     */
    fun noteFor(comparison: MetricComparison?, comparedTo: ComparedTo): String {
        if (comparison == null || comparedTo == ComparedTo.NONE) return ""
        if (comparison.state != RegressionState.UNKNOWN) return ""
        return when (comparison.unknownReason) {
            UnknownReason.CONDITION_MISMATCH -> "조건 불일치"
            UnknownReason.NOT_MEASURABLE -> "판정 불가"
            UnknownReason.INSUFFICIENT_SAMPLES -> "표본 부족"
            UnknownReason.NOT_RUN -> "미실행"
            UnknownReason.NO_BASELINE -> "baseline 없음"
            UnknownReason.UNSUPPORTED -> "미지원"
            UnknownReason.CADENCE_CHANGED -> "cadence 변경"
            null -> ""
        }
    }

    /**
     * Only metrics whose sample count the profile fixes (launch, still) get a second statistic column: an
     * observation-window metric is already a percentile of its own distribution, so a second one beside it
     * would read as a percentile of a percentile.
     */
    fun statHeader(metric: BenchmarkMetric): String? {
        if (metric.samples == null || metric.sampleCount < 2 || statValue(metric) == null) return null
        return if (metric.sampleCount >= P95_MIN_SAMPLES) "p95" else "max"
    }

    fun statValue(metric: BenchmarkMetric): Double? =
        if (metric.sampleCount >= P95_MIN_SAMPLES) metric.p95 else metric.max

    /** COUNT metrics compare as a difference in counts; latency metrics as a percentage (7.2). */
    fun delta(metric: BenchmarkMetric, comparison: MetricComparison?): String {
        if (comparison == null) return ""
        val rule = RegressionRules.rule(metric.id)
        val b = comparison.baselineValue
        val c = comparison.currentValue
        if (b == null || c == null) return "—"
        if (rule?.kind == RuleKind.COUNT) {
            val d = (c - b).toInt()
            return if (d > 0) "+$d" else d.toString()
        }
        val pct = comparison.deltaPct ?: return "—"
        return String.format(Locale.US, "%+.0f%%", pct).replace("+0%", "0%")
    }

    private fun marker(state: RegressionState?) = when (state) {
        RegressionState.REGRESSED -> "▲"
        RegressionState.IMPROVED -> "▼"
        else -> ""
    }

    /** 3A stays informational until M5 (8.4): one line, and a timeout is named rather than shown as a duration. */
    fun threeALine(run: BenchmarkRun): String? {
        val metrics = listOf("H.6", "H.7", "H.8").map { run.metric(it) }
        if (metrics.all { it?.value == null && it?.timeout != true }) return null
        val values = metrics.joinToString(" / ") { m ->
            when {
                m == null -> "—"
                m.timeout -> "timeout"
                m.value == null -> "—"
                else -> format(m, m.value).removeSuffix(" ms")
            }
        }
        return "3A (informational)\n  AE / AF / AWB   $values ms"
    }

    // ---- formatting ----

    /**
     * Cadence metrics need the tenth of a millisecond that separates 33.3 from 33.4; the rest do not. Recording
     * jitter is such a metric even though its category is RECORD: it is a fraction of a millisecond, and
     * rounding it to whole milliseconds would print every value as 0.
     *
     * A frame rate keeps one decimal for the same reason in the other direction: 29.8 and 30.0 are the
     * difference between a steady recording and one that dropped a frame in a window.
     */
    fun format(metric: BenchmarkMetric, value: Double?): String {
        if (value == null) return "—"
        return formatValue(value, metric.unit, fine(metric.id, metric.category))
    }

    /**
     * One formatter for both places a measured number is printed: the bars and the monospace rows. They had
     * separate copies, and the decimal rule reached only one of them, so recording jitter printed as "0 ms" on
     * the screen while the test of the other copy was green (device check, 2026-09-24).
     */
    internal fun formatValue(value: Double, unit: String, fine: Boolean): String = when (unit) {
        "count" -> String.format(Locale.US, "%.0f", value)
        "fps" -> String.format(Locale.US, "%.1f fps", value)
        // A preview jitter of a few microseconds printed "0.0 ms", then "0.00 ms", beside a -19% change. Below
        // 0.1 ms the value is shown in microseconds, the unit a HAL developer reads sensor jitter in anyway.
        "ms" -> when {
            // Sensor timestamps are nanoseconds, and a steady HAL keeps their spread under a microsecond: "0 µs"
            // (seen on versionCode 401) hid a -19% change as well, so below 1 µs the value goes to nanoseconds.
            fine && kotlin.math.abs(value) < 0.001 -> String.format(Locale.US, "%.0f ns", value * 1_000_000.0)
            fine && kotlin.math.abs(value) < 0.1 -> String.format(Locale.US, "%.0f µs", value * 1000.0)
            fine -> String.format(Locale.US, "%.1f ms", value)
            else -> String.format(Locale.US, "%.0f ms", value)
        }
        // A unit this app does not define comes from a run written by another version; it is printed as stored
        // rather than rounded to a precision this version invented for it.
        else -> "$value $unit"
    }

    /** True for the millisecond metrics whose values are small enough that a whole millisecond hides them. */
    internal fun fine(id: String, category: Category): Boolean =
        category == Category.PREVIEW || id in FINE_MS_METRICS

    /** Spreads (standard deviations) of sensor intervals; they scale apart from the intervals themselves. */
    private val SPREAD_METRICS = setOf("H.10", "3.7")

    /** Millisecond metrics outside PREVIEW that are still small enough to need a decimal. */
    private val FINE_MS_METRICS = setOf("3.7")

    // ---- monospace layout ----

    /**
     * The table has to fit the card, which is about 303dp wide on a phone. In a real monospace face at 10sp that
     * is roughly 45 cells, and these four columns plus the marker come to 44.
     *
     * The widths were larger when the face was not actually monospaced: nothing lined up, so nothing revealed
     * that the line had grown past the screen. Once it did line up the verdict markers sat off the right edge,
     * reachable only by scrolling, which is the wrong place for the one thing the reader is looking for.
     *
     * LABEL fits the longest catalog name ("Capture stalls") plus its indent and a separator; DELTA fits
     * "vs base" and a two-cell gap.
     */
    private const val LABEL = 17
    private const val VALUE = 8
    private const val STAT = 8
    private const val DELTA = 8

    // Columns are padded and then the line is trimmed: an empty trailing column must not leave stray spaces
    // behind, because the result text is copied to a PC as often as it is read on the phone.
    fun headerLine(section: ResultSection): String =
        (pad(section.title, LABEL) + right(section.valueHeader, VALUE) + right(section.statHeader, STAT) + right(section.deltaHeader, DELTA)).trimEnd()

    fun rowLine(row: ResultRow): String =
        (pad("  " + row.label, LABEL) + right(row.value, VALUE) + right(row.stat, STAT) + right(row.delta, DELTA) +
            listOf(row.marker, row.note).filter { it.isNotEmpty() }.joinToString(" ", prefix = "  ")).trimEnd()

    private fun pad(s: String, width: Int) = if (s.length >= width) s else s + " ".repeat(width - s.length)
    private fun right(s: String, width: Int) = if (s.length >= width) s else " ".repeat(width - s.length) + s

    /** Run ids are local time already (yyyyMMdd-HHmmss-SSS), so the headline only has to re-punctuate them. */
    fun localTime(runId: String): String? {
        val m = Regex("^(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})").find(runId) ?: return null
        val (y, mo, d, h, mi) = m.destructured
        return "$y-$mo-$d $h:$mi"
    }
}
