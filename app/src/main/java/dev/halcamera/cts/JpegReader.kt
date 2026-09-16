package dev.halcamera.cts

import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.view.Surface
import dev.halcamera.cts.combination.StillPreviewCombinationRules
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * CameraTestUtils' SimpleImageReaderListener: a JPEG ImageReader whose images queue up as they arrive on
 * [handler], so a runner can wait for the next one with a timeout. Close it after the session that targets its
 * surface is closed; closing drops any image still queued.
 */
class JpegReader(width: Int, height: Int, handler: Handler, maxImages: Int = 2) : AutoCloseable {
    private val reader: ImageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, maxImages)
    private val queue = LinkedBlockingQueue<Image>()

    init {
        reader.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { queue.offer(it) } }, handler)
    }

    val surface: Surface get() = reader.surface

    /** The next image, or null when none arrived within [timeoutMs]. The caller closes it. */
    fun next(timeoutMs: Long): Image? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        while (true) { (queue.poll() ?: break).close() }
        reader.close()
    }

    companion object {
        /** What the rules need from a JPEG: its reported geometry, the blob size, and the size BitmapFactory reads from the blob. */
        fun describe(image: Image): StillPreviewCombinationRules.ImageInfo {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            val decoded = if (options.outWidth > 0 && options.outHeight > 0) Dim(options.outWidth, options.outHeight) else null
            return StillPreviewCombinationRules.ImageInfo(image.width, image.height, image.format, bytes.size, decoded)
        }
    }
}
