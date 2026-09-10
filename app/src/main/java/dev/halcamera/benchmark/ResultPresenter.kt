package dev.halcamera.benchmark

import dev.halcamera.diagnosis.UnknownReason
import dev.halcamera.diagnosis.jsonName
import java.util.Locale

/** Which run the delta column is measured against (docs/PLAN-BenchMarker-v0.3.md 7.1, 8.4). */
enum class ComparedTo { BASELINE, PREVIOUS, NONE }

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
    val note: String = ""
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
    val baselineButtonEnabled: Boolean
) {
    fun render(): String = buildString {
        appendLine("CAMERA BENCHMARK")
        appendLine(titleLine)
        appendLine(subLine)
        appendLine(eligibilityLine)
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
            comparisonLine = comparisonLine(run, comparison, comparedTo),
            identityLine = comparison?.identity?.let(::identityLine),
            conditionLine = comparison?.let(::conditionLine),
            // The hint names a button that is no longer on the screen once this run is itself the baseline: the
            // button reads CLEAR BASELINE by then, so telling the reader to press SET AS BASELINE describes
            // nothing they can do. Being compared against a baseline and being one are separate states.
            hint = if (comparedTo == ComparedTo.BASELINE || isBaseline) null
            else "[ SET AS BASELINE ]을 누르면 이 run이 기준이 됩니다",
            sections = sections,
            threeALine = threeALine(run),
            baselineButton = if (isBaseline) "CLEAR BASELINE" else "SET AS BASELINE",
            // Clearing must stay possible even if the run later became ineligible under a changed flag table.
            baselineButtonEnabled = isBaseline || run.validity.comparisonEligible
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
            !v.measurementValid -> "측정 무효 (${codes { it.blocksMeasurement }})"
            !v.comparisonEligible -> "비교 불가 (${codes { it.blocksComparison }})"
            !v.scoringEligible -> "비교 가능 · 점수 제외 (${codes { it.blocksScoring }})"
            else -> "비교 가능 · 점수 가능"
        }
        val thermal = listOf(run.env.thermalStart, run.env.thermalMax, run.env.thermalEnd)
        val thermalText = if (thermal.any { it == null }) null else thermal.joinToString(" → ")
        val suffix = if (v.comparisonEligible) null else "SET AS BASELINE 비활성"
        return listOfNotNull(head, thermalText?.let { "thermal $it" }, suffix).joinToString(" · ")
    }

    fun comparisonLine(run: BenchmarkRun, comparison: RunComparison?, comparedTo: ComparedTo): String = when {
        comparison == null || comparedTo == ComparedTo.NONE -> "baseline 없음 · 비교할 이전 run이 없습니다"
        // No metric could be judged: saying "REGRESSED 없음" here would read as a clean result rather than as a
        // comparison that never happened (PR #21 review).
        comparison.judgedCount == 0 && comparedTo == ComparedTo.BASELINE ->
            "판정 불가   baseline ${comparison.baseRunId} · 비교 조건을 만족하는 지표가 없습니다"
        comparedTo == ComparedTo.PREVIOUS -> "baseline 없음 · 이전 run ${comparison.baseRunId} 대비 표시"
        comparison.hasRegression -> "▲ ${comparison.regressedCount} REGRESSED   baseline ${comparison.baseRunId}"
        else -> "REGRESSED 없음   baseline ${comparison.baseRunId}"
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
        "Camera build " + (id.sameCameraBuild?.let(::same) ?: "알 수 없음"),
        "앱 " + same(id.sameAppVersion),
        id.sameAppBuild?.let { "앱 빌드 " + same(it) },
        listOfNotNull(id.sameSubjectLabel, id.sameSubjectCommit).let { axes ->
            if (axes.isEmpty()) null else "subject " + same(axes.all { it })
        }
    ).joinToString(" · ")

    private fun same(v: Boolean) = if (v) "동일" else "다름"

    // ---- table ----

    private fun section(category: Category, run: BenchmarkRun, comparison: RunComparison?, comparedTo: ComparedTo): ResultSection? {
        // The catalog is declared in display order, so iterating its ids is the row order of 8.4.
        val metrics = BenchmarkMetricCatalog.ids
            .mapNotNull { run.metric(it) }
            .filter { it.category == category }
        if (metrics.isEmpty()) return null
        val rows = metrics.map { row(it, comparison?.metric(it.id), comparedTo) }
        return ResultSection(
            title = category.name.replace('_', ' '),
            valueHeader = "p50",
            statHeader = metrics.mapNotNull { statHeader(it) }.firstOrNull().orEmpty(),
            deltaHeader = when (comparedTo) {
                ComparedTo.BASELINE -> "vs baseline"
                ComparedTo.PREVIOUS -> "vs previous"
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
            note = noteFor(comparison, comparedTo)
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
        val text = if (metric.category == Category.PREVIEW) String.format(Locale.US, "%.1f", value)
        else String.format(Locale.US, "%.0f", value)
        return "$text ms"
    }

    // ---- monospace layout ----

    /** LABEL fits the longest catalog name ("Stall during capture") plus its indent; DELTA fits "vs baseline". */
    private const val LABEL = 24
    private const val VALUE = 9
    private const val STAT = 9
    private const val DELTA = 13

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
