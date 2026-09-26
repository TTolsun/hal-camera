package dev.halcamera.ui

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import dev.halcamera.benchmark.domain.BenchmarkRun
import dev.halcamera.benchmark.domain.ComparePresenter
import dev.halcamera.benchmark.domain.ComparedTo
import dev.halcamera.benchmark.domain.ResultPresenter
import dev.halcamera.benchmark.domain.ResultView
import dev.halcamera.benchmark.domain.RunComparison
import dev.halcamera.benchmark.domain.Tone
import dev.halcamera.camera.CameraLabel

/**
 * The cards of BENCHMARK's result (8.4) and compare (7.3) screens.
 *
 * Every number and sentence comes from [ResultPresenter] and [ComparePresenter]; this file only lays them out. The
 * buttons under the cards and what they do stay in BenchmarkActivity, which owns the run, the baseline and the export.
 * The cards were split out so BenchmarkActivity stays inside the 60,000-character input the docs scan can read.
 */
object BenchmarkResultCards {
    /**
     * Adds the headline card and the metrics card to [content] and returns the presented view, whose baseline button
     * label and state the activity needs for its actions. The verdict leads, every metric follows as a bar grouped
     * by category, and the run facts fold (mockup v7).
     */
    fun addResult(
        context: Context,
        content: LinearLayout,
        run: BenchmarkRun,
        comparison: RunComparison?,
        comparedTo: ComparedTo,
        isBaseline: Boolean,
        fileName: String?,
    ): ResultView {
        val dp = { v: Int -> Look.dp(context, v) }
        val lp = { top: Int -> LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) } }
        val deviceName = "${run.device.manufacturer} ${run.device.model}"
        val endpointName = CameraLabel.full(run.endpoint)
        val view = ResultPresenter.present(run, comparison, comparedTo, isBaseline, deviceName, endpointName)

        // Headline card: the verdict, what it was measured against, and the score.
        val head = ResultPresenter.headline(run, comparison, comparedTo, isBaseline, endpointName)
        val card = Look.card(context, dark = true)
        val headColor = when (head.tone) {
            Tone.BAD -> Look.statusFail
            Tone.GOOD -> Look.statusPass
            Tone.NEUTRAL -> Look.onDark
        }
        card.addView(Look.text(context, head.text, 21, headColor, bold = true))
        card.addView(Look.text(context, head.sub, 13, Look.onDarkMuted), lp(6))
        view.conditionLine?.let { card.addView(Look.text(context, it, 13, Look.statusWarn), lp(8)) }
        ResultPresenter.scoreValue(run)?.let { total ->
            val scoreRow = Look.row(context)
            scoreRow.addView(Look.text(context, total.toString(), 30, Look.onDark, bold = true, mono = true))
            scoreRow.addView(Look.text(context, "/ 1000 · Camera Endpoint Score · internal draft", 12, Look.onDarkMuted),
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            card.addView(scoreRow, lp(12))
        }
        content.addView(card)

        // Metrics card: every metric is a bar, grouped by category. The table behind an "All metrics" fold is gone:
        // a number next to its baseline is what this screen is for, and a bar answers that faster than a row of digits.
        val metricsCard = Look.card(context, dark = true)
        val sections = ResultPresenter.metricBars(run, comparison, comparedTo)
        var anyBaseline = false
        sections.forEachIndexed { sectionIndex, section ->
            metricsCard.addView(Look.text(context, section.title, 13, Look.onDarkMuted, bold = true), lp(if (sectionIndex == 0) 0 else 20))
            section.bars.forEachIndexed { i, k ->
                if (k.baseFraction != null) anyBaseline = true
                val top = Look.row(context)
                val label = if (k.statLabel.isBlank()) k.label else "${k.label} · ${k.statLabel}"
                top.addView(Look.text(context, label, 13, Look.onDark), LinearLayout.LayoutParams(0, -2, 1f))
                top.addView(Look.text(context, k.valueText, 15, if (k.tone == Tone.BAD) Look.statusFail else Look.onDark, bold = true, mono = true))
                k.deltaText?.let {
                    val deltaColor = when (k.tone) {
                        Tone.BAD -> Look.statusFail
                        Tone.GOOD -> Look.primaryOnDark
                        Tone.NEUTRAL -> Look.onDarkMuted
                    }
                    top.addView(Look.text(context, it, 12, deltaColor, bold = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                }
                metricsCard.addView(top, lp(if (i == 0) 8 else 14))
                metricsCard.addView(MeterView(context, k.fraction.toFloat(), k.baseFraction?.toFloat(), k.tone == Tone.BAD), lp(6))
            }
        }
        if (anyBaseline) {
            val tickName = if (comparedTo == ComparedTo.BASELINE) "baseline" else "이전 run"
            metricsCard.addView(Look.text(context, "막대 = 이번 run · 눈금 = $tickName", 11, Look.onDarkMuted), lp(12))
        }
        // How far a length may be compared. Without it the reader has to guess the rule from the bars, and the guess
        // a column of bars invites — every row on one axis — is not the rule a mixed-unit section follows.
        ResultPresenter.barScaleNote(sections)?.let {
            metricsCard.addView(Look.text(context, it, 11, Look.onDarkMuted), lp(if (anyBaseline) 4 else 12))
        }
        // Label and value pairs, not seven sentences that repeated each other: the eligibility line named the same
        // flags the summary printed again as codes, and neither said what a code meant.
        val details = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        ResultPresenter.runFacts(run, deviceName, endpointName, fileName).forEachIndexed { i, (label, fact) ->
            val factRow = Look.row(context)
            factRow.addView(Look.text(context, label, 12, Look.onDarkMuted), LinearLayout.LayoutParams(dp(76), -2))
            factRow.addView(Look.text(context, fact, 12, Look.onDark), LinearLayout.LayoutParams(0, -2, 1f))
            details.addView(factRow, lp(if (i == 0) 4 else 8))
        }
        ResultPresenter.scoreValue(run)?.let {
            details.addView(Look.text(context, "점수는 같은 기기·카메라의 변화를 보기 위한 내부 초안입니다. 기기 간 순위가 아닙니다.", 11, Look.onDarkMuted), lp(12))
        }
        metricsCard.addView(Look.disclosure(context, "실행 정보", details), lp(4))
        content.addView(metricsCard, lp(10))
        return view
    }

    /**
     * Adds the compare card: a delta chart around a zero line first, and rows a chart cannot carry as text below it.
     * Run history's 두 실행 비교 uses the same card with [selectedReference], so comparing two runs looks the same
     * wherever it is opened; it used to be a column of text rows there.
     */
    fun addCompare(
        context: Context,
        content: LinearLayout,
        run: BenchmarkRun?,
        base: BenchmarkRun?,
        comparison: RunComparison?,
        comparedTo: ComparedTo,
        isBaseline: Boolean,
        selectedReference: Boolean = false,
    ) {
        val dp = { v: Int -> Look.dp(context, v) }
        val lp = { top: Int -> LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) } }
        val card = Look.card(context, dark = true)
        if (run == null || base == null || comparison == null) {
            card.addView(Look.text(context, "비교할 run이 없습니다.", 13, Look.onDarkMuted), lp(2))
            content.addView(card)
            return
        }
        val view = ComparePresenter.present(base, run, comparison, comparedTo, isBaseline, selectedReference)
        card.addView(Look.text(context, "Delta vs ${view.baseHeader.lowercase()}", 19, Look.onDark, bold = true))
        view.referenceNote?.let { card.addView(Look.text(context, it, 13, Look.onDarkMuted), lp(6)) }
        val regressed = view.rows.filter { it.marker.startsWith("▲") }
        if (regressed.isNotEmpty()) {
            card.addView(Look.text(context, "▲ ${regressed.size} degraded", 17, Look.statusFail, bold = true), lp(10))
        }
        view.conditionLine?.let { card.addView(Look.text(context, it, 13, Look.statusWarn), lp(8)) }

        // The chart: one shared percentage scale, degraded grows right, improved grows left.
        val charted = view.rows.filter { it.deltaPct != null }
        if (charted.isNotEmpty()) {
            card.addView(Look.text(context, "◀ Improved · Degraded ▶", 11, Look.onDarkMuted), lp(12))
            // An informational outlier (a 3A metric can move by thousands of percent) must not flatten every judged
            // bar, so the shared scale caps at 100% and larger deltas saturate.
            val maxPct = charted.maxOf { kotlin.math.abs(it.deltaPct!!) }.coerceIn(1.0, 100.0)
            charted.forEach { row ->
                val line = Look.row(context)
                val degraded = row.marker.startsWith("▲")
                val valueColor = when {
                    degraded -> Look.statusFail
                    row.marker.startsWith("▼") -> Look.primaryOnDark
                    else -> Look.onDarkMuted
                }
                line.addView(Look.text(context, row.label, 12, Look.onDarkMuted), LinearLayout.LayoutParams(dp(96), -2))
                line.addView(DeltaBarView(context, row.deltaPct!!.toFloat(), maxPct.toFloat(), degraded),
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(8) })
                line.addView(Look.text(context, row.delta, 12, valueColor, bold = true, mono = true).apply {
                    gravity = Gravity.END
                }, LinearLayout.LayoutParams(dp(56), -2))
                card.addView(line, lp(8))
            }
        }

        // Rows without a percentage (counts, unit changes, unknowns) keep their textual form.
        view.rows.filter { it.deltaPct == null }.forEach { card.addView(MetricRows.comparison(context, it, view.baseHeader)) }
        val details = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        listOfNotNull(view.baseLine, view.currentLine, view.identityLine).forEach {
            details.addView(Look.text(context, it.trim().replace(Regex(" {2,}"), " · "), 12, Look.onDarkMuted), lp(8))
        }
        card.addView(Look.disclosure(context, "실행 정보", details), lp(10))
        content.addView(card)
    }
}
