package dev.halcamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * The horizontal value bar under a key metric on the result screen. The fill is this run, the tick is the
 * baseline on the same scale, so whether the run moved past its baseline is visible without reading a number.
 * That scale is shared by every row of the same unit in the section, so the fills also rank the rows against
 * each other; ResultPresenter.metricBars computes both fractions.
 */
internal class MeterView(
    context: Context,
    private val fraction: Float,
    private val baseFraction: Float?,
    degraded: Boolean
) : View(context) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Look.expertTile3 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (degraded) Look.statusFail else Look.primaryOnDark
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Look.onDarkMuted }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Look.dp(context, 12))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val barTop = height * 0.25f
        val barBottom = height * 0.75f
        val r = (barBottom - barTop) / 2
        canvas.drawRoundRect(0f, barTop, w, barBottom, r, r, track)
        canvas.drawRoundRect(0f, barTop, w * fraction.coerceIn(0f, 1f), barBottom, r, r, fill)
        baseFraction?.let {
            val x = (w * it.coerceIn(0f, 1f)).coerceIn(1.5f, w - 1.5f)
            canvas.drawRect(x - 1.5f, 0f, x + 1.5f, height.toFloat(), tick)
        }
    }
}
