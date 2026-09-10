package dev.halcamera.ui

import android.content.Context
import android.graphics.*
import android.view.View
import dev.halcamera.telemetry.Event
import dev.halcamera.telemetry.stateName
import java.util.Locale

class ScopeView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Color.rgb(36, 53, 66)
    private val mint = Color.rgb(41, 151, 255)   // primaryOnDark
    private val blue = Color.rgb(127, 191, 255)
    private val amber = Color.rgb(204, 204, 204)   // onDarkMuted; track colours stay off the status palette
    private val issue = Color.rgb(255, 59, 48)
    private val warn = Color.rgb(255, 149, 0)
    private var events = emptyList<Event>()
    private var now = 0L
    /** Vertical cursors drawn across every track: (time, label, isIncident). Incident triggers are red, anomalies orange. */
    private var markers = emptyList<Triple<Long, String, Boolean>>()
    fun update(frames: List<Event>, time: Long, markers: List<Triple<Long, String, Boolean>> = emptyList()) {
        events = frames; now = time; this.markers = markers; invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val left = 12*d; val right = width - 12*d
        val row = height / 6f
        val start = now - 10_000_000_000L
        // Incident / anomaly cursors first so the traces draw on top of them and correlation across tracks is visible.
        markers.filter { it.first >= start }.forEach { (time, label, incident) ->
            val x = left + (right-left)*((time-start)/1e10).toFloat().coerceIn(0f,1f)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f*d; paint.color = if (incident) issue else warn
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4*d, 3*d), 0f)
            canvas.drawLine(x, 4*d, x, height - 4*d, paint)
            paint.pathEffect = null; paint.style = Paint.Style.FILL; paint.textSize = 9*d
            paint.textAlign = if (x > width/2) Paint.Align.RIGHT else Paint.Align.LEFT
            canvas.drawText(label, x + (if (x > width/2) -3*d else 3*d), height - 6*d, paint)
            paint.textAlign = Paint.Align.LEFT
        }
        val series = listOf("ae", "af", "awb", "exposureNs", "iso", "intervalMs")
        val labels = listOf("AE", "AF", "AWB", "EXPOSURE", "ISO", "INTERVAL")
        val colors = listOf(mint, blue, amber, mint, blue, amber)
        for (i in series.indices) {
            val top = i*row
            val points = events.mapNotNull { e -> (e.values[series[i]] as? Number)?.toDouble()?.let { e.atNs to it } }.filter { it.first >= start }
            val current = points.lastOrNull()?.second
            val rendered = if (i < 3) stateName(labels[i], current?.toInt()) else when (i) {
                3 -> current?.let { "%.1f ms".format(Locale.US, it/1e6) }
                4 -> current?.toInt()?.toString()
                else -> current?.let { "%.1f ms".format(Locale.US, it) }
            } ?: "UNAVAILABLE"
            paint.color = Color.rgb(155, 175, 192); paint.textSize = 10*d; paint.style=Paint.Style.FILL
            canvas.drawText(labels[i], left, top+13*d, paint)
            paint.color = colors[i]
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(rendered, right, top+13*d, paint)
            paint.textAlign = Paint.Align.LEFT
            val y0 = top+22*d; val y1 = top+row-7*d
            paint.color = grid; paint.strokeWidth = d
            for (tick in 0..10) {
                val x = left+(right-left)*tick/10
                canvas.drawLine(x, y0, x, y1, paint)
            }
            canvas.drawLine(left, y1, right, y1, paint)
            if (points.isEmpty()) continue
            val min = if (i < 3) 0.0 else (points.minOf { it.second } * .8).coerceAtLeast(0.0)
            val max = if (i < 3) (if (i==1) 6.0 else if (i==0) 5.0 else 3.0) else maxOf(points.maxOf { it.second } * 1.1, min + 1)
            val path = Path(); var lastY = 0f; var lastTime: Long? = null
            points.forEach { (time, value) ->
                val x = left + (right-left)*((time-start)/1e10).toFloat().coerceIn(0f,1f)
                val y = y1 - (y1-y0)*((value-min)/(max-min)).toFloat().coerceIn(0f,1f)
                // Leave holes where telemetry is missing; categorical traces use steps.
                if (lastTime == null || time-lastTime!! > 500_000_000) path.moveTo(x,y)
                else { if (i<3) path.lineTo(x,lastY); path.lineTo(x,y) }
                lastY=y; lastTime=time
            }
            paint.style=Paint.Style.STROKE; paint.strokeWidth=1.8f*d; paint.color=colors[i]
            canvas.drawPath(path,paint); paint.style=Paint.Style.FILL
        }
    }
}
