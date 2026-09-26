package dev.halcamera.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * View pieces for the dark camera screen: LIVE and its 진단 panel build their labels, buttons and outlined cards from
 * the same factory so both read as one surface.
 *
 * [enabled] gates every button built here. LIVE passes "no CLI request is running", so a tap cannot race a command
 * that is driving the camera from ADB.
 */
class CameraWidgets(private val context: Context, private val enabled: () -> Boolean = { true }) {
    fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = size.coerceAtLeast(12).toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun button(text: String, action: () -> Unit) = Button(context).apply {
        this.text = text
        isAllCaps = false
        textSize = 12f
        setTextColor(Look.onDark)
        background = chrome(Look.cameraCard, true)
        backgroundTintList = null
        stateListAnimator = null
        setPadding(0, 0, 0, 0)
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setOnClickListener { if (enabled()) action() }
    }

    fun chrome(fill: Int = Look.cameraGlass, outlined: Boolean = false) = RippleDrawable(
        ColorStateList.valueOf(0x40FFFFFF),
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(4).toFloat()
            if (outlined) setStroke(dp(1), Look.cameraOutline)
        },
        rounded(Color.WHITE),
    )

    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(4).toFloat()
        setStroke(dp(1), Look.cameraOutline)
    }

    fun lp(height: Int = -2, top: Int = 0) =
        LinearLayout.LayoutParams(-1, if (height < 0) height else dp(height)).apply { topMargin = dp(top) }

    fun dp(value: Int) = Look.dp(context, value)
}
