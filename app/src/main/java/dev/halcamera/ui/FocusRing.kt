package dev.halcamera.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import dev.halcamera.camera.TouchMeter
import dev.halcamera.camera.TouchMetering
import dev.halcamera.camera.TouchPhase

/**
 * Tap-to-focus over the Live preview (#168). A tap asks the current engine to meter there and draws a ring that
 * follows that tap's outcome: white and shrinking while AF scans, green once focused, red when AF gave up,
 * white for exposure-only metering, gone when the engine returns to whole-frame metering.
 *
 * It sits in the preview host, so every camera switch replaces it along with the preview; a tap token makes a
 * late callback from an earlier tap do nothing. Controls drawn above it keep their own touches.
 */
class FocusRing(context: Context, private val engine: () -> TouchMetering?) : View(context) {
    private var token = 0
    private var phase: TouchPhase? = null
    private var cx = 0f
    private var cy = 0f
    private var scale = 1f
    private var message: String? = null
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Look.onDark; textAlign = Paint.Align.CENTER; textSize = 13f * resources.displayMetrics.scaledDensity
        setShadowLayer(4f, 0f, 1f, Look.cameraSurface)
    }
    private val hide = Runnable { phase = null; message = null; invalidate() }
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { tap(e.x, e.y); return true }
    })

    init {
        // TalkBack users would find a full-screen unlabeled target; the AF and AE buttons cover their needs.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = detector.onTouchEvent(event)

    private fun tap(x: Float, y: Float) {
        val t = ++token
        removeCallbacks(hide); animate().cancel(); alpha = 1f
        cx = x; cy = y; message = null; phase = null; invalidate()
        val accepted = engine()?.meterAt(x, y) { p -> if (t == token) show(p) } == true
        if (!accepted) {
            phase = null; message = "터치 초점을 지원하지 않거나 카메라가 준비 중입니다"
            invalidate(); postDelayed(hide, 1500)
            return
        }
        // Covers a tap the engine accepted but never answered, such as one that raced a session rebuild.
        postDelayed(hide, TouchMeter.SCAN_TIMEOUT_MS + TouchMeter.HOLD_MS + 1000)
    }

    private fun show(next: TouchPhase) {
        if (next == TouchPhase.DONE) { removeCallbacks(hide); animate().alpha(0f).setDuration(250).withEndAction { hide.run(); alpha = 1f }; return }
        animate().cancel(); alpha = 1f
        phase = next
        if (next == TouchPhase.SCANNING || next == TouchPhase.METERED) {
            ValueAnimator.ofFloat(1.3f, 1f).setDuration(180).apply { addUpdateListener { scale = it.animatedValue as Float; invalidate() } }.start()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        message?.let {
            val half = text.measureText(it) / 2
            canvas.drawText(it, cx.coerceIn(half, (width - half).coerceAtLeast(half)), cy, text); return
        }
        val p = phase ?: return
        ring.color = when (p) { TouchPhase.FOCUSED -> Look.statusPass; TouchPhase.FAILED -> Look.statusFail; else -> Look.onDark }
        ring.strokeWidth = Look.dp(context, if (p == TouchPhase.SCANNING) 1 else 2).toFloat()
        canvas.drawCircle(cx, cy, Look.dp(context, 36) * scale, ring)
    }
}
