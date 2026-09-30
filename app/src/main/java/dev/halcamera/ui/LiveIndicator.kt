package dev.halcamera.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.util.TypedValue
import android.widget.TextView
import android.widget.LinearLayout

/** A small preview heartbeat. Both the dot and label pulse together, independently of recording. */
class LiveIndicator(context: Context) : LinearLayout(context) {
    var onSizesClick: (() -> Unit)? = null
    private val heartbeat = TextView(context).apply { textSize = 10f }
    private val sizes = TextView(context).apply {
        textSize = 10f
        maxLines = 1
        setHorizontallyScrolling(false)
        setAutoSizeTextTypeUniformWithConfiguration(1, 10, 1, TypedValue.COMPLEX_UNIT_SP)
        setTextColor(Look.onDarkMuted)
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = Look.dp(context, 48)
        isFocusable = true
        val ripple = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        if (ripple.resourceId != 0) setBackgroundResource(ripple.resourceId)
        setOnClickListener { onSizesClick?.invoke() }
        visibility = View.GONE
    }
    private val dot = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setBounds(0, 0, Look.dp(context, 5), Look.dp(context, 5))
    }
    private var running = false
    private var pulse: ObjectAnimator? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        heartbeat.text = "Live"
        heartbeat.compoundDrawablePadding = Look.dp(context, 4)
        heartbeat.setCompoundDrawablesRelative(dot, null, null, null)
        addView(heartbeat, LayoutParams(-2, -2))
        addView(sizes, LayoutParams(0, -2, 1f).apply { marginStart = Look.dp(context, 8) })
        setPadding(Look.dp(context, 12), 0, Look.dp(context, 12), 0)
        render()
    }

    fun bindSizes(streams: Map<*, *>?) {
        val labels = listOf(Triple("preview", "P", "Preview"), Triple("analysis", "Y", "YUV"),
            Triple("jpeg", "J", "JPEG"), Triple("recording", "R", "Recording"))
        val active = labels.filter { streams?.containsKey(it.first) == true }
        val recordingFormat = streams?.get("recordingFormat")?.toString()?.takeIf { it.isNotBlank() } ?: "Auto"
        val value = active.joinToString("   ") { (key, label, _) ->
            val prefix = if (key == "recording") "$label $recordingFormat" else label
            "$prefix ${streams?.get(key)?.toString()?.replace('x', '×') ?: "Off"}"
        }
        if (sizes.text.toString() != value) sizes.text = value
        sizes.contentDescription = active.joinToString(", ") { (key, _, name) ->
            val prefix = if (key == "recording") "$name $recordingFormat" else name
            "$prefix ${streams?.get(key) ?: "Off"}"
        } + ", open stream size settings"
        sizes.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
    }

    fun setSizesEnabled(enabled: Boolean) { sizes.isEnabled = enabled }

    fun bind(running: Boolean) {
        if (this.running == running) return
        this.running = running
        render()
    }

    private fun render() {
        pulse?.cancel()
        pulse = null
        heartbeat.alpha = 1f
        val color = if (running) Look.statusFail else Look.onDarkMuted
        heartbeat.setTextColor(color)
        dot.setColor(color)
        heartbeat.contentDescription = if (running) "Live, preview running" else "Live, preview stopped"
        if (running && isAttachedToWindow && windowVisibility == View.VISIBLE && ValueAnimator.areAnimatorsEnabled()) {
            pulse = ObjectAnimator.ofFloat(heartbeat, View.ALPHA, 1f, 0.3f).apply {
                duration = 600
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); render() }
    override fun onDetachedFromWindow() { pulse?.cancel(); pulse = null; heartbeat.alpha = 1f; super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) render()
    }
}
