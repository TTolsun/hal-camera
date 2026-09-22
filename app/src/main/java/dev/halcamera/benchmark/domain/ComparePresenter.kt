package dev.halcamera.benchmark.domain

/** One metric of the COMPARE table (docs/PLAN-BenchMarker-v0.3.md 7.3). */
data class CompareRow(
    val label: String,
    val base: String,
    val current: String,
    val delta: String,
    /** "▲ Degraded", "▼ Improved", the reason a metric could not be judged, or empty for STABLE. */
    val marker: String,
    /** Signed percentage for the delta chart; null when the pair has no percentage (counts, unit mismatch). */
    val deltaPct: Double? = null
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
    /** Header of the left value column: "Baseline" or "Previous". */
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
            titleLine = pad("Compare", ROLE + RUN_ID + SUBJECT) + "rule ${comparison.ruleVersion}",
            baseLine = runLine(if (againstBaseline) "baseline" else if (selectedReference) "selected" else "previous", base),
            currentLine = runLine("current", current),
            identityLine = comparison.identity?.let(ResultPresenter::identityLine),
            conditionLine = ResultPresenter.conditionLine(comparison),
            baseHeader = if (againstBaseline) "Baseline" else if (selectedReference) "Selected" else "Previous",
            referenceNote = when {
                againstBaseline -> null
                selectedReference -> "Deltas vs the selected run only · the baseline is not changed"
                // The baseline has nothing above it to be measured against, so it too falls back to the previous
                // run. Saying "no baseline" on the baseline's own screen contradicts the button beside it.
                currentIsBaseline -> "This run is the baseline · deltas vs the previous run only"
                else -> "No baseline · deltas vs the previous run only"
            },
            rows = rows(base, current, comparison, comparedTo)
        )
    }

    fun runLine(role: String, run: BenchmarkRun): String =
        pad(role, ROLE) + pad(run.runId, RUN_ID) +
            pad(run.subject.subjectBuildLabel?.takeIf { it.isNotBlank() } ?: "(no subject)", SUBJECT) +
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
            // History permits selecting incompatible contracts. Preserve each side
            // independently and never report a percentage between different units.
            val shape = c ?: b!!
            val metricComparison = comparison.metric(id)
            val unitMismatch = b != null && c != null && b.unit != c.unit
            CompareRow(
                label = BenchmarkMetricCatalog.info(id)?.short ?: id,
                base = ResultPresenter.format(b ?: shape, b?.value),
                current = ResultPresenter.format(shape, c?.value),
                delta = if (unitMismatch) "—" else ResultPresenter.delta(shape, metricComparison),
                marker = if (unitMismatch) "unit differs" else marker(metricComparison, comparedTo),
                deltaPct = if (unitMismatch) null else metricComparison?.deltaPct
            )
        }

    /**
     * A verdict only against a baseline. Against a reference the delta stands on its own, but the reason a
     * metric could not be judged is still worth saying: it explains why the delta itself is not trustworthy.
     */
    private fun marker(comparison: MetricComparison?, comparedTo: ComparedTo): String = when (comparison?.state) {
        RegressionState.REGRESSED -> if (comparedTo == ComparedTo.BASELINE) "▲ Degraded" else ""
        RegressionState.IMPROVED -> if (comparedTo == ComparedTo.BASELINE) "▼ Improved" else ""
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
        (pad("", LABEL) + right(baseHeader, VALUE) + right("Current", VALUE)).trimEnd()

    fun rowLine(row: CompareRow): String =
        (pad(row.label, LABEL) + right(row.base, VALUE) + right(row.current, VALUE) + right(row.delta, DELTA) +
            if (row.marker.isEmpty()) "" else "  ${row.marker}").trimEnd()

    private fun pad(s: String, width: Int) = if (s.length >= width) "$s " else s + " ".repeat(width - s.length)
    private fun right(s: String, width: Int) = if (s.length >= width) " $s" else " ".repeat(width - s.length) + s
}
