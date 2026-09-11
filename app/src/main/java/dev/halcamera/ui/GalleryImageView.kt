package dev.halcamera.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.min

/** Fits the entire photo, with pinch/double-tap zoom and paging only at the fitted scale. */
class GalleryImageView(context: Context, private val page: (Int) -> Unit) : ImageView(context) {
    private val transform = Matrix()
    private var zoom = 1f
    private var fittedScale = 1f
    private var multiplePointers = false
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (zoom * detector.scaleFactor).coerceIn(1f, 4f)
            transform.postScale(next / zoom, next / zoom, detector.focusX, detector.focusY)
            zoom = next
            constrain()
            return true
        }
    })
    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean = performClick()
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.01f) fit() else {
                zoom = 2.5f
                transform.postScale(zoom, zoom, e.x, e.y)
                constrain()
            }
            return true
        }
        override fun onScroll(first: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (zoom > 1.01f && !scale.isInProgress) {
                transform.postTranslate(-distanceX, -distanceY)
                constrain()
            }
            return true
        }
        override fun onFling(first: MotionEvent?, last: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (first != null && !multiplePointers && zoom <= 1.01f &&
                abs(last.x - first.x) > 48 * resources.displayMetrics.density &&
                abs(last.x - first.x) > abs(last.y - first.y) && abs(velocityX) > abs(velocityY)
            ) {
                page(if (last.x < first.x) 1 else -1)
                return true
            }
            return false
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun setImageBitmap(bitmap: Bitmap?) {
        super.setImageBitmap(bitmap)
        fit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    fun toggleZoom() {
        if (zoom > 1.01f) fit() else {
            zoom = 2.5f
            transform.postScale(zoom, zoom, width / 2f, height / 2f)
            constrain()
        }
    }

    private fun fit() {
        val image = drawable ?: return
        if (width == 0 || height == 0 || image.intrinsicWidth <= 0 || image.intrinsicHeight <= 0) return
        fittedScale = min(width.toFloat() / image.intrinsicWidth, height.toFloat() / image.intrinsicHeight)
        zoom = 1f
        transform.setScale(fittedScale, fittedScale)
        transform.postTranslate((width - image.intrinsicWidth * fittedScale) / 2f, (height - image.intrinsicHeight * fittedScale) / 2f)
        imageMatrix = transform
    }

    private fun constrain() {
        val image = drawable ?: return
        val bounds = RectF(0f, 0f, image.intrinsicWidth.toFloat(), image.intrinsicHeight.toFloat())
        transform.mapRect(bounds)
        fun offset(start: Float, end: Float, limit: Int): Float = when {
            end - start <= limit -> (limit - start - end) / 2f
            start > 0 -> -start
            end < limit -> limit - end
            else -> 0f
        }
        transform.postTranslate(offset(bounds.left, bounds.right, width), offset(bounds.top, bounds.bottom, height))
        imageMatrix = transform
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) multiplePointers = false
        if (event.pointerCount > 1) multiplePointers = true
        scale.onTouchEvent(event)
        gesture.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
