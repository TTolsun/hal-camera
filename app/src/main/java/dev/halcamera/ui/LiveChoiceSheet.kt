package dev.halcamera.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import dev.halcamera.R

/** One-tap choices over Live, with the current selection visible and no confirmation step. */
internal object LiveChoiceSheet {
    data class Choice(val title: String, val detail: String = "")

    fun show(context: Context, title: String, choices: List<Choice>, selected: Int,
             clear: (() -> Unit)? = null, choose: (Int) -> Unit): AlertDialog {
        fun dp(value: Int) = Look.dp(context,value)
        val dialog = AlertDialog.Builder(context).create()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16),dp(8),dp(16),dp(16))
        }
        val header = Look.row(context).apply {
            addView(Look.text(context,title,22,Look.onDark,true).apply {
                ViewCompat.setAccessibilityHeading(this,true)
            },LinearLayout.LayoutParams(0,-2,1f))
            addView(IconButton(context,R.drawable.ic_action_close,"Close $title",dark=true) { dialog.dismiss() },
                LinearLayout.LayoutParams(dp(48),dp(48)))
        }
        root.addView(header,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        val list = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        choices.forEachIndexed { index, choice ->
            val checked = selected == index
            val row = Look.row(context).apply {
                minimumHeight=dp(64); setPadding(dp(16),dp(12),dp(12),dp(12))
                val shape=Look.cardBackground(context,Look.cameraCard,if (checked) Look.onDark else Look.cameraCard)
                    .apply { cornerRadius=dp(16).toFloat() }
                background=RippleDrawable(ColorStateList.valueOf(Look.cameraOutline),shape,null)
                isFocusable=true; isSelected=checked
                contentDescription=listOf(choice.title,choice.detail).filter { it.isNotEmpty() }.joinToString(", ")
                ViewCompat.setStateDescription(this,if (checked) "Selected" else "Not selected")
                setOnClickListener { dialog.dismiss(); if (!checked) choose(index) }
            }
            val labels=LinearLayout(context).apply {
                orientation=LinearLayout.VERTICAL
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(Look.text(context,choice.title,16,Look.onDark,checked))
                if (choice.detail.isNotEmpty()) addView(Look.text(context,choice.detail,12,Look.onDarkMuted),
                    LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4) })
            }
            row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            row.addView(Look.text(context,if (checked) "✓" else "",20,Look.onDark).apply {
                gravity=Gravity.CENTER; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LinearLayout.LayoutParams(dp(32),-2))
            list.addView(row,LinearLayout.LayoutParams(-1,-2).apply { if (index>0) topMargin=dp(8) })
        }
        val scroll=object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
                val limit=(resources.displayMetrics.heightPixels*.60f).toInt()
                super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(limit,MeasureSpec.AT_MOST))
            }
        }.apply { addView(list); isFillViewport=false; clipToPadding=false }
        root.addView(scroll,LinearLayout.LayoutParams(-1,-2))
        if (clear!=null) root.addView(Look.galleryButton(context,"Turn off PIP") { dialog.dismiss(); clear() },
            LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
        dialog.setView(root,0,0,0,0)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Look.cameraSurface); cornerRadius=dp(24).toFloat() })
            setDimAmount(.25f)
            setGravity(Gravity.BOTTOM)
            attributes=attributes.apply { y=dp(16) }
            setLayout(minOf(dp(480),context.resources.displayMetrics.widthPixels-dp(24)),-2)
        }
        return dialog
    }
}
