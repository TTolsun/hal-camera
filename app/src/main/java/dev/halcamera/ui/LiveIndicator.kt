package dev.halcamera.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** A small preview heartbeat. Both the dot and label pulse together, independently of recording. */
class LiveIndicator(context: Context) : TextView(context) {
    private val dot = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setBounds(0, 0, Look.dp(context, 5), Look.dp(context, 5))
    }
    private var running = false
    private var pulse: ObjectAnimator? = null

    init {
        text = "Live"
        textSize = 10f
        gravity = Gravity.CENTER
        compoundDrawablePadding = Look.dp(context, 4)
        setCompoundDrawablesRelative(dot, null, null, null)
        setPadding(Look.dp(context, 12), 0, Look.dp(context, 12), 0)
        render()
    }

    fun bind(running: Boolean) {
        if (this.running == running) return
        this.running = running
        render()
    }

    private fun render() {
        pulse?.cancel()
        pulse = null
        alpha = 1f
        val color = if (running) Look.statusFail else Look.onDarkMuted
        setTextColor(color)
        dot.setColor(color)
        contentDescription = if (running) "Live, 프리뷰 동작 중" else "Live, 프리뷰 정지"
        if (running && isAttachedToWindow && windowVisibility == View.VISIBLE && ValueAnimator.areAnimatorsEnabled()) {
            pulse = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0.3f).apply {
                duration = 600
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); render() }
    override fun onDetachedFromWindow() { pulse?.cancel(); pulse = null; alpha = 1f; super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) render()
    }
}
