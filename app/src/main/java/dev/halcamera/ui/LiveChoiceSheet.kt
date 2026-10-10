package dev.halcamera.ui

import android.app.AlertDialog
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.TextureView
import android.view.SurfaceView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.children
import androidx.core.view.descendants
import androidx.core.view.doOnLayout
import dev.halcamera.R

/** One-tap choices over Live, with the current selection visible and no confirmation step. */
internal object LiveChoiceSheet {
    enum class Edge { TOP, BOTTOM }
    data class Choice(val title: String, val detail: String = "")

    fun show(context: Context, title: String, choices: List<Choice> = emptyList(), selected: Int = -1,
             clear: (() -> Unit)? = null, edge: Edge = Edge.BOTTOM,
             content: View? = null, actionLabel: String? = null, confirm: (() -> Boolean)? = null,
             scrollPosition: Int? = null, saveScrollPosition: ((Int) -> Unit)? = null,
             choose: (Int) -> Unit = {}): AlertDialog {
        fun dp(value: Int) = Look.dp(context,value)
        val dialog = AlertDialog.Builder(context).create()
        // Keep the opposite controls visible but subdued behind the modal window.
        val hidden = mutableListOf<Pair<View,Float>>()
        fun containsPreview(view: View): Boolean = view is TextureView || view is SurfaceView ||
            (view is ViewGroup && view.descendants.any { it is TextureView || it is SurfaceView })
        fun hideChrome(group: ViewGroup) {
            group.children.forEach { child ->
                if (!containsPreview(child)) {
                    hidden += child to child.alpha
                    val position=IntArray(2).also(child::getLocationOnScreen)
                    val middle=context.resources.displayMetrics.heightPixels/2
                    val covered=if (edge == Edge.TOP) position[1]<middle else position[1]+child.height>middle
                    child.alpha = if (covered) 0f else child.alpha*.45f
                } else if (child is ViewGroup) hideChrome(child)
            }
        }
        fun restoreChrome() { hidden.forEach { (view,alpha) -> view.alpha=alpha }; hidden.clear() }
        fun dismiss() { restoreChrome(); dialog.dismiss() }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16),dp(8),dp(16),dp(16))
        }
        val header = Look.row(context).apply {
            addView(Look.text(context,title,20,Look.onDark,true).apply {
                ViewCompat.setAccessibilityHeading(this,true)
            },LinearLayout.LayoutParams(0,-2,1f))
            addView(IconButton(context,R.drawable.ic_action_close,"Close $title",dark=true) { dismiss() },
                LinearLayout.LayoutParams(dp(48),dp(48)))
        }
        root.addView(header,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        val list = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        content?.let { list.addView(it,LinearLayout.LayoutParams(-1,-2)) }
        choices.forEachIndexed { index, choice ->
            val checked = selected == index
            val row = Look.row(context).apply {
                minimumHeight=dp(64); setPadding(dp(16),dp(12),dp(12),dp(12))
                val shape=Look.cardBackground(context,Look.cameraControlGlass,if (checked) Look.onDark else Color.TRANSPARENT)
                    .apply { cornerRadius=dp(16).toFloat() }
                background=RippleDrawable(ColorStateList.valueOf(Look.cameraOutline),shape,null)
                isFocusable=true; isSelected=checked
                contentDescription=listOf(choice.title,choice.detail).filter { it.isNotEmpty() }.joinToString(", ")
                ViewCompat.setStateDescription(this,if (checked) "Selected" else "Not selected")
                setOnClickListener { dismiss(); if (!checked) choose(index) }
            }
            val labels=LinearLayout(context).apply {
                orientation=LinearLayout.VERTICAL
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(Look.text(context,choice.title,14,Look.onDark,checked))
                if (choice.detail.isNotEmpty()) addView(Look.text(context,choice.detail,10,Look.onDarkMuted),
                    LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4) })
            }
            row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            row.addView(Look.text(context,if (checked) "✓" else "",18,Look.onDark).apply {
                gravity=Gravity.CENTER; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LinearLayout.LayoutParams(dp(32),-2))
            list.addView(row,LinearLayout.LayoutParams(-1,-2).apply { if (index>0) topMargin=dp(8) })
        }
        val scroll=object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
                val limit=(resources.displayMetrics.heightPixels*if (edge == Edge.TOP) .40f else .60f).toInt()
                super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(limit,MeasureSpec.AT_MOST))
            }
        }.apply { addView(list); isFillViewport=false; clipToPadding=false }
        dialog.setOnDismissListener { saveScrollPosition?.invoke(scroll.scrollY); restoreChrome() }
        root.addView(scroll,LinearLayout.LayoutParams(-1,-2))
        if (clear!=null) root.addView(Look.galleryButton(context,"Turn off PIP") { dismiss(); clear() }.apply { textSize=13f },
            LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
        if (actionLabel!=null && confirm!=null) root.addView(Look.galleryButton(context,actionLabel) {
            if (confirm()) dismiss()
        }.apply { textSize=12f },LinearLayout.LayoutParams(-1,dp(48)).apply { topMargin=dp(12) })
        dialog.setView(root,0,0,0,0)
        dialog.window?.apply {
            setWindowAnimations(if (edge == Edge.TOP) R.style.LiveSheetFromTop else R.style.LiveSheetFromBottom)
            setGravity(if (edge == Edge.TOP) Gravity.TOP else Gravity.BOTTOM)
        }
        (context as? Activity)?.findViewById<ViewGroup>(android.R.id.content)?.let {
            if (containsPreview(it)) hideChrome(it)
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Look.cameraControlGlass); cornerRadius=dp(24).toFloat() })
            if (Build.VERSION.SDK_INT >= 31) setBackgroundBlurRadius(dp(24))
            setDimAmount(.25f)
            attributes=attributes.apply { y=dp(16) }
            setLayout(minOf(dp(480),context.resources.displayMetrics.widthPixels-dp(24)),-2)
        }
        if (saveScrollPosition != null) scroll.doOnLayout {
            val selectedRow = list.getChildAt(selected + if (content == null) 0 else 1)
            scroll.scrollTo(0,scrollPosition ?: selectedRow?.top ?: 0)
        }
        return dialog
    }
}
