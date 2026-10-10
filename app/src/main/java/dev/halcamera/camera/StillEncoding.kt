package dev.halcamera.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

/** A YUV frame copied out of its Image as NV21, so the Image can go back to its reader at once. */
internal class YuvFrame(val bytes: ByteArray, val width: Int, val height: Int)

/**
 * The selected LIVE YUV frame as a JPEG: NV21 compressed at quality 95, then turned upright by [rotation]
 * degrees clockwise. The camera's own JPEG carries its orientation from the request; this one has no EXIF, so the
 * pixels are rotated instead. Both engines use this conversion for app-generated JPEGs.
 */
internal fun encodeYuvStill(frame: YuvFrame, rotation: Int): ByteArray {
    val stream = ByteArrayOutputStream()
    check(YuvImage(frame.bytes, ImageFormat.NV21, frame.width, frame.height, null)
        .compressToJpeg(Rect(0, 0, frame.width, frame.height), 95, stream))
    val converted = stream.toByteArray()
    if (rotation == 0) return converted
    val bitmap = BitmapFactory.decodeByteArray(converted, 0, converted.size) ?: error("Cannot decode YUV JPEG")
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    try {
        stream.reset(); check(rotated.compress(Bitmap.CompressFormat.JPEG, 95, stream))
        return stream.toByteArray()
    } finally { if (rotated !== bitmap) rotated.recycle(); bitmap.recycle() }
}
