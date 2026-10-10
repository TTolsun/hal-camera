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
import dev.halcamera.camera.LiveEisStatus

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
    private val eis = TextView(context).apply {
        textSize = 10f
        setTextColor(Look.onDarkMuted)
        gravity = Gravity.CENTER_VERTICAL
        minimumWidth = Look.dp(context, 48)
        minimumHeight = Look.dp(context, 48)
        isFocusable = true
        val ripple = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        if (ripple.resourceId != 0) setBackgroundResource(ripple.resourceId)
        setOnClickListener { onSizesClick?.invoke() }
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val eisWarning = TextView(context).apply {
        textSize = 12f
        setTextColor(Look.statusWarn)
        background = Look.cardBackground(context, Look.cameraGlass, android.graphics.Color.TRANSPARENT)
        setPadding(Look.dp(context, 8), Look.dp(context, 6), Look.dp(context, 8), Look.dp(context, 6))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private var recording = false

    init {
        orientation = VERTICAL
        heartbeat.text = "Live"
        heartbeat.compoundDrawablePadding = Look.dp(context, 4)
        heartbeat.setCompoundDrawablesRelative(dot, null, null, null)
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(heartbeat, LayoutParams(-2, -2))
            addView(sizes, LayoutParams(0, -2, 1f).apply { marginStart = Look.dp(context, 8) })
            addView(eis, LayoutParams(-2, -2).apply { marginStart = Look.dp(context, 8) })
        }, LayoutParams(-1, -2))
        addView(eisWarning, LayoutParams(-1, -2))
        setPadding(Look.dp(context, 12), 0, Look.dp(context, 12), 0)
        bindStabilization(LiveEisStatus(), false)
        render()
    }

    fun bindStabilization(status: LiveEisStatus, recording: Boolean) {
        this.recording = recording
        val hidden = status.video == 0 && status.warning == null
        eis.visibility = if (hidden) View.GONE else View.VISIBLE
        eisWarning.visibility = if (status.warning == null) View.GONE else View.VISIBLE
        val warning = status.warning.orEmpty()
        if (eisWarning.text.toString() != warning) eisWarning.text = warning
        val value = when (status.video) {
            0 -> "EIS: Off"
            1 -> "EIS: V"
            2 -> "EIS: P, V"
            else -> "EIS: ?"
        }
        if (eis.text.toString() != value) eis.text = value
        eis.contentDescription = "${if (recording) "Recording" else "Preview"}, " + (when (status.video) {
            0 -> "EIS inactive"
            1 -> "EIS active, Video mode"
            2 -> "EIS active, Preview and Video mode"
            else -> "EIS status unknown"
        }) + ", open Live Streams"
        eis.setTextColor(when {
            status.warning != null -> Look.statusWarn
            status.video == null -> Look.onDarkMuted
            else -> Look.onDarkMuted
        })
    }

    fun bindSizes(streams: Map<*, *>?) {
        val labels = listOf(Triple("preview", "P", "Preview"), Triple("analysis", "Y", "YUV"),
            Triple("jpeg", "J", "JPEG"), Triple("raw", "RAW", "RAW"), Triple("recording", "R", "Recording"))
        fun size(key: String): String? = if (key == "jpeg" && streams?.get(key) == null && streams?.get("appJpeg") != null)
            "YUV (${streams["appJpeg"]})" else streams?.get(key)?.toString()
        val active = labels.filter { streams?.containsKey(it.first) == true }
        val recordingFormat = streams?.get("recordingFormat")?.toString()?.takeIf { it.isNotBlank() } ?: "Auto"
        val value = active.joinToString("   ") { (key, label, _) ->
            val prefix = if (key == "recording") "$label $recordingFormat" else label
            "$prefix ${size(key)?.replace('x', '×') ?: "Off"}"
        }
        if (sizes.text.toString() != value) sizes.text = value
        sizes.contentDescription = active.joinToString(", ") { (key, _, name) ->
            val prefix = if (key == "recording") "$name $recordingFormat" else name
            "$prefix ${size(key) ?: "Off"}"
        } + ", open Live Streams"
        sizes.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
    }

    fun setSizesEnabled(enabled: Boolean) {
        sizes.isEnabled = enabled
        eis.isEnabled = enabled
    }

    fun bind(running: Boolean) {
        if (!running) bindStabilization(LiveEisStatus(), recording)
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
        if (running && isAttachedToWindow && isShown && ValueAnimator.areAnimatorsEnabled()) {
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
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView,visibility)
        if (isAttachedToWindow) render()
    }
}
