package dev.halcamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import dev.halcamera.telemetry.Event
import java.util.Locale

/** A shared automatic time ruler for one frame, directly on the preview. */
class ResultCallbackGraph(context: Context) : LinearLayout(context) {
    private val prefs = context.getSharedPreferences("callback_timeline", Context.MODE_PRIVATE)
    private val timeline = ResultCallbackTimeline().apply {
        autoHold = prefs.getBoolean("auto_hold", true)
        holdSeconds = prefs.getInt("hold_seconds", 3).takeIf { it in ResultCallbackTimeline.HOLD_SECONDS } ?: 3
    }
    private val widgets = CameraWidgets(context)
    private val status = widgets.label("Waiting for frames… Any moment now.", 11, Look.onDark).apply {
        setShadowLayer(widgets.dp(2).toFloat(), 0f, widgets.dp(1).toFloat(), Color.BLACK)
        setSingleLine()
        setAutoSizeTextTypeUniformWithConfiguration(10, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }
    private var nowNs = 0L
    private var renderedFrame: CallbackTimelineFrame? = null
    private val hold = widgets.button("Hold") {
        timeline.toggleAutoHold(nowNs)
        prefs.edit().putBoolean("auto_hold", timeline.autoHold).apply()
        render()
    }
    private val duration = widgets.button("${timeline.holdSeconds}s") {
        timeline.cycleHoldSeconds()
        prefs.edit().putInt("hold_seconds", timeline.holdSeconds).apply()
        render()
    }
    private val plot = Plot(context)
    private val heldIndicator = HeldIndicator(context)

    init {
        orientation = VERTICAL
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(heldIndicator, LayoutParams(widgets.dp(16), widgets.dp(24)))
            addView(status, LayoutParams(0, -2, 1f))
            addView(hold, LayoutParams(widgets.dp(64), widgets.dp(48)))
            addView(duration.apply {
                setTextColor(Look.onDark)
                background = widgets.chrome(Color.TRANSPARENT)
                setShadowLayer(widgets.dp(2).toFloat(), 0f, 0f, Color.BLACK)
                setPadding(widgets.dp(12), 0, widgets.dp(12), 0)
                minWidth = widgets.dp(56)
                minimumWidth = widgets.dp(56)
                typeface = Look.mono
            }, LayoutParams(widgets.dp(56), widgets.dp(48)).apply { marginStart = widgets.dp(4) })
        }, LayoutParams(-1, -2))
        hold.setTextColor(Look.onDark)
        hold.background = widgets.chrome(Color.TRANSPARENT)
        hold.setShadowLayer(widgets.dp(2).toFloat(), 0f, 0f, Color.BLACK)
        hold.setPadding(widgets.dp(12), 0, widgets.dp(12), 0)
        hold.minWidth = widgets.dp(64)
        hold.minimumWidth = widgets.dp(64)
        addView(plot, LayoutParams(-1, -2))
        render()
    }

    fun reset() { timeline.reset() }

    fun update(events: List<Event>, session: String, nowNs: Long, metadata: Map<String, Any?>) {
        this.nowNs = nowNs
        // MainActivity already schedules this at 100 ms. A second throttle adds visible lag.
        timeline.update(ResultCallbackSeries.read(events, session, nowNs, metadata), session,
            (metadata["callbackStreamsAtNs"] as? Number)?.toLong() ?: Long.MIN_VALUE, nowNs)
        render()
    }

    private fun render() {
        val mode = if (timeline.autoHold) "Event Frame" else "Real-time Frame"
        fun TextView.textIfChanged(value: String) { if (text.toString() != value) text = value }
        status.textIfChanged("$mode #${timeline.displayed?.number ?: "—"}")
        val held = timeline.autoHoldUntilNs != null
        heldIndicator.bind(held)
        status.contentDescription = if (held) "Held, ${status.text}" else status.text
        hold.textIfChanged(if (timeline.autoHold) "Live" else "Hold")
        duration.textIfChanged("${timeline.holdSeconds}s")
        val durationVisibility = if (timeline.autoHold) View.VISIBLE else View.INVISIBLE
        if (duration.visibility != durationVisibility) duration.visibility = durationVisibility
        duration.contentDescription = "Auto-hold duration: ${timeline.holdSeconds} seconds; tap for the next duration"
        hold.contentDescription = if (timeline.autoHold) "Turn off auto-hold" else "Turn on auto-hold"
        if (renderedFrame != timeline.displayed) {
            renderedFrame = timeline.displayed
            plot.contentDescription = "Frame ${timeline.displayed?.number ?: "none"}. Relative to the previous frame shutter callback. " + timeline.displayed?.rows.orEmpty().joinToString(". ") {
                "${it.label} ${it.state ?: it.latenciesMs.joinToString { value -> format(value) }}"
            }
        }
        plot.bind(timeline.displayed?.rows.orEmpty(), timeline.axisMs)
    }

    private fun format(value: Double) = String.format(Locale.US, "%.1f ms", value)

    /** The slot remains in place while live; only an actual held frame shows the pause mark. */
    private inner class HeldIndicator(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private var held = false
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        fun bind(value: Boolean) {
            if (held == value) return
            held = value
            invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            if (!held) return
            val density = resources.displayMetrics.density
            for (x in listOf(4f, 9f)) {
                paint.color = Color.BLACK
                paint.strokeWidth = 4f * density
                canvas.drawLine(x * density, height / 2f - 4f * density, x * density, height / 2f + 4f * density, paint)
                paint.color = Look.onDark
                paint.strokeWidth = 2f * density
                canvas.drawLine(x * density, height / 2f - 4f * density, x * density, height / 2f + 4f * density, paint)
            }
        }
    }

    private inner class Plot(context: Context) : View(context) {
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            color = Color.argb(220, 0, 0, 0)
        }
        private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private val density = resources.displayMetrics.density
        private fun dp(value: Float) = value * density
        private fun sp(value: Float) = dp(value) * resources.configuration.fontScale
        private val rowHeight get() = sp(24f)
        private var rows = emptyList<CallbackTimelineRow>()
        private var valueLabels = emptyList<String>()
        private var axisMs = 200
        private var minimum = 0
        private var widestValue = "200.0 ms"
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            textPaint.setShadowLayer(dp(1.5f), 0f, dp(0.5f), Color.BLACK)
        }

        fun bind(nextRows: List<CallbackTimelineRow>, nextAxisMs: Int) {
            if (rows == nextRows && axisMs == nextAxisMs) return
            val sizeChanged = rows.size != nextRows.size
            rows = nextRows
            axisMs = nextAxisMs
            minimum = if (rows.any { row -> row.latenciesMs.any { it < 0 } }) -axisMs else 0
            widestValue = format(if (minimum < 0) minimum.toDouble() else axisMs.toDouble())
            valueLabels = rows.map { row -> row.latenciesMs.maxOrNull()?.let(::format) ?: row.state.orEmpty() }
            // A new value changes pixels, not the dimensions of the camera screen.
            if (sizeChanged) requestLayout()
            invalidate()
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(
                (sp(24f) + rows.size * rowHeight).toInt(), heightMeasureSpec))
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            fun label(text: String, x: Float, y: Float, size: Float, color: Int,
                align: Paint.Align = Paint.Align.LEFT, mono: Boolean = false) {
                textPaint.color = color
                textPaint.typeface = if (mono) Look.mono else Typeface.DEFAULT
                textPaint.textSize = sp(size)
                textPaint.textAlign = align
                textOutline.typeface = textPaint.typeface
                textOutline.textSize = textPaint.textSize
                textOutline.textAlign = align
                textOutline.strokeWidth = dp(2f)
                canvas.drawText(text, x, y, textOutline)
                canvas.drawText(text, x, y, textPaint)
            }
            fun rulerLine(x1: Float, y1: Float, x2: Float, y2: Float) {
                markPaint.color = Color.argb(200, 0, 0, 0)
                markPaint.strokeWidth = dp(2.5f)
                canvas.drawLine(x1, y1, x2, y2, markPaint)
                markPaint.color = Look.onDark
                markPaint.strokeWidth = dp(0.75f)
                canvas.drawLine(x1, y1, x2, y2, markPaint)
            }
            textPaint.textSize = sp(11f)
            textPaint.typeface = Typeface.DEFAULT
            val left = ((rows.maxOfOrNull { textPaint.measureText(it.label) } ?: 0f) + dp(16f))
                .coerceIn(dp(60f), width * 0.34f)
            textPaint.typeface = Look.mono
            textPaint.textSize = sp(11f)
            val valueWidth = textPaint.measureText(widestValue)
            val valueRight = width - dp(4f)
            val right = (valueRight - dp(16f) - valueWidth).coerceAtLeast(left + dp(32f))
            fun x(value: Double) = left + (right - left) *
                ((value - minimum) / (axisMs.toDouble() - minimum)).coerceIn(0.0, 1.0).toFloat()
            rulerLine(left, sp(20f), right, sp(20f))
            rulerLine(x(0.0), sp(20f), x(0.0), height - dp(8f))
            val divisions = if (right - left >= sp(150f)) 4 else 2
            for (part in 0..divisions) {
                val value = minimum + (axisMs.toDouble() - minimum) * part / divisions
                val position = x(value)
                label(value.toInt().toString(), position, sp(12f), 9f, Look.onDark, Paint.Align.CENTER, mono = true)
                rulerLine(position, sp(17f), position, sp(23f))
            }
            rows.forEachIndexed { index, row ->
                val y = sp(38f) + rowHeight * index
                val color = when (index) {
                    0 -> Look.onDarkMuted
                    1 -> Look.onDark
                    else -> when ((index - 2) % 4) {
                        0 -> Look.primaryOnDark
                        1 -> Look.statusPass
                        2 -> Look.statusWarn
                        else -> Look.statusFail
                    }
                }
                label(row.label, dp(4f), y, 11f, if (row.latenciesMs.isEmpty()) Look.onDarkMuted else Look.onDark)
                if (row.latenciesMs.isEmpty()) {
                    label(valueLabels[index], valueRight, y, 10f, Look.onDarkMuted, Paint.Align.RIGHT)
                } else {
                    val end = row.latenciesMs.max()
                    rulerLine(x(minOf(0.0, row.latenciesMs.min())), y - sp(3.5f), x(maxOf(0.0, end)), y - sp(3.5f))
                    row.latenciesMs.forEach { value ->
                        markPaint.color = Color.BLACK
                        canvas.drawCircle(x(value), y - sp(3.5f), dp(3.75f), markPaint)
                        markPaint.color = color
                        canvas.drawCircle(x(value), y - sp(3.5f), dp(2.75f), markPaint)
                    }
                    label(valueLabels[index], valueRight, y, 11f, Look.onDark, Paint.Align.RIGHT, mono = true)
                }
            }
        }
    }
}
