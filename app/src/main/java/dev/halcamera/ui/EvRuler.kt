package dev.halcamera.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The exposure compensation dial: a scale of every EV step that slides under a fixed centre mark, as in stock
 * camera apps. Dragging moves the scale and each step it crosses is sent at once, so the preview brightens while
 * the finger moves; lifting snaps to the nearest step. A double tap returns to 0.
 *
 * A dial instead of a list because steps differ by device: 1/3 EV gives 13 values, 0.1 EV (Galaxy S25+) gives 41,
 * and 41 rows is not a control anyone wants to scroll.
 *
 * Accessibility: exposed as an adjustable control; scroll forward and backward move one step.
 */
class EvRuler(context: Context, private val onChange: (Int) -> Unit) : View(context) {
    private var range = 0..0
    private var step = 1.0
    private var index = 0
    /** Scale position in steps, fractional while dragging. */
    private var position = 0f
    private var dragging = false
    private var wholeTicks = emptySet<Int>()
    private val spacing get() = Look.dp(context, if (step < 0.2) 9 else 20).toFloat()

    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Look.onDarkMuted; strokeWidth = Look.dp(context, 1).toFloat(); strokeCap = Paint.Cap.ROUND }
    private val major = Paint(tick).apply { color = Look.onDark; strokeWidth = Look.dp(context, 2).toFloat() }
    private val numbers = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Look.onDarkMuted; textAlign = Paint.Align.CENTER; textSize = Look.dp(context, 11).toFloat(); typeface = Look.mono
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Look.onDark; textAlign = Paint.Align.CENTER; textSize = Look.dp(context, 13).toFloat()
        typeface = Typeface.create(Look.mono, Typeface.BOLD)
    }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Look.cameraSelection; style = Paint.Style.FILL }
    private val markerPath = Path()

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean { select(0); return true }
    })

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
    }

    fun set(range: IntRange, step: Double, current: Int) {
        if (range != this.range || step != this.step) {
            // The step closest to each whole EV gets the tall tick and a number, so 1/3 and 0.1 steps both mark -2..+2 once.
            wholeTicks = (kotlin.math.ceil(range.first * step - 1e-6).toInt()..kotlin.math.floor(range.last * step + 1e-6).toInt())
                .map { n -> (n / step).roundToInt() }.toSet()
        }
        this.range = range; this.step = step
        if (!dragging) { index = current.coerceIn(range); position = index.toFloat() }
        contentDescription = "노출 보정 ${label(index)}. 좌우로 끌어 조절, 두 번 탭하면 0"
        invalidate()
    }

    private fun label(i: Int) = dev.halcamera.camera.LiveControlText.ev(i * step)

    private fun select(i: Int) {
        val next = i.coerceIn(range)
        position = next.toFloat()
        if (next != index) { index = next; onChange(next); performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
        contentDescription = "노출 보정 ${label(index)}"
        invalidate()
    }

    private var lastX = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; dragging = true; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                // The scale follows the finger like a physical dial: dragging left brings the brighter side under the mark.
                position = (position - (event.x - lastX) / spacing).coerceIn(range.first.toFloat(), range.last.toFloat())
                lastX = event.x
                val nearest = position.roundToInt()
                if (nearest != index) { index = nearest; onChange(nearest); performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; select(position.roundToInt()) }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val base = height - Look.dp(context, 18).toFloat()
        canvas.drawText(label(index), cx, Look.dp(context, 14).toFloat(), value)
        for (i in range) {
            val x = cx + (i - position) * spacing
            if (x < -spacing || x > width + spacing) continue
            val ev = i * step
            val whole = i in wholeTicks
            // Ticks fade toward the edges so the centre reads first.
            val fade = (1f - abs(x - cx) / (width / 2f)).coerceIn(0.15f, 1f)
            val paint = if (whole) major else tick
            paint.alpha = (255 * fade).toInt()
            val h = Look.dp(context, if (whole) 14 else 8).toFloat()
            canvas.drawLine(x, base - h, x, base, paint)
            if (whole) {
                numbers.alpha = (255 * fade).toInt()
                val n = ev.roundToInt()
                canvas.drawText(if (n > 0) "+$n" else if (n < 0) "−${-n}" else "0", x, base + Look.dp(context, 14), numbers)
            }
        }
        val m = Look.dp(context, 5).toFloat()
        val top = base - Look.dp(context, 22)
        markerPath.reset()
        markerPath.moveTo(cx - m, top); markerPath.lineTo(cx + m, top); markerPath.lineTo(cx, top + m * 1.4f); markerPath.close()
        canvas.drawPath(markerPath, marker)
    }


    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.SeekBar::class.java.name
        if (index < range.last) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        if (index > range.first) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { select(index + 1); true }
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { select(index - 1); true }
        else -> super.performAccessibilityAction(action, arguments)
    }
}
