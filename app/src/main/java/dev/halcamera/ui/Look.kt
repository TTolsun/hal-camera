package dev.halcamera.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Visual tokens from docs/design/DESIGN.md mapped in docs/archive/PRODUCT-v0.2.md 11.6. Consumer screens are light parchment with
 * one Action Blue; expert screens are dark tiles. Status colours are only used for state marks, never for buttons.
 */
object Look {
    val canvas = Color.parseColor("#f5f5f7")
    val card = Color.WHITE
    val hairline = Color.parseColor("#e0e0e0")
    val ink = Color.parseColor("#1d1d1f")
    val inkMuted = Color.parseColor("#7a7a7a")
    val primary = Color.parseColor("#0066cc")
    val onPrimary = Color.WHITE
    val expertTile = Color.parseColor("#272729")
    val expertTile2 = Color.parseColor("#2a2a2c")
    val expertTile3 = Color.parseColor("#252527")
    val onDark = Color.WHITE
    val onDarkMuted = Color.parseColor("#cccccc")
    val primaryOnDark = Color.parseColor("#2997ff")
    val statusPass = Color.parseColor("#34c759")
    val statusWarn = Color.parseColor("#ff9500")
    val statusFail = Color.parseColor("#ff3b30")
    val statusUnknown = inkMuted

    fun statusColor(level: String): Int = when (level.lowercase()) {
        "normal", "pass", "ok" -> statusPass
        "warning", "warn", "watch" -> statusWarn
        "issue", "fail" -> statusFail
        else -> statusUnknown
    }

    fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun cardBackground(context: Context, fill: Int = card, stroke: Int = hairline) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(context, 18).toFloat(); setStroke(dp(context, 1), stroke)
    }

    fun pill(context: Context, fill: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(context, 999).toFloat() }

    fun text(context: Context, s: CharSequence, sizeSp: Int, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(context).apply {
        text = s; textSize = sizeSp.toFloat(); setTextColor(color)
        typeface = if (mono) Typeface.MONOSPACE else if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        if (sizeSp >= 17) letterSpacing = -0.02f
        setLineSpacing(0f, if (sizeSp <= 14) 1.35f else 1.15f)
    }

    fun primaryButton(context: Context, label: String, action: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 17f; setTextColor(onPrimary); background = pill(context, primary)
        setPadding(dp(context, 24), dp(context, 14), dp(context, 24), dp(context, 14)); stateListAnimator = null
        setOnClickListener { action() }
    }

    fun ghostButton(context: Context, label: String, dark: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 15f
        setTextColor(if (dark) primaryOnDark else primary)
        background = GradientDrawable().apply { setColor(if (dark) expertTile2 else Color.parseColor("#fafafc")); cornerRadius = dp(context, 999).toFloat(); setStroke(dp(context, 1), if (dark) expertTile3 else hairline) }
        setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 12)); stateListAnimator = null
        setOnClickListener { action() }
    }

    fun card(context: Context, dark: Boolean = false) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = if (dark) cardBackground(context, expertTile2, expertTile3) else cardBackground(context)
        setPadding(dp(context, 18), dp(context, 16), dp(context, 18), dp(context, 16))
    }

    fun row(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
}
