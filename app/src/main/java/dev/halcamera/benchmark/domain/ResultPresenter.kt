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
 * One of the four metrics shown with a bar on the result screen. [fraction] and [baseFraction] are 0..1
 * positions on a shared scale, so the bar and the baseline tick are comparable by eye; [baseFraction] is null
 * when there is nothing to compare against.
 */
data class KeyMetric(
    val label: String,
    val statLabel: String,
    val valueText: String,
    val deltaText: String?,
    val tone: Tone,
    val fraction: Double,
    val baseFraction: Double?
)

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

    private val ORDER = listOf(Category.LAUNCH, Category.PREVIEW, Category.CAPTURE, Category.STABILITY)

    /** Category names as the screen prints them: first letter capital, the rest lower, no underscores. */
    fun categoryLabel(category: Category): String = category.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

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
            else "[ Set as baseline ]을 누르면 이 run이 기준이 됩니다",
            sections = sections,
            threeALine = threeALine(run),
            baselineButton = if (isBaseline) "Clear baseline" else "Set as baseline",
            // Clearing must stay possible even if the run later became ineligible under a changed flag table.
            baselineButtonEnabled = isBaseline || run.validity.comparisonEligible
        )
    }

    // ---- headline and key metrics ----

    /**
     * The verdict-first headline. It says one thing in large type — degraded, clean, or why there is no verdict —
     * and moves everything the verdict was measured against into [ResultHeadline.sub].
     */
    fun headline(
        run: BenchmarkRun,
        comparison: RunComparison?,
        comparedTo: ComparedTo,
        isBaseline: Boolean,
        endpointName: String
    ): ResultHeadline {
        val vs = comparison?.baseRunId?.let { localTime(it) ?: it }
        return when {
            !run.validity.measurementValid ->
                ResultHeadline("Measurement invalid", eligibilityLine(run), Tone.BAD)
            comparison == null || comparedTo == ComparedTo.NONE ->
                if (isBaseline) ResultHeadline("This run is the baseline", "비교할 이전 run이 없습니다 · $endpointName", Tone.NEUTRAL)
                else ResultHeadline("First run", "Baseline으로 지정하면 다음 run부터 비교합니다 · $endpointName", Tone.NEUTRAL)
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
                    "baseline($vs) 대비 · $endpointName",
                    Tone.BAD
                )
            else -> ResultHeadline("No degradation", "baseline($vs) 대비 · $endpointName", Tone.GOOD)
        }
    }

    /** The four metrics the card shows without unfolding anything: launch, first frame, capture, frame rate. */
    val KEY_METRIC_IDS = listOf("1.1", "1.6", "2.2")

    fun keyMetrics(run: BenchmarkRun, comparison: RunComparison?, comparedTo: ComparedTo): List<KeyMetric> {
        val withDelta = comparedTo != ComparedTo.NONE
        val rows = KEY_METRIC_IDS.mapNotNull { id ->
            val metric = run.metric(id) ?: return@mapNotNull null
            val label = when (id) {
                "1.1" -> "Camera open"
                "1.6" -> "First frame"
                "2.2" -> "Still capture"
                else -> BenchmarkMetricCatalog.info(id)?.short ?: id
            }
            val cmp = comparison?.metric(id)
            keyMetric(label, "median", metric.value, cmp?.baselineValue, "ms", cmp, withDelta, lowerIsBetter = true)
        }
        // Frame rate is H.1 (preview interval) turned upside down, because "29.8 fps" answers the question the
        // interval only implies. The verdict still belongs to H.1: a longer interval is a lower rate.
        val interval = run.metric("H.1")
        val fps = interval?.value?.takeIf { it > 0 }?.let { 1000.0 / it }
        val frameRate = if (fps == null) null else {
            val cmp = comparison?.metric("H.1")
            val baseFps = cmp?.baselineValue?.takeIf { it > 0 }?.let { 1000.0 / it }
            keyMetric("Frame rate", "", fps, baseFps, "fps", cmp, withDelta, lowerIsBetter = false)
        }
        return rows + listOfNotNull(frameRate)
    }

    private fun keyMetric(
        label: String,
        statLabel: String,
        value: Double?,
        base: Double?,
        unit: String,
        cmp: MetricComparison?,
        withDelta: Boolean,
        lowerIsBetter: Boolean
    ): KeyMetric? {
        if (value == null) return null
        // One shared scale per row: the larger of the two values sits at 80% of the bar, so the tick and the
        // fill are always both on screen and their order is readable. A zero pair would make the scale 0 and
        // the fraction NaN, so it falls back to an empty bar instead.
        val scale = (maxOf(value, base ?: value) / 0.8).takeIf { it > 0 } ?: 1.0
        val delta = if (!withDelta || base == null) null else {
            val d = value - base
            val text = if (unit == "fps") String.format(Locale.US, "%+.1f", d) else String.format(Locale.US, "%+.0f", d)
            "$text $unit"
        }
        val tone = when {
            !withDelta || cmp == null -> Tone.NEUTRAL
            cmp.state == RegressionState.REGRESSED -> Tone.BAD
            cmp.state == RegressionState.IMPROVED -> Tone.GOOD
            else -> Tone.NEUTRAL
        }
        return KeyMetric(
            label = label,
            statLabel = statLabel,
            valueText = if (unit == "fps") String.format(Locale.US, "%.1f fps", value) else String.format(Locale.US, "%.0f ms", value),
            deltaText = delta,
            tone = tone,
            fraction = (value / scale).coerceIn(0.0, 1.0),
            baseFraction = base?.let { (it / scale).coerceIn(0.0, 1.0) }
        )
    }

    // ---- headline ----

    /**
     * The three eligibility steps of 5.3 in one line. The blocking flags are named because "비교 불가" alone does
     * not tell the developer whether to re-run in a cooler state or to fix the measurement.
     */
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
        val suffix = if (v.comparisonEligible) null else "Set as baseline disabled"
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
        ConditionMismatch.EXPOSURE_DIFFERS -> "노출 부하 4배 이상 차이 (3A 제외)"
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

    /** Cadence metrics need the tenth of a millisecond that separates 33.3 from 33.4; the rest do not. */
    fun format(metric: BenchmarkMetric, value: Double?): String {
        if (value == null) return "—"
        if (metric.unit == "count") return value.toInt().toString()
        if (metric.unit != "ms") return "$value ${metric.unit}"
        val text = if (metric.category == Category.PREVIEW) String.format(Locale.US, "%.1f", value)
        else String.format(Locale.US, "%.0f", value)
        return "$text ms"
    }

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
