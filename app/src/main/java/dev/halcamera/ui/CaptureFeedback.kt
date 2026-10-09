package dev.halcamera.ui

import android.content.Context
import android.view.Gravity
import android.widget.TextView

/** One compact line next to the shutter: progress takes priority over a recent result. */
class CaptureFeedback(context: Context) : TextView(context) {
    private var progress: String? = null
    private var result: String? = null
    private var bracket = false

    init {
        textSize = 12f
        setTextColor(Look.onDark)
        gravity = Gravity.CENTER
        minHeight = Look.dp(context, 24)
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = INVISIBLE
    }

    fun bind(progress: String?, bracket: Boolean) {
        if (this.progress == null && progress != null) result = null
        this.progress = progress; this.bracket = bracket
        render()
    }

    fun showResult(value: String) {
        result = value
        render()
    }

    /** Keep the outcome available after an interruption; the next capture clears it explicitly. */
    fun clearResult() { result = null; render() }

    /** Sequence progress already covers routine per-shot notices; errors still use the camera notice. */
    fun coversStatus(message: String) = (progress != null || result != null) &&
        (message.startsWith("Capturing") || message == "Saving…" || message.startsWith("Saved "))

    private fun render() {
        val value = progress ?: result ?: if (bracket) "AEB" else ""
        if (text.toString() != value) text = value
        visibility = if (value.isEmpty()) INVISIBLE else VISIBLE
    }

}
