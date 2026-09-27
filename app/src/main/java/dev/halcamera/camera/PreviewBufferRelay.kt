package dev.halcamera.camera

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.media.ImageWriter
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import dev.halcamera.telemetry.Telemetry

/** Receives the preview stream before handing its PRIVATE buffers to TextureView. No CPU pixel copy. */
@RequiresApi(33)
internal class PreviewBufferRelay(
    displaySurface: Surface,
    size: Size,
    telemetry: Telemetry,
    sessionId: String,
    private val onError: (Exception) -> Unit,
) {
    val descriptor = OutputDescriptor("preview", OutputKind.PREVIEW, repeating = true)
    private val thread = HandlerThread("CD.PreviewBuffer")
    private val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.PRIVATE, 3,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
    private val writer = try { ImageWriter.newInstance(displaySurface, 3, ImageFormat.PRIVATE) }
        catch (error: Exception) { reader.close(); throw error }
    private val handler: Handler
    @Volatile private var closing = false
    val output get() = ConfiguredOutput(descriptor, reader.surface)

    init {
        thread.start()
        handler = Handler(thread.looper)
        reader.setOnImageAvailableListener({ source ->
            if (!closing) {
                try {
                    val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        telemetry.recorder.record(sessionId, descriptor.kind.eventKind, sensorNs = image.timestamp,
                            values = mapOf("stream" to descriptor.id, "width" to image.width,
                                "height" to image.height, "format" to image.format))
                        writer.queueInputImage(image)
                    } catch (error: Exception) {
                        image.close()
                        throw error
                    }
                } catch (error: Exception) {
                    if (!closing) { closing = true; onError(error) }
                }
            }
        }, handler)
    }

    /** Camera is already closed. Do not block camera teardown on a paused display consumer. */
    fun close() {
        closing = true
        reader.setOnImageAvailableListener(null, null)
        handler.post {
            try { writer.close() } finally {
                try { reader.close() } finally { thread.quitSafely() }
            }
        }
    }
}
