package dev.halcamera.ui

import android.content.Context
import android.widget.LinearLayout
import dev.halcamera.benchmark.domain.CompareRow
import dev.halcamera.benchmark.domain.ResultRow

/** Wrapping metric rows keep verdicts visible at phone widths and large font scales. */
internal object MetricRows {
    fun result(context: Context, row: ResultRow): LinearLayout = item(
        context,
        listOf(row.label, verdict(row.marker), row.note).filter { it.isNotBlank() }.joinToString(" · "),
        listOf(row.value, row.stat.takeIf { it.isNotBlank() }?.let { "${row.statLabel} $it" },
            row.delta.takeIf { it.isNotBlank() }?.let { "Δ $it" }).filterNotNull().joinToString(" · "),
        row.marker
    )

    fun comparison(context: Context, row: CompareRow, baseHeader: String): LinearLayout = item(
        context,
        listOf(row.label, row.marker).filter { it.isNotBlank() }.joinToString(" · "),
        "$baseHeader ${row.base} → Current ${row.current}" + if (row.delta.isBlank()) "" else "\nΔ ${row.delta}",
        row.marker
    )

    private fun verdict(marker: String) = when (marker) {
        "▲" -> "▲ Degraded"
        "▼" -> "▼ Improved"
        else -> marker
    }

    private fun item(context: Context, title: String, values: String, marker: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, Look.dp(context, 12), 0, Look.dp(context, 12))
        val color = when {
            marker.startsWith("▲") -> Look.statusFail
            marker.startsWith("▼") -> Look.statusPass
            else -> Look.onDark
        }
        addView(Look.text(context, title, 14, color, bold = true))
        addView(Look.text(context, values, 13, Look.onDark, mono = true).apply { setTextIsSelectable(true) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Look.dp(context, 4) })
    }
}
