package dev.halcamera.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.widget.Button
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat

/** Camera shutter with a stable touch target; the caller owns size, availability and capture actions. */
class ShutterButton(context: Context) : Button(context) {
    private val shutter = ShutterDrawable()
    private var accessibleBurst: (() -> Unit)? = null
    private var burstAccessible: Boolean? = null

    init {
        text = ""
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        stateListAnimator = null
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        background = RippleDrawable(ColorStateList.valueOf(0x66FFFFFF), shutter, mask)
        backgroundTintList = null
        setCaptureState(videoMode = false, recording = false)
    }

    /** Only the touch that started a held burst releases it; an accessibility start uses the stop click. */
    fun bindBurstInput(available: () -> Boolean, permission: (() -> Unit) -> Unit, hold: () -> Unit, release: () -> Unit) {
        var holding = false
        setOnLongClickListener { available().also { allowed ->
            if (allowed) permission { if (isPressed && available()) { holding = true; hold() } }
        } }
        accessibleBurst = { if (available()) permission { if (available()) hold() } }
        setOnTouchListener { _, event ->
            if (holding && (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)) {
                holding = false
                release()
            }
            false
        }
    }

    fun setCaptureState(videoMode: Boolean, recording: Boolean, sequence: Boolean = false, bracket: Boolean = false, state: String? = null) {
        shutter.videoMode = videoMode
        shutter.recording = recording
        shutter.sequence = sequence
        shutter.invalidateSelf()
        contentDescription = when {
            sequence -> if (bracket) "Stop bracketing" else "Stop burst"
            recording -> "Stop recording"
            videoMode -> "Start recording with audio"
            bracket -> "Take 3 exposure bracket photos"
            else -> "Take a photo"
        }
        ViewCompat.setStateDescription(this, state ?: if (recording) "Recording" else null)
        val allowBurst = accessibleBurst != null && !videoMode && !sequence && !bracket
        isLongClickable = allowBurst
        if (burstAccessible != allowBurst) {
            burstAccessible = allowBurst
            if (allowBurst) ViewCompat.replaceAccessibilityAction(this, AccessibilityActionCompat.ACTION_LONG_CLICK, "Start burst") { _, _ ->
                accessibleBurst?.invoke(); true
            } else ViewCompat.removeAccessibilityAction(this, AccessibilityActionCompat.ACTION_LONG_CLICK.id)
        }
    }

    private class ShutterDrawable : Drawable() {
        var videoMode = false
        var recording = false
        var sequence = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val square = RectF()
        private var drawableAlpha = 255

        override fun draw(canvas: Canvas) {
            val size = bounds.width().coerceAtMost(bounds.height()).toFloat()
            if (size <= 0f) return
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            val unit = size / 72f
            if (!videoMode && !recording && !sequence) {
                paint.style = Paint.Style.FILL
                paint.color = Look.onDark
                paint.alpha = drawableAlpha
                canvas.drawCircle(cx, cy, 32f * unit, paint)
                return
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * unit
            paint.color = Look.onDark
            paint.alpha = drawableAlpha
            canvas.drawCircle(cx, cy, size / 2f - paint.strokeWidth / 2f, paint)

            paint.style = Paint.Style.FILL
            paint.color = if (sequence) Look.onDark else Look.statusFail
            paint.alpha = drawableAlpha
            if (recording || sequence) {
                val half = 16f * unit
                square.set(cx - half, cy - half, cx + half, cy + half)
                canvas.drawRoundRect(square, 4f * unit, 4f * unit, paint)
            } else {
                canvas.drawCircle(cx, cy, 28f * unit, paint)
            }
        }

        override fun setAlpha(alpha: Int) {
            drawableAlpha = alpha.coerceIn(0, 255)
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
