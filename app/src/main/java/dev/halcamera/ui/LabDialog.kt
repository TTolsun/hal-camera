package dev.halcamera.ui

import android.app.AlertDialog
import android.content.Context
import android.view.ContextThemeWrapper
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import dev.halcamera.R

/** Scrollable, content-sized sheets for Lab settings and saved ZIPs. */
class LabDialog(context: Context, title: String) {
    val context: Context = ContextThemeWrapper(context, R.style.LabDialogTheme)
    val content = LinearLayout(this.context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(24), dp(24), dp(24))
    }
    private val dialog = AlertDialog.Builder(this.context).create().apply {
        setView(ScrollView(this@LabDialog.context).apply { addView(content) }, 0, 0, 0, 0)
    }

    init {
        content.addView(Look.text(this.context, title, 24, Look.ink, bold = true).apply {
            ViewCompat.setAccessibilityHeading(this, true)
        }, params(0))
    }

    fun group(build: LinearLayout.() -> Unit) {
        content.addView(Look.card(context).apply(build), params())
    }

    fun action(label: String, primary: Boolean = false, destructive: Boolean = false, block: () -> Unit = {}) {
        val button = if (primary) Look.primaryButton(context, label) { dialog.dismiss(); block() }
            else Look.ghostButton(context, label) { dialog.dismiss(); block() }
        if (destructive) button.setTextColor(Look.failureInk)
        content.addView(button, params(12))
    }

    fun onDismiss(block: () -> Unit) { dialog.setOnDismissListener { block() } }

    fun entry(label: String, block: () -> Unit) {
        content.addView(Look.ghostButton(context, label) { dialog.dismiss(); block() }.apply {
            setTextColor(Look.ink)
            textSize = 14f
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            background = Look.touchBackground(context, Look.labSurface, Look.hairline)
        }, params(12))
    }

    fun show() {
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(R.drawable.lab_dialog_background)
            setLayout(minOf(dp(560), context.resources.displayMetrics.widthPixels - dp(32)), -2)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    fun params(top: Int = 16) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int) = Look.dp(context, value)
}
