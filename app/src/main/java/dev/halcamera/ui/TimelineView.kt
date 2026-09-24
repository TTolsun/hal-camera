package dev.halcamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import java.util.Locale

/**
 * Frame callback timeline as two dot-lines (docs/archive/PRODUCT-v0.2.md 12.2 item 3):
 * the latest frame (START, PARTIAL, BUFFER offsets) above the session-typical p50 offsets,
 * so where the delay sits is visible without reading numbers.
 */
class TimelineView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Color.WHITE
    private val muted = Color.rgb(204, 204, 204)
    private val late = Color.rgb(255, 149, 0)
    private var nowPartial: Double? = null
    private var nowBuffer: Double? = null
    private var typicalPartial: Double? = null
    private var typicalBuffer: Double? = null
    private var frame: Long? = null

    fun update(frame: Long?, partialMs: Double?, bufferMs: Double?, typicalPartialMs: Double?, typicalBufferMs: Double?) {
        this.frame = frame; nowPartial = partialMs; nowBuffer = bufferMs; typicalPartial = typicalPartialMs; typicalBuffer = typicalBufferMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val left = 64 * d; val right = width - 16 * d
        val max = listOfNotNull(nowPartial, nowBuffer, typicalPartial, typicalBuffer, 40.0).max() * 1.15
        fun x(ms: Double) = left + (right - left) * (ms / max).toFloat().coerceIn(0f, 1f)
        fun row(y: Float, label: String, partial: Double?, buffer: Double?, color: Int, worse: Boolean) {
            paint.style = Paint.Style.FILL; paint.textSize = 10 * d; paint.color = muted; paint.textAlign = Paint.Align.LEFT
            canvas.drawText(label, 12 * d, y + 4 * d, paint)
            val end = buffer ?: partial ?: return
            paint.color = if (worse) late else color; paint.strokeWidth = 1.5f * d
            canvas.drawLine(x(0.0), y, x(end), y, paint)
            val stops = listOfNotNull(
                0.0 to "0",
                partial?.let { it to String.format(Locale.US, "%.1f", it) },
                buffer?.let { it to String.format(Locale.US, "%.1f", it) }
            ).sortedBy { it.first }.map { x(it.first) to it.second }
            stops.forEach { canvas.drawCircle(it.first, y, 3.5f * d, paint) }
            drawStopLabels(canvas, stops, y)
        }
        val worse = nowPartial != null && typicalPartial != null && nowPartial!! > typicalPartial!! * 1.3 && nowPartial!! - typicalPartial!! > 10.0
        row(height * 0.32f, frame?.let { "#$it" } ?: "Now", nowPartial, nowBuffer, ink, worse)
        row(height * 0.72f, "Typical", typicalPartial, typicalBuffer, muted, false)
        paint.color = muted; paint.textSize = 9 * d; paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("START · PARTIAL · BUFFER (ms)", right, 10 * d, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    /**
     * Value labels under a row's dots. PARTIAL and BUFFER routinely land within a few milliseconds of each
     * other, and two labels centred on their own dots then overlap into one unreadable run of digits
     * ("62.7" over "67.5" reads as "62.767.5"). Neighbours whose boxes would touch are merged into a single
     * "62.7 / 67.5" drawn between them, so the numbers stay legible without growing the row.
     */
    private fun drawStopLabels(canvas: Canvas, stops: List<Pair<Float, String>>, y: Float) {
        if (stops.isEmpty()) return
        val d = resources.displayMetrics.density
        paint.textSize = 9 * d
        paint.textAlign = Paint.Align.CENTER
        val gap = 4 * d
        var index = 0
        while (index < stops.size) {
            var last = index
            var right = stops[index].first + paint.measureText(stops[index].second) / 2
            while (last + 1 < stops.size) {
                val (nextX, nextText) = stops[last + 1]
                val half = paint.measureText(nextText) / 2
                if (nextX - half >= right + gap) break
                right = maxOf(right, nextX + half)
                last++
            }
            val text = (index..last).joinToString(" / ") { stops[it].second }
            val half = paint.measureText(text) / 2
            val centre = (stops[index].first + stops[last].first) / 2
            canvas.drawText(text, centre.coerceIn(half, width - half), y + 14 * d, paint)
            index = last + 1
        }
        paint.textAlign = Paint.Align.LEFT
    }
}
