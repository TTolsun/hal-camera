package dev.halcamera.ui

import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import dev.halcamera.camera.SplitArrangement

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
    private val outline = View(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = Look.dp(context,8).toFloat()
            setColor(ColorUtils.setAlphaComponent(Look.cameraSelection,48))
            setStroke(Look.dp(context,2),Look.cameraSelection)
        }
        translationZ = Look.dp(context,4).toFloat()
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; visibility = View.GONE
    }

    // Non-drag touches fall through to the handle click; drags have explicit accessibility actions.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun attach(handle: View, panel: View, index: Int) {
        var dragging = false
        var startX = 0f; var startY = 0f
        var target: SplitArrangement? = null
        fun end(drop: Boolean) {
            dragging = false; handle.isPressed = false
            panel.translationX = 0f; panel.translationY = 0f; panel.translationZ = 0f; panel.alpha = 1f
            outline.visibility = View.GONE
            val next = target; target = null
            if (drop && next != null && next != arrangement()) onDrop(next)
        }
        // Shorter than the system long press (500ms on the S25+), so picking a panel up feels immediate.
        val begin = Runnable {
            if (!enabled() || area() == null) return@Runnable
            dragging = true; target = null; handle.isPressed = false
            handle.parent?.requestDisallowInterceptTouchEvent(true)
            handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            panel.translationZ = Look.dp(context,8).toFloat(); panel.alpha = .85f
            if (outline.parent == null) stage.addView(outline,FrameLayout.LayoutParams(0,0))
            target = preview(index,0f,0f)
        }
        handle.setOnTouchListener { _,event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    handle.removeCallbacks(begin); handle.postDelayed(begin,HOLD_MS); false
                }
                MotionEvent.ACTION_MOVE -> if (!dragging) {
                    if (Math.hypot((event.rawX-startX).toDouble(),(event.rawY-startY).toDouble()) > slop) handle.removeCallbacks(begin)
                    false
                } else {
                    panel.translationX = event.rawX-startX; panel.translationY = event.rawY-startY
                    target = preview(index,event.rawX-startX,event.rawY-startY); true
                }
                MotionEvent.ACTION_UP -> { handle.removeCallbacks(begin); if (!dragging) false else {
                    target = preview(index,event.rawX-startX,event.rawY-startY); end(drop = true); true
                } }
                MotionEvent.ACTION_CANCEL -> { handle.removeCallbacks(begin); if (!dragging) false else { end(drop = false); true } }
                else -> dragging
            }
        }
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

    /** Places the outline where the panel moved by ([dx], [dy]) would land; at pick-up that is its current place. */
    private fun preview(index: Int, dx: Float, dy: Float): SplitArrangement? {
        val bounds = area() ?: return null
        val next = arrangement().drag(index,dx,dy,bounds.width(),bounds.height())
        val frame = next.frames(bounds.width(),bounds.height()).getOrNull(index) ?: return null
        val inset = Look.dp(context,4)
        val params = outline.layoutParams as? FrameLayout.LayoutParams ?: return next
        val width = (frame.width-2*inset).coerceAtLeast(1); val height = (frame.height-2*inset).coerceAtLeast(1)
        val left = bounds.left+frame.left+inset; val top = bounds.top+frame.top+inset
        if (params.width != width || params.height != height || params.leftMargin != left || params.topMargin != top)
            outline.layoutParams = FrameLayout.LayoutParams(width,height).apply { leftMargin = left; topMargin = top }
        outline.visibility = View.VISIBLE
        return next
    }

    private companion object { const val HOLD_MS = 250L }
}
