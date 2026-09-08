package dev.cameradoctor.ui

import android.content.Context
import android.graphics.*
import android.view.View
import dev.cameradoctor.telemetry.Event

/** Ten-second sparkline of sensor frame intervals with the session baseline and the 1.5x threshold drawn as guides. */
class StripView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val amber = Color.rgb(255, 199, 109)
    private val coral = Color.rgb(255, 128, 126)
    private val guide = Color.argb(140, 153, 174, 192)
    private var points = emptyList<Pair<Long, Double>>()
    private var tRef: Double? = null
    private var now = 0L
    private val window = 10_000_000_000L

    fun update(frames: List<Event>, tRefMs: Double?, time: Long) {
        points = frames.mapNotNull { e -> (e.values["intervalMs"] as? Number)?.toDouble()?.let { e.atNs to it } }
        tRef = tRefMs; now = time
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val w = width.toFloat(); val h = height.toFloat()
        val pad = 4 * d
        val ref = tRef
        val maxV = maxOf(points.maxOfOrNull { it.second } ?: 0.0, (ref ?: 33.0).coerceAtLeast(1.0) * 2.0)
        fun x(t: Long) = pad + (w - 2 * pad) * ((t - (now - window)).toFloat() / window)
        fun y(v: Double) = (h - pad - (h - 2 * pad) * (v / maxV)).toFloat()
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1 * d; paint.color = guide
        if (ref != null) {
            canvas.drawLine(pad, y(ref), w - pad, y(ref), paint)
            paint.pathEffect = DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f)
            canvas.drawLine(pad, y(ref * 1.5), w - pad, y(ref * 1.5), paint)
            paint.pathEffect = null
        }
        if (points.size < 2) return
        paint.color = amber; paint.strokeWidth = 1.5f * d
        val path = Path()
        points.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v)) }
        canvas.drawPath(path, paint)
        if (ref != null) {
            paint.style = Paint.Style.FILL; paint.color = coral
            for ((t, v) in points) if (v > ref * 1.5) canvas.drawCircle(x(t), y(v), 3 * d, paint)
        }
    }
}
