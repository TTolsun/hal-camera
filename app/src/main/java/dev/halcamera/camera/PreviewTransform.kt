package dev.halcamera.camera

import android.graphics.Matrix
import android.graphics.RectF
import android.util.Size
import android.view.Surface
import android.view.TextureView

/**
 * Fills the TextureView with a preview buffer of [size] without stretching it, as the stock camera does.
 *
 * The camera pipeline already rotates buffers (and mirrors front cameras) for the device's natural orientation,
 * so in portrait a 1280x720 buffer is shown as 720x1280 stretched to the view. Only undo the stretch and fill-crop.
 * In landscape the display itself is rotated, so follow the Camera2Basic sample: map, scale, then rotate.
 * TextureView.naturalPoint inverts this matrix to map a touch back onto the picture.
 */
@Suppress("DEPRECATION")
fun TextureView.fitPreview(size: Size) {
    if (width == 0) return
    val rotation = display?.rotation ?: Surface.ROTATION_0
    val w = width.toFloat(); val h = height.toFloat()
    val cx = w / 2; val cy = h / 2
    val matrix = Matrix()
    if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
        val viewRect = RectF(0f, 0f, w, h)
        val bufferRect = RectF(0f, 0f, size.height.toFloat(), size.width.toFloat())
        bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = maxOf(h / size.height, w / size.width)
        matrix.postScale(scale, scale, cx, cy)
        matrix.postRotate(90f * (rotation - 2), cx, cy)
    } else {
        val contentW = size.height.toFloat(); val contentH = size.width.toFloat()
        val scale = maxOf(w / contentW, h / contentH)
        matrix.setScale(contentW * scale / w, contentH * scale / h, cx, cy)
        if (rotation == Surface.ROTATION_180) matrix.postRotate(180f, cx, cy)
    }
    setTransform(matrix)
}
