package dev.halcamera.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import dev.halcamera.camera.DepthFrame
import dev.halcamera.camera.LiveSize
import java.util.Locale

/** Raw sensor depth, fitted without cropping; rotation/mirror are applied only for display. */
@android.annotation.SuppressLint("ViewConstructor")
internal class DepthMapView(context: Context, private val sourceSize: LiveSize,
                            private val sensorOrientation: Int, private val front: Boolean) : View(context) {
    private var bitmap: Bitmap? = null
    private var summary = "Waiting for DEPTH16 frames…"
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Look.onDark; textSize = Look.dp(context, 12).toFloat(); textAlign = Paint.Align.CENTER }
    private val imagePaint = Paint().apply { isFilterBitmap = false }

    init { setBackgroundColor(Color.BLACK); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    fun setChromeInsets(top: Int, bottom: Int) {
        if (paddingTop != top || paddingBottom != bottom) setPadding(Look.dp(context, 16), top, Look.dp(context, 16), bottom)
    }

    fun bind(frame: DepthFrame) {
        val image = bitmap?.takeIf { it.width == frame.width && it.height == frame.height }
            ?: createBitmap(frame.width, frame.height).also { bitmap = it }
        image.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        val center = frame.centerMm?.let { String.format(Locale.US, "Center %.3f m", it / 1000.0) } ?: "Center unavailable"
        summary = "$center · Valid ${frame.validPercent}%"
        contentDescription = "Depth map $sourceSize. $summary. Near is bright, far is dark. Black means invalid or confidence below 50 percent."
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val top = paddingTop.toFloat()
        val availableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(1).toFloat()
        val centerY = top + availableHeight / 2f
        val textSpace = Look.dp(context, 80).toFloat()
        bitmap?.let { image ->
            @Suppress("DEPRECATION")
            val degrees = (display?.rotation ?: 0) * 90
            val rotation = (sensorOrientation + (if (front) degrees else -degrees) + 360) % 360
            val swapped = rotation % 180 != 0
            val imageWidth = if (swapped) image.height else image.width
            val imageHeight = if (swapped) image.width else image.height
            val scale = minOf((width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat() / imageWidth,
                (availableHeight - textSpace).coerceAtLeast(1f) / imageHeight)
            canvas.withTranslation(width / 2f, centerY - textSpace / 2) {
                if (front) scale(-1f, 1f)
                rotate(rotation.toFloat())
                scale(scale, scale)
                drawBitmap(image, -image.width / 2f, -image.height / 2f, imagePaint)
            }
        }
        val baseline = top + availableHeight - Look.dp(context, 56)
        canvas.drawText("DEPTH16 · $sourceSize · Depth only", width / 2f, baseline, paint)
        canvas.drawText(summary, width / 2f, baseline + Look.dp(context, 20), paint)
        canvas.drawText("Near: bright · Far: dark · Black: invalid", width / 2f, baseline + Look.dp(context, 40), paint)
        canvas.drawText("0–8.191 m · Confidence ≥ 50%", width / 2f, baseline + Look.dp(context, 60), paint)
    }

    override fun onDetachedFromWindow() {
        bitmap?.recycle(); bitmap = null
        super.onDetachedFromWindow()
    }
}
