package dev.halcamera.ui

import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.accessibility.AccessibilityManager
import android.widget.TextView

/** One compact line next to the shutter: progress takes priority over a recent result. */
class CaptureFeedback(context: Context) : TextView(context) {
    private var progress: String? = null
    private var result: String? = null
    private var bracket = false
    private val clear = Runnable { result = null; render() }

    init {
        textSize = 12f
        setTextColor(Look.onDark)
        gravity = Gravity.CENTER
        minHeight = Look.dp(context, 24)
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = INVISIBLE
    }

    fun bind(progress: String?, bracket: Boolean) {
        if (this.progress == null && progress != null) { result = null; removeCallbacks(clear) }
        this.progress = progress; this.bracket = bracket
        render()
    }

    fun showResult(value: String) {
        result = value
        removeCallbacks(clear)
        val accessibility = context.getSystemService(AccessibilityManager::class.java)
        val timeout = if (Build.VERSION.SDK_INT >= 29)
            accessibility.getRecommendedTimeoutMillis(5000, AccessibilityManager.FLAG_CONTENT_TEXT) else 5000
        postDelayed(clear, timeout.toLong())
        render()
    }

    fun clearResult() { result = null; removeCallbacks(clear); render() }

    /** Sequence progress already covers routine per-shot notices; errors still use the camera notice. */
    fun coversStatus(message: String) = (progress != null || result != null) &&
        (message.startsWith("Capturing") || message == "Saving…" || message.startsWith("Saved "))

    private fun render() {
        val value = progress ?: result ?: if (bracket) "AEB" else ""
        if (text.toString() != value) text = value
        visibility = if (value.isEmpty()) INVISIBLE else VISIBLE
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(clear)
        result = null
        super.onDetachedFromWindow()
    }
}
