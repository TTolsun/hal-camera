package dev.halcamera.ui

import android.content.Context
import android.widget.LinearLayout
import dev.halcamera.benchmark.domain.BenchmarkRun
import dev.halcamera.benchmark.domain.ComparedTo
import dev.halcamera.benchmark.domain.ResultPresenter
import dev.halcamera.benchmark.domain.ResultView
import dev.halcamera.benchmark.domain.RunComparison
import dev.halcamera.benchmark.domain.Tone
import dev.halcamera.camera.CameraLabel

/**
 * The cards of BENCHMARK's result screen (8.4), which is also the only way two runs are compared.
 *
 * There used to be a second chart for that, a percentage bar around a zero line behind a 비교 button, and run
 * history drew its comparison as text rows. Three pictures of one comparison was two too many; the bars with a
 * reference tick stayed, and this card now carries everything the other two showed (percentages, rows only the
 * reference measured, why a row was not judged, and the reference run's facts).
 *
 * Every number and sentence comes from [ResultPresenter]; this file only lays them out. The buttons under the
 * cards and what they do stay in BenchmarkActivity and HistoryActivity. The cards were split out so
 * BenchmarkActivity stays inside the 60,000-character input the docs scan can read.
 */
object BenchmarkResultCards {
    /**
     * Adds the headline card and the metrics card to [content] and returns the presented view, whose baseline button
     * label and state the activity needs for its actions. The verdict leads, every metric follows as a bar grouped
     * by category, and the run facts fold (mockup v7).
     *
     * [reference] is the run the ticks stand for, and [selectedReference] says it was picked in run history rather
     * than found as the baseline or the previous run.
     */
    fun addResult(
        context: Context,
        content: LinearLayout,
        run: BenchmarkRun,
        comparison: RunComparison?,
        comparedTo: ComparedTo,
        isBaseline: Boolean,
        fileName: String?,
        reference: BenchmarkRun? = null,
        selectedReference: Boolean = false,
    ): ResultView {
        val dp = { v: Int -> Look.dp(context, v) }
        val lp = { top: Int -> LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) } }
        val deviceName = "${run.device.manufacturer} ${run.device.model}"
        val endpointName = CameraLabel.full(run.endpoint)
        val view = ResultPresenter.present(run, comparison, comparedTo, isBaseline, deviceName, endpointName)

        // Headline card: the verdict, what it was measured against, and the score.
        val head = ResultPresenter.headline(run, comparison, comparedTo, isBaseline, endpointName, selectedReference)
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
        val sections = ResultPresenter.metricBars(run, comparison, comparedTo, reference)
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
                    top.addView(Look.text(context, it, 12, deltaColor(k.tone), bold = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                }
                metricsCard.addView(top, lp(if (i == 0) 8 else 14))
                metricsCard.addView(MeterView(context, k.fraction.toFloat(), k.baseFraction?.toFloat(), k.tone == Tone.BAD), lp(6))
                // Why this row has no verdict or no fill; the removed compare chart said it beside the row.
                k.note?.let { metricsCard.addView(Look.text(context, it, 11, Look.onDarkMuted), lp(4)) }
            }
        }
        if (anyBaseline) {
            val tickName = when {
                comparedTo == ComparedTo.BASELINE -> "baseline"
                selectedReference -> "선택한 run"
                else -> "이전 run"
            }
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
        // The reference run's facts follow this run's, so the fold answers "compared with what" as well; the compare
        // screen that used to hold them is gone.
        val role = when {
            comparedTo == ComparedTo.BASELINE -> "baseline"
            selectedReference -> "기준"
            else -> "이전 run"
        }
        val referenceFacts = reference?.takeIf { comparedTo != ComparedTo.NONE }
            ?.let { ResultPresenter.referenceFacts(it, comparison, role) }.orEmpty()
        (ResultPresenter.runFacts(run, deviceName, endpointName, fileName) + referenceFacts).forEachIndexed { i, (label, fact) ->
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
     * One colour per verdict for every delta on the card: red degraded, green improved, grey not judged. Improved
     * used to be blue, which on this app's dark screens is the colour of things that can be pressed.
     */
    private fun deltaColor(tone: Tone): Int = when (tone) {
        Tone.BAD -> Look.statusFail
        Tone.GOOD -> Look.statusPass
        Tone.NEUTRAL -> Look.onDarkMuted
    }
}
