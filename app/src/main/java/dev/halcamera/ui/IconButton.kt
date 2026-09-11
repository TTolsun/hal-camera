package dev.halcamera.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat

/** Familiar actions with a centered symbol, a full touch target and an explicit accessible name. */
class IconButton(
    context: Context,
    @DrawableRes iconRes: Int,
    label: String,
    private val dark: Boolean = true,
    filled: Boolean = false,
    action: () -> Unit
) : Button(context) {
    private var icon: Drawable? = null

    init {
        text = ""
        minWidth = Look.dp(context, 48)
        minimumWidth = minWidth
        minHeight = Look.dp(context, 48)
        minimumHeight = minHeight
        setPadding(0, 0, 0, 0)
        stateListAnimator = null
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (filled) { if (dark) Look.expertTile else Look.canvas } else Color.TRANSPARENT)
        }
        val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        background = RippleDrawable(ColorStateList.valueOf(if (dark) 0x40FFFFFF else 0x18000000), circle, mask)
        backgroundTintList = null
        setIcon(iconRes, label)
        setOnClickListener { action() }
    }

    fun setIcon(@DrawableRes iconRes: Int, label: String) {
        icon = ContextCompat.getDrawable(context, iconRes)?.mutate()
        contentDescription = label
        tooltipText = label
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        icon?.let {
            val edge = Look.dp(context, 24).coerceAtMost(width).coerceAtMost(height)
            val left = (width - edge) / 2
            val top = (height - edge) / 2
            it.setBounds(left, top, left + edge, top + edge)
            it.setTint(if (isSelected) { if (dark) Look.primaryOnDark else Look.primary } else if (dark) Look.onDark else Look.ink)
            it.alpha = if (isEnabled) 255 else 90
            it.draw(canvas)
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}
