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

/** Receives PRIVATE camera buffers and transfers ownership to the encoder without reading pixels. */
@RequiresApi(29)
internal class RecordingBufferRelay(
    encoderSurface: Surface,
    size: Size,
    output: OutputDescriptor,
    telemetry: Telemetry,
    sessionId: String,
    timebase: RecordingTimebase,
    private val onError: (Exception) -> Unit,
) {
    private val thread = HandlerThread("CD.RecordingBuffer")
    private val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.PRIVATE, 3,
        HardwareBuffer.USAGE_VIDEO_ENCODE)
    private val writer = try { ImageWriter.newInstance(encoderSurface, 3, ImageFormat.PRIVATE) }
        catch (error: Exception) { reader.close(); throw error }
    private val handler: Handler
    @Volatile private var closing = false
    val surface: Surface get() = reader.surface

    init {
        thread.start()
        handler = Handler(thread.looper)
        reader.setOnImageAvailableListener({ source ->
            if (!closing) {
                try {
                    val image = source.acquireNextImage() ?: return@setOnImageAvailableListener
                    try {
                        // Preserve the exact sensor key before converting the timestamp for audio/video sync.
                        telemetry.image(sessionId, image.timestamp, image.width, image.height, image.format, output.id)
                        image.timestamp = timebase.encoderTimestampNs(image.timestamp)
                        writer.queueInputImage(image) // Ownership transfers to the writer on success.
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

    fun stopAccepting() {
        closing = true
        reader.setOnImageAvailableListener(null, null)
    }

    /** Call after releasing MediaRecorder: abandoning its Surface unblocks an in-flight queueInputImage. */
    fun closeAfterEncoder() {
        stopAccepting()
        handler.post {
            try { writer.close() } finally {
                try { reader.close() } finally { thread.quitSafely() }
            }
        }
    }
}
