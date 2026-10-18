package dev.halcamera.ui

import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import android.view.Gravity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import dev.halcamera.camera.SplitArrangement
import dev.halcamera.camera.SplitDragGesture
import dev.halcamera.camera.SplitLayout

/**
 * Hold a Split panel's title for [HOLD_MS] and drag it, like Windows snap. An outline shows where the panel lands;
 * releasing applies [SplitArrangement.snap]. Accessibility actions offer the same moves as Win+arrow keys.
 */
internal class SplitPanelDrag(
    private val stage: FrameLayout,
    /** Preview area in [stage] coordinates, or null before layout. */
    private val area: () -> Rect?,
    private val arrangement: () -> SplitArrangement,
    private val enabled: () -> Boolean,
    private val onDrop: (SplitArrangement) -> Unit,
) {
    private val context = stage.context
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val hint = TextView(context).apply {
        textSize = 14f; setTextColor(Look.onDark)
        gravity = Gravity.CENTER
        setPadding(Look.dp(context,12),Look.dp(context,8),Look.dp(context,12),Look.dp(context,8))
        background = GradientDrawable().apply { setColor(Look.cameraCard); cornerRadius = Look.dp(context,8).toFloat() }
    }
    private val outline = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = Look.dp(context,8).toFloat()
            setColor(ColorUtils.setAlphaComponent(Look.cameraSelection,48))
            setStroke(Look.dp(context,2),Look.cameraSelection)
        }
        translationZ = Look.dp(context,12).toFloat()
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS; visibility = View.GONE
        addView(hint,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
    }

    // Non-drag touches fall through to the handle click; drags have explicit accessibility actions.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun attach(handle: View, panel: View, index: Int) {
        var dragging = false
        var startX = 0f; var startY = 0f
        var target: SplitArrangement? = null
        var gesture: SplitDragGesture? = null
        var cancelled = false
        fun end(drop: Boolean) {
            dragging = false; handle.isPressed = false
            panel.translationX = 0f; panel.translationY = 0f; panel.translationZ = 0f; panel.alpha = 1f
            outline.visibility = View.GONE
            handle.parent?.requestDisallowInterceptTouchEvent(false)
            gesture = null
            val next = target; target = null
            if (drop && enabled() && next != null && next != arrangement()) onDrop(next)
        }
        fun move(dx: Float, dy: Float) {
            panel.translationX = dx; panel.translationY = dy
            val next = gesture?.update(dx,dy) ?: return
            if (target != null && next != target) handle.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            target = next
            preview(index,next)
        }
        // Shorter than the system long press (500ms on the S25+), so picking a panel up feels immediate.
        val begin = Runnable {
            val bounds = area() ?: return@Runnable
            if (!enabled() || !handle.isAttachedToWindow || bounds.width() <= 0 || bounds.height() <= 0) return@Runnable
            gesture = SplitDragGesture(arrangement(),index,bounds.width(),bounds.height(),Look.dp(context,24).toFloat())
            dragging = true; target = null; handle.isPressed = false
            handle.parent?.requestDisallowInterceptTouchEvent(true)
            handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            panel.translationZ = Look.dp(context,8).toFloat(); panel.alpha = .85f
            if (outline.parent == null) stage.addView(outline,FrameLayout.LayoutParams(0,0))
            move(0f,0f)
        }
        handle.setOnTouchListener { _,event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelled = false
                    startX = event.rawX; startY = event.rawY
                    handle.removeCallbacks(begin); handle.postDelayed(begin,HOLD_MS); false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    handle.removeCallbacks(begin); cancelled = true; handle.isPressed = false
                    if (dragging) end(drop = false)
                    true
                }
                MotionEvent.ACTION_MOVE -> if (cancelled) true else if (!dragging) {
                    if (Math.hypot((event.rawX-startX).toDouble(),(event.rawY-startY).toDouble()) > slop) handle.removeCallbacks(begin)
                    false
                } else {
                    if (!enabled()) { end(drop = false); cancelled = true }
                    else move(event.rawX-startX,event.rawY-startY)
                    true
                }
                MotionEvent.ACTION_UP -> { handle.removeCallbacks(begin); if (cancelled) true else if (!dragging) false else {
                    move(event.rawX-startX,event.rawY-startY); end(drop = true); true
                } }
                MotionEvent.ACTION_CANCEL -> { handle.removeCallbacks(begin); if (!dragging) false else { end(drop = false); true } }
                else -> dragging
            }
        }
        handle.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                handle.removeCallbacks(begin)
                if (dragging) end(drop = false)
            }
        })
        listOf("Move to left" to (0f to .5f),"Move to right" to (1f to .5f),
            "Move to top" to (.5f to 0f),"Move to bottom" to (.5f to 1f)).forEach { (label,point) ->
            ViewCompat.addAccessibilityAction(handle,label) { _,_ ->
                val bounds = area()
                if (!enabled() || bounds == null) false else {
                    val next = arrangement().snap(index,point.first*bounds.width(),point.second*bounds.height(),bounds.width(),bounds.height())
                    if (next != arrangement()) onDrop(next); true
                }
            }
        }
    }

    private fun preview(index: Int, next: SplitArrangement) {
        val bounds = area() ?: return
        val frame = next.frames(bounds.width(),bounds.height()).getOrNull(index) ?: return
        hint.text = if (next == arrangement()) "Drag ↑ ↓ ← →" else "Release · " + when (next.layout) {
            SplitLayout.STACKED -> if (next.order.first() == index) "Top" else "Bottom"
            SplitLayout.SIDE_BY_SIDE -> if (next.order.first() == index) "Left" else "Right"
            SplitLayout.GRID -> "Screen ${next.order.indexOf(index)+1}"
        }
        val inset = Look.dp(context,4)
        val params = outline.layoutParams as? FrameLayout.LayoutParams ?: return
        val width = (frame.width-2*inset).coerceAtLeast(1); val height = (frame.height-2*inset).coerceAtLeast(1)
        val left = bounds.left+frame.left+inset; val top = bounds.top+frame.top+inset
        if (params.width != width || params.height != height || params.leftMargin != left || params.topMargin != top)
            outline.layoutParams = FrameLayout.LayoutParams(width,height).apply { leftMargin = left; topMargin = top }
        outline.visibility = View.VISIBLE
    }

    private companion object { const val HOLD_MS = 250L }
}
