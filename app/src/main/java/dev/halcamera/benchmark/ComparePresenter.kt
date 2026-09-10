package dev.halcamera.benchmark

/** One metric of the COMPARE table (docs/PLAN-BenchMarker-v0.3.md 7.3). */
data class CompareRow(
    val label: String,
    val base: String,
    val current: String,
    val delta: String,
    /** "▲ REGRESSED", "▼ IMPROVED", the reason a metric could not be judged, or empty for STABLE. */
    val marker: String
)

data class CompareView(
    val titleLine: String,
    val baseLine: String,
    val currentLine: String,
    val identityLine: String?,
    val conditionLine: String?,
    val rows: List<CompareRow>
) {
    fun render(): String = buildString {
        appendLine(titleLine)
        appendLine(baseLine)
        appendLine(currentLine)
        identityLine?.let { appendLine(" ".repeat(ComparePresenter.ROLE) + it) }
        conditionLine?.let { appendLine(" ".repeat(ComparePresenter.ROLE) + it) }
        appendLine()
        appendLine(ComparePresenter.headerLine())
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
 */
object ComparePresenter {

    fun present(
        base: BenchmarkRun,
        current: BenchmarkRun,
        comparison: RunComparison
    ): CompareView = CompareView(
        titleLine = pad("COMPARE", ROLE + RUN_ID + SUBJECT) + "rule ${comparison.ruleVersion}",
        baseLine = runLine("baseline", base),
        currentLine = runLine("current", current),
        identityLine = comparison.identity?.let(ResultPresenter::identityLine),
        conditionLine = ResultPresenter.conditionLine(comparison),
        rows = rows(base, current, comparison)
    )

    fun runLine(role: String, run: BenchmarkRun): String =
        pad(role, ROLE) + pad(run.runId, RUN_ID) +
            pad(run.subject.subjectBuildLabel?.takeIf { it.isNotBlank() } ?: "(subject 없음)", SUBJECT) +
            pad(run.device.buildDisplay, BUILD) +
            "thermal max ${run.env.thermalMax ?: "—"}"

    /**
     * Catalog order, and only metrics one of the two runs actually measured. A row of two dashes would say
     * nothing that the run's own validity flags do not already say.
     */
    fun rows(base: BenchmarkRun, current: BenchmarkRun, comparison: RunComparison): List<CompareRow> =
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
                marker = marker(metricComparison)
            )
        }

    private fun marker(comparison: MetricComparison?): String = when (comparison?.state) {
        RegressionState.REGRESSED -> "▲ REGRESSED"
        RegressionState.IMPROVED -> "▼ IMPROVED"
        RegressionState.UNKNOWN -> ResultPresenter.noteFor(comparison, ComparedTo.BASELINE)
        else -> ""
    }

    // ---- monospace layout ----

    internal const val ROLE = 10
    private const val RUN_ID = 22
    private const val SUBJECT = 16
    private const val BUILD = 20
    private const val LABEL = 20
    private const val VALUE = 11
    private const val DELTA = 8

    fun headerLine(): String = (pad("", LABEL) + right("BASELINE", VALUE) + right("CURRENT", VALUE)).trimEnd()

    fun rowLine(row: CompareRow): String =
        (pad(row.label, LABEL) + right(row.base, VALUE) + right(row.current, VALUE) + right(row.delta, DELTA) +
            if (row.marker.isEmpty()) "" else "  ${row.marker}").trimEnd()

    private fun pad(s: String, width: Int) = if (s.length >= width) "$s " else s + " ".repeat(width - s.length)
    private fun right(s: String, width: Int) = if (s.length >= width) " $s" else " ".repeat(width - s.length) + s
}
