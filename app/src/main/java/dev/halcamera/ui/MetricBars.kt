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
    degraded: Boolean,
    /** The reference is past the end of the track: its tick sits at the end with an arrow pointing on. */
    private val baseBeyond: Boolean = false
) : View(context) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Look.expertTile3 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (degraded) Look.statusFail else Look.primaryOnDark
    }
    // White and 2dp wide: the grey 3px tick nearly vanished on the dark track, in the legend most of all.
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Look.onDark }
    private val tickHalf = Look.dp(context, 1).toFloat()

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
            if (baseBeyond) {
                // A small arrow at the end of the track: the reference is further on than the track reaches.
                val h = height.toFloat()
                val path = android.graphics.Path().apply {
                    moveTo(w - h * 0.6f, 0f); lineTo(w, h / 2); lineTo(w - h * 0.6f, h); close()
                }
                canvas.drawPath(path, tick)
                return@let
            }
            val x = (w * it.coerceIn(0f, 1f)).coerceIn(tickHalf, w - tickHalf)
            canvas.drawRect(x - tickHalf, 0f, x + tickHalf, height.toFloat(), tick)
        }
    }
}
