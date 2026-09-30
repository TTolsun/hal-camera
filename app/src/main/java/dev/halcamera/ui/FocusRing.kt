package dev.halcamera.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import dev.halcamera.camera.AeRelock
import dev.halcamera.camera.TouchMeter
import dev.halcamera.camera.TouchMetering
import dev.halcamera.camera.TouchPhase

/**
 * Touch focus and exposure over the Live preview (#168), laid out like the Samsung camera's photo mode.
 *
 * - A tap focuses there. A square with corner marks follows the outcome: white while AF scans, green once focused,
 *   red when AF gave up, gone when AF returns to the whole frame.
 * - A long press meters exposure there. A circle appears; once AE has metered the point, [lockExposure] takes the
 *   AE lock through the Live controls and a padlock sits in the circle's top gap. The circle dims after a while and
 *   stays until a tap, another long press or the AE button releases the lock.
 * - A tap while a long-pressed lock is held releases that lock first, as in the Samsung camera. A lock taken with
 *   the AE button is left alone.
 *
 * It sits in the preview host, so every camera switch replaces it along with the preview; a token per mark makes
 * a late callback from an earlier touch do nothing. Controls drawn above it keep their own touches.
 */
class FocusRing(
    context: Context,
    private val engine: () -> TouchMetering?,
    /** Takes or releases the AE lock; false when the camera or engine has none. */
    private val lockExposure: (Boolean) -> Boolean,
) : View(context) {
    private class Mark { var token = 0; var phase: TouchPhase? = null; var x = 0f; var y = 0f; var scale = 1f; var dim = false }

    private val focus = Mark()
    private val exposure = Mark()
    /** The AE lock is held because of a long press, so a tap releases it. */
    private var exposureLocked = false
    private var message: String? = null
    private var messageX = 0f
    private var messageY = 0f
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Look.onDark; textAlign = Paint.Align.CENTER; textSize = 13f * resources.displayMetrics.scaledDensity
        setShadowLayer(4f, 0f, 1f, Look.cameraSurface)
    }
    private val hideFocus = Runnable { focus.phase = null; invalidate() }
    private val hideExposure = Runnable { if (exposure.phase == TouchPhase.SCANNING) { exposure.phase = null; invalidate() } }
    private val dimExposure = Runnable { exposure.dim = true; invalidate() }
    private val hideMessage = Runnable { message = null; invalidate() }
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { tap(e.x, e.y); return true }
        override fun onLongPress(e: MotionEvent) { press(e.x, e.y) }
    })

    init {
        // TalkBack users would find a full-screen unlabeled target; the AF and AE buttons cover their needs.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = detector.onTouchEvent(event)

    private fun tap(x: Float, y: Float) {
        releaseExposure()
        val t = start(focus, x, y)
        removeCallbacks(hideFocus)
        if (engine()?.meterAt(x, y, false) { p -> if (t == focus.token) showFocus(p) } != true) {
            focus.phase = null; say("Tap focus is unavailable. The lens is not taking requests.", x, y); return
        }
        // Covers a tap the engine accepted but never answered, such as one that raced a session rebuild.
        postDelayed(hideFocus, TouchMeter.SCAN_TIMEOUT_MS + TouchMeter.HOLD_MS + 1000)
    }

    private fun press(x: Float, y: Float) {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        releaseExposure()
        // A lock taken with the AE button would keep AE from metering the new point, so a long press always re-meters.
        lockExposure(false)
        focus.token++; focus.phase = null
        val t = start(exposure, x, y)
        if (engine()?.meterAt(x, y, true) { p -> if (t == exposure.token) showExposure(p) } != true) {
            exposure.phase = null; say("Tap exposure is unavailable. The light meter is off duty.", x, y); return
        }
        postDelayed(hideExposure, AeRelock.TIMEOUT_MS + 1000)
    }

    /** Releases a lock this view took; the engine then reports DONE for the old circle, which its token ignores. */
    private fun releaseExposure() {
        exposure.token++; exposure.phase = null
        removeCallbacks(dimExposure); removeCallbacks(hideExposure)
        if (exposureLocked) { exposureLocked = false; lockExposure(false) }
    }

    private fun start(mark: Mark, x: Float, y: Float): Int {
        removeCallbacks(hideMessage); message = null
        mark.token++; mark.x = x; mark.y = y; mark.phase = null; mark.dim = false
        invalidate()
        return mark.token
    }

    private fun showFocus(next: TouchPhase) {
        if (next == TouchPhase.DONE) { removeCallbacks(hideFocus); focus.phase = null; invalidate(); return }
        focus.phase = next
        if (next == TouchPhase.SCANNING) pop(focus)
        invalidate()
    }

    private fun showExposure(next: TouchPhase) {
        removeCallbacks(hideExposure)
        when (next) {
            TouchPhase.DONE -> { removeCallbacks(dimExposure); exposure.phase = null; exposureLocked = false }
            TouchPhase.METERED -> {
                exposure.phase = next
                exposureLocked = lockExposure(true)
                postDelayed(dimExposure, 3000)
            }
            else -> { exposure.phase = next; pop(exposure) }
        }
        invalidate()
    }

    private fun pop(mark: Mark) {
        ValueAnimator.ofFloat(1.3f, 1f).setDuration(180).apply { addUpdateListener { mark.scale = it.animatedValue as Float; invalidate() } }.start()
    }

    private fun say(line: String, x: Float, y: Float) {
        message = line; messageX = x; messageY = y
        invalidate(); postDelayed(hideMessage, 1500)
    }

    override fun onDraw(canvas: Canvas) {
        focus.phase?.let { drawSquare(canvas, it) }
        exposure.phase?.let { drawCircle(canvas, it) }
        message?.let {
            val half = text.measureText(it) / 2
            canvas.drawText(it, messageX.coerceIn(half, (width - half).coerceAtLeast(half)), messageY, text)
        }
    }

    /** The AF mark: a square drawn as four corners, like the Samsung camera's focus frame. */
    private fun drawSquare(canvas: Canvas, p: TouchPhase) {
        stroke.color = when (p) { TouchPhase.FOCUSED -> Look.statusPass; TouchPhase.FAILED -> Look.statusFail; else -> Look.onDark }
        stroke.strokeWidth = dp(if (p == TouchPhase.SCANNING) 1.5f else 2.5f)
        val h = dp(32f) * focus.scale
        val c = h * 0.35f
        val l = focus.x - h; val t = focus.y - h; val r = focus.x + h; val b = focus.y + h
        for ((x, y, dx, dy) in listOf(Quad(l, t, 1f, 1f), Quad(r, t, -1f, 1f), Quad(l, b, 1f, -1f), Quad(r, b, -1f, -1f))) {
            canvas.drawLine(x, y, x + dx * c, y, stroke)
            canvas.drawLine(x, y, x, y + dy * c, stroke)
        }
    }

    /** The AE mark: a circle, with a padlock in a gap at the top once the lock is held. */
    private fun drawCircle(canvas: Canvas, p: TouchPhase) {
        val alpha = if (exposure.dim) 140 else 255
        stroke.color = Look.onDark; stroke.alpha = alpha
        stroke.strokeWidth = dp(if (p == TouchPhase.SCANNING) 1.5f else 2f)
        val r = dp(32f) * exposure.scale
        val oval = RectF(exposure.x - r, exposure.y - r, exposure.x + r, exposure.y + r)
        if (!exposureLocked) { canvas.drawCircle(exposure.x, exposure.y, r, stroke); stroke.alpha = 255; return }
        canvas.drawArc(oval, -60f, 300f, false, stroke)
        // Padlock: a filled body and a shackle arc above it, centred on the gap.
        val cx = exposure.x; val top = exposure.y - r
        val w = dp(10f); val bh = dp(8f)
        fill.color = Look.onDark; fill.alpha = alpha
        canvas.drawRoundRect(RectF(cx - w / 2, top - bh / 4, cx + w / 2, top + bh * 3 / 4), dp(1.5f), dp(1.5f), fill)
        stroke.strokeWidth = dp(1.5f)
        canvas.drawArc(RectF(cx - w / 3, top - bh * 3 / 4, cx + w / 3, top + bh / 4), 180f, 180f, false, stroke)
        stroke.alpha = 255; fill.alpha = 255
    }

    private data class Quad(val x: Float, val y: Float, val dx: Float, val dy: Float)

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
