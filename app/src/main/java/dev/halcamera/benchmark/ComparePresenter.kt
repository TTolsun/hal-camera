package dev.halcamera.benchmark

/** One metric of the COMPARE table (docs/PLAN-BenchMarker-v0.3.md 7.3). */
data class CompareRow(
    val label: String,
    val base: String,
    val current: String,
    val delta: String,
    /** "▲ REGRESSED", "▼ IMPROVED", the reason a metric could not be judged, or empty for STABLE. */
    val marker: String
) {
    /** [marker] is a verdict only against a baseline; against a reference it can only carry a reason. */
    val hasVerdict: Boolean get() = marker.startsWith("▲") || marker.startsWith("▼")
}

data class CompareView(
    val titleLine: String,
    val baseLine: String,
    val currentLine: String,
    val identityLine: String?,
    val conditionLine: String?,
    /** Header of the left value column: "BASELINE" or "PREVIOUS". */
    val baseHeader: String,
    /** Said only for a reference comparison, where a delta carries no verdict (7.1). */
    val referenceNote: String?,
    val rows: List<CompareRow>
) {
    fun render(): String = buildString {
        appendLine(titleLine)
        appendLine(baseLine)
        appendLine(currentLine)
        identityLine?.let { appendLine(" ".repeat(ComparePresenter.ROLE) + it) }
        conditionLine?.let { appendLine(" ".repeat(ComparePresenter.ROLE) + it) }
        referenceNote?.let { appendLine(" ".repeat(ComparePresenter.ROLE) + it) }
        appendLine()
        appendLine(ComparePresenter.headerLine(baseHeader))
        rows.forEach { appendLine(ComparePresenter.rowLine(it)) }
    }
}

/**
 * The COMPARE screen of 7.3: two runs side by side. The comparison itself stays in
 * [RegressionDetector.compare]; this only lays the result out, so the screen can never reach a verdict the rule
 * table did not.
 *
 * Unlike the result table of 8.4 it names the state in words. COMPARE is opened to answer "what changed between
 * these two builds", and a bare triangle in a two-column table does not say which side got worse.
 *
 * [ComparedTo] has to reach this screen. A reference is only "the run measured before this one" and carries no
 * state at all (7.1), so labelling it `baseline` and hanging REGRESSED off it would report a regression against
 * a configuration nobody chose as the standard.
 */
object ComparePresenter {

    fun present(
        base: BenchmarkRun,
        current: BenchmarkRun,
        comparison: RunComparison,
        comparedTo: ComparedTo,
        /** Whether [current] is itself the baseline, which is a different thing from there being none. */
        currentIsBaseline: Boolean = false,
        selectedReference: Boolean = false
    ): CompareView {
        val againstBaseline = comparedTo == ComparedTo.BASELINE
        return CompareView(
            titleLine = pad("COMPARE", ROLE + RUN_ID + SUBJECT) + "rule ${comparison.ruleVersion}",
            baseLine = runLine(if (againstBaseline) "baseline" else if (selectedReference) "selected" else "previous", base),
            currentLine = runLine("current", current),
            identityLine = comparison.identity?.let(ResultPresenter::identityLine),
            conditionLine = ResultPresenter.conditionLine(comparison),
            baseHeader = if (againstBaseline) "BASELINE" else if (selectedReference) "SELECTED" else "PREVIOUS",
            referenceNote = when {
                againstBaseline -> null
                selectedReference -> "선택한 run 대비 delta만 표시합니다 · baseline은 변경하지 않습니다"
                // The baseline has nothing above it to be measured against, so it too falls back to the previous
                // run. Saying "baseline 없음" on the baseline's own screen contradicts the button beside it.
                currentIsBaseline -> "이 run이 baseline입니다 · 이전 run 대비 delta만 표시합니다"
                else -> "baseline 없음 · 이전 run 대비 delta만 표시합니다"
            },
            rows = rows(base, current, comparison, comparedTo)
        )
    }

    fun runLine(role: String, run: BenchmarkRun): String =
        pad(role, ROLE) + pad(run.runId, RUN_ID) +
            pad(run.subject.subjectBuildLabel?.takeIf { it.isNotBlank() } ?: "(subject 없음)", SUBJECT) +
            pad(run.device.buildDisplay, BUILD) +
            "thermal max ${run.env.thermalMax ?: "—"}"

    /**
     * Catalog order, and only metrics one of the two runs actually measured. A row of two dashes would say
     * nothing that the run's own validity flags do not already say.
     */
    fun rows(
        base: BenchmarkRun,
        current: BenchmarkRun,
        comparison: RunComparison,
        comparedTo: ComparedTo
    ): List<CompareRow> =
        BenchmarkMetricCatalog.ids.mapNotNull { id ->
            val b = base.metric(id)
            val c = current.metric(id)
            if (b?.value == null && c?.value == null) return@mapNotNull null
            // Unit and category come from whichever side is present; both sides share the metric definition
            // version, so formatting one against the other cannot disagree.
            val shape = c ?: b!!
            val metricComparison = comparison.metric(id)
            CompareRow(
                label = BenchmarkMetricCatalog.info(id)?.short ?: id,
                base = ResultPresenter.format(shape, b?.value),
                current = ResultPresenter.format(shape, c?.value),
                delta = ResultPresenter.delta(shape, metricComparison),
                marker = marker(metricComparison, comparedTo)
            )
        }

    /**
     * A verdict only against a baseline. Against a reference the delta stands on its own, but the reason a
     * metric could not be judged is still worth saying: it explains why the delta itself is not trustworthy.
     */
    private fun marker(comparison: MetricComparison?, comparedTo: ComparedTo): String = when (comparison?.state) {
        RegressionState.REGRESSED -> if (comparedTo == ComparedTo.BASELINE) "▲ REGRESSED" else ""
        RegressionState.IMPROVED -> if (comparedTo == ComparedTo.BASELINE) "▼ IMPROVED" else ""
        RegressionState.UNKNOWN -> ResultPresenter.noteFor(comparison, comparedTo)
        else -> ""
    }

    // ---- monospace layout ----

    internal const val ROLE = 10
    private const val RUN_ID = 22
    private const val SUBJECT = 16
    private const val BUILD = 20

    /**
     * The metric rows are sized like the result table so both fit the card without a horizontal scroll: LABEL
     * holds the longest catalog name plus a separator, and each value column holds "1234.5 ms".
     *
     * The three identity lines above them stay wide and still scroll. A run id is 22 characters and cannot be
     * shortened without making it useless for finding the file, so that is a cost worth paying on three lines
     * rather than on every row of the table.
     */
    private const val LABEL = 15
    private const val VALUE = 10
    private const val DELTA = 8

    fun headerLine(baseHeader: String): String =
        (pad("", LABEL) + right(baseHeader, VALUE) + right("CURRENT", VALUE)).trimEnd()

    fun rowLine(row: CompareRow): String =
        (pad(row.label, LABEL) + right(row.base, VALUE) + right(row.current, VALUE) + right(row.delta, DELTA) +
            if (row.marker.isEmpty()) "" else "  ${row.marker}").trimEnd()

    private fun pad(s: String, width: Int) = if (s.length >= width) "$s " else s + " ".repeat(width - s.length)
    private fun right(s: String, width: Int) = if (s.length >= width) " $s" else " ".repeat(width - s.length) + s
}
