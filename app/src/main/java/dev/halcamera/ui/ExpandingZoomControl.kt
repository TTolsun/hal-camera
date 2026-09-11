package dev.halcamera.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import androidx.core.view.ViewCompat
import java.util.Locale
import kotlin.math.roundToInt

/** A fixed-height zoom rail that opens around the selected ratio and folds back into one circle. */
class ExpandingZoomControl(context: Context, private val onSelect: (Float) -> Unit) : ViewGroup(context) {
    private val accessibility = context.getSystemService(AccessibilityManager::class.java)
    private var ratios = emptyList<Float>()
    private var selected = 1f
    private var expanded = false
    private var touching = false
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private val fold = Runnable { collapse() }

    init {
        background = Look.pill(context, Color.argb(150, 39, 39, 41))
        clipChildren = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setChoices(values: List<Float>, value: Float) {
        if (values != ratios) {
            collapse(animate = false)
            ratios = values.toList()
            removeAllViews()
            ratios.forEach { ratio ->
                addView(Button(context).apply {
                    isAllCaps = false
                    textSize = 12f
                    minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                    setPadding(0, 0, 0, 0)
                    setOnClickListener {
                        if (!this@ExpandingZoomControl.isEnabled) return@setOnClickListener
                        if (!expanded) {
                            expanded = ratios.size > 1
                            renderButtons()
                            animateTo(if (expanded) 1f else 0f)
                        } else {
                            selected = ratio
                            renderButtons()
                            onSelect(ratio)
                            // Screen readers can close explicitly without racing an automatic timeout.
                            if (accessibility.isTouchExplorationEnabled) collapse()
                        }
                        scheduleFold()
                    }
                    setOnFocusChangeListener { _, focused -> if (focused) scheduleFold() }
                }, LayoutParams(dp(48), dp(48)))
            }
        }
        selected = value
        renderButtons()
        requestLayout()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) collapse(animate = false)
        alpha = if (enabled) 1f else 0.4f
        renderButtons()
    }

    fun collapse(animate: Boolean = true) {
        removeCallbacks(fold)
        expanded = false
        animateTo(0f, animate)
        renderButtons()
    }

    private fun scheduleFold() {
        removeCallbacks(fold)
        if (!expanded || !isEnabled || touching || accessibility.isTouchExplorationEnabled) return
        val delay = if (Build.VERSION.SDK_INT >= 29) {
            accessibility.getRecommendedTimeoutMillis(3000, AccessibilityManager.FLAG_CONTENT_CONTROLS)
        } else 3000
        postDelayed(fold, delay.toLong())
    }

    /** Called by the viewport so a horizontal drag cannot race the collapse timer. */
    fun setTouchInProgress(inProgress: Boolean) {
        touching = inProgress
        if (touching) removeCallbacks(fold) else scheduleFold()
    }

    private fun animateTo(target: Float, animate: Boolean = true) {
        animator?.cancel()
        animator = null
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || progress == target) {
            progress = target
            renderVisibility()
            requestLayout()
            return
        }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 220
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                renderVisibility()
                requestLayout()
            }
            start()
        }
    }

    private fun renderButtons() {
        ratios.forEachIndexed { index, ratio ->
            val button = getChildAt(index) as Button
            val active = ratio == selected
            val number = if (ratio == ratio.toInt().toFloat()) "${ratio.toInt()}"
                else "%.1f".format(Locale.US, ratio).removePrefix("0")
            button.text = if (active) "${number}×" else number
            button.isSelected = active
            button.setTextColor(if (active) Look.expertTile else Look.onDark)
            val circle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (active) Look.primaryOnDark else Color.TRANSPARENT)
            }
            val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            // The visible circle is 40dp; each button still has a 48dp touch target.
            button.background = InsetDrawable(RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), circle, mask), dp(4))
            button.contentDescription = "${ratio}배 줌" + if (!expanded && active && ratios.size > 1) ", 배율 펼치기" else ""
            ViewCompat.setStateDescription(button, if (active) "선택됨" else null)
        }
        renderVisibility()
    }

    private fun renderVisibility() {
        ratios.forEachIndexed { index, ratio ->
            val button = getChildAt(index)
            val active = ratio == selected
            val available = active || (expanded && progress == 1f)
            button.visibility = if (active || progress > 0f) VISIBLE else INVISIBLE
            button.alpha = if (active) 1f else progress
            button.isEnabled = isEnabled && available
            button.importantForAccessibility = if (available) IMPORTANT_FOR_ACCESSIBILITY_YES else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            if (!available && button.hasFocus()) (getChildAt(ratios.indexOf(selected)))?.requestFocus()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = dp(56) + dp(48) * (ratios.size - 1).coerceAtLeast(0) * progress
        setMeasuredDimension(resolveSize(wanted.roundToInt(), widthMeasureSpec), resolveSize(dp(52), heightMeasureSpec))
        val size = MeasureSpec.makeMeasureSpec(dp(48), MeasureSpec.EXACTLY)
        for (index in 0 until childCount) getChildAt(index).measure(size, size)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val center = (width - dp(48)) / 2f
        val middle = (ratios.size - 1) / 2f
        for (index in 0 until childCount) {
            val x = (center + (index - middle) * dp(48) * progress).roundToInt()
            val y = (height - dp(48)) / 2
            getChildAt(index).layout(x, y, x + dp(48), y + dp(48))
        }
    }

    override fun onDetachedFromWindow() {
        touching = false
        collapse(animate = false)
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int) = Look.dp(context, value)
}
