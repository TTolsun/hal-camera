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
import android.widget.Button
import androidx.core.view.ViewCompat

/** Camera shutter with a stable touch target; the caller owns size, availability and capture actions. */
class ShutterButton(context: Context) : Button(context) {
    private val shutter = ShutterDrawable()

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

    fun setCaptureState(videoMode: Boolean, recording: Boolean) {
        shutter.videoMode = videoMode
        shutter.recording = recording
        shutter.invalidateSelf()
        contentDescription = when {
            recording -> "동영상 녹화 중지"
            videoMode -> "소리와 함께 동영상 녹화 시작"
            else -> "YUV와 JPEG 사진 두 장 촬영"
        }
        ViewCompat.setStateDescription(this, if (recording) "녹화 중" else null)
    }

    private class ShutterDrawable : Drawable() {
        var videoMode = false
        var recording = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val square = RectF()
        private var drawableAlpha = 255

        override fun draw(canvas: Canvas) {
            val size = bounds.width().coerceAtMost(bounds.height()).toFloat()
            if (size <= 0f) return
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            val unit = size / 72f
            if (!videoMode && !recording) {
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
            paint.color = Look.statusFail
            paint.alpha = drawableAlpha
            if (recording) {
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
