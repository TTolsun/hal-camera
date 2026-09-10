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
            fun dot(ms: Double, text: String) {
                canvas.drawCircle(x(ms), y, 3.5f * d, paint)
                paint.textAlign = Paint.Align.CENTER; paint.textSize = 9 * d
                canvas.drawText(text, x(ms), y + 14 * d, paint)
                paint.textAlign = Paint.Align.LEFT
            }
            dot(0.0, "0")
            partial?.let { dot(it, String.format(Locale.US, "%.1f", it)) }
            buffer?.let { dot(it, String.format(Locale.US, "%.1f", it)) }
        }
        val worse = nowPartial != null && typicalPartial != null && nowPartial!! > typicalPartial!! * 1.3 && nowPartial!! - typicalPartial!! > 10.0
        row(height * 0.32f, frame?.let { "#$it" } ?: "Now", nowPartial, nowBuffer, ink, worse)
        row(height * 0.72f, "Typical", typicalPartial, typicalBuffer, muted, false)
        paint.color = muted; paint.textSize = 9 * d; paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("START · PARTIAL · BUFFER (ms)", right, 10 * d, paint)
        paint.textAlign = Paint.Align.LEFT
    }
}
