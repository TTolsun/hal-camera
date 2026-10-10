package dev.halcamera.camera

import android.content.Context
import android.os.Handler
import android.widget.Toast
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import dev.halcamera.telemetry.Telemetry
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Saves either the CameraX JPEG or the next analysis frame converted by the app. */
internal class CameraXStillCapture(
    private val context: Context,
    private val main: Handler,
    private val mainExecutor: Executor,
    private val telemetry: Telemetry,
    private val sessionId: String,
    private val library: MediaLibrary,
    private val mediaIo: Executor,
    private val host: Host,
) {
    interface Host {
        val imageCapture: ImageCapture?
        val analysisEnabled: Boolean
        val active: Boolean
        val recordingBusy: Boolean
        val flashName: String
        val zoomRequested: Float
        /** The still output's id, which telemetry records the JPEG arrival under. */
        val stillStream: String
        /** Points ImageCapture and ImageAnalysis at the display rotation, so the selected output comes out upright. */
        fun updateRotation()
        fun report(message: String, ok: Boolean)
    }

    private class Frame(val yuv: YuvFrame, val rotation: Int)
    private class Photo(val name: String, val requestId: String?, val done: ((Result<PhotoResult>) -> Unit)?) {
        val delivered = AtomicBoolean(false)
    }

    private val lock = Any()
    private var photo: Photo? = null
    @Volatile var inFlight = false
        private set

    fun capture(requestId: String?, done: ((Result<PhotoResult>) -> Unit)?) {
        val useCase = host.imageCapture
        if ((useCase == null && !host.analysisEnabled) || !host.active || inFlight || host.recordingBusy) {
            main.post { done?.invoke(Result.failure(IllegalStateException("Camera not ready or busy"))) }
            return
        }
        val pending = Photo(BracketFiles.named(library.name(), requestId), requestId, done)
        synchronized(lock) { photo = pending }
        inFlight = true
        host.updateRotation()
        host.report("Capturing… Say cheese.", false)
        telemetry.event(sessionId, "capture_submit", mapOf("api" to if (useCase == null) "ImageAnalysis" else "ImageCapture.takePicture", "flash" to host.flashName,
            "zoomRequested" to host.zoomRequested, "correlation" to "one app capture in flight; CameraX owns internal tags"))
        useCase?.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val timestamp = image.imageInfo.timestamp
                    if (host.active) telemetry.image(sessionId, timestamp, image.width, image.height, image.format, host.stillStream)
                    val buffer = image.planes[0].buffer
                    save(pending, timestamp, null, ByteArray(buffer.remaining()).also { buffer.get(it) })
                } catch (e: Exception) { fail(pending, e, "Capture failed: ${e.message} · retry") }
                finally { image.close() }
            }
            override fun onError(exception: ImageCaptureException) {
                telemetry.event(sessionId, "capture_error", mapOf("message" to exception.message, "code" to exception.imageCaptureError))
                fail(pending, exception, "Capture failed: ${exception.message} · retry")
            }
        })
        main.postDelayed({
            if (synchronized(lock) { photo === pending }) {
                telemetry.event(sessionId, "capture_timeout")
                fail(pending, IllegalStateException("Capture timed out"), "Capture timed out (5s) · retry")
            }
        }, 5000)
    }

    /** Every analysis frame. Copies it only while an app JPEG waits for its source; the caller closes the image. */
    fun onFrame(image: ImageProxy) {
        if (!host.analysisEnabled) return
        val pending = synchronized(lock) { photo } ?: return
        val timestamp = image.imageInfo.timestamp
        val crop = image.cropRect
        val frame = Frame(YuvFrame(YuvPacking.nv21(image.planes.map { YuvPacking.Plane(it.buffer, it.rowStride, it.pixelStride) },
            crop.left, crop.top, crop.width(), crop.height()), crop.width(), crop.height()), image.imageInfo.rotationDegrees)
        save(pending, timestamp, frame, null)
    }

    /** The camera is closing: answer a pending still. */
    fun close() {
        val pending = synchronized(lock) { photo.also { photo = null } } ?: return
        deliver(pending, Result.failure(IllegalStateException("Camera closed before capture completed")))
        inFlight = false
    }

    private fun fail(pending: Photo, e: Exception, message: String) {
        if (!synchronized(lock) { (photo === pending).also { if (it) photo = null } }) return
        deliver(pending, Result.failure(e))
        inFlight = false
        if (host.active) host.report(message, true)
    }

    private fun deliver(pending: Photo, result: Result<PhotoResult>) {
        if (pending.delivered.compareAndSet(false, true)) main.post { pending.done?.invoke(result) }
    }

    private fun save(pending: Photo, timestamp: Long, frame: Frame?, jpeg: ByteArray?) {
        synchronized(lock) {
            if (photo !== pending) return
            photo = null // The IO job now owns the selected frame; keep BUSY until saved.
        }
        mediaIo.execute {
            val metadata = captureMetadata(telemetry, sessionId, timestamp, pending.requestId) +
                mapOf("jpegSource" to if (frame != null) "YUV" else "CAMERA", "yuvRotationDegrees" to frame?.rotation)
            val result = runCatching {
                val encoded = frame?.let { encodeYuvStill(it.yuv, it.rotation) }
                if (encoded != null) telemetry.recorder.record(sessionId, appJpegOutput.eventKind,
                    sensorNs = timestamp, values = mapOf("stream" to appJpegOutput.id))
                library.saveCapture(pending.name, encoded, jpeg, metadata)
            }
            result.onSuccess { uris ->
                telemetry.event(sessionId, "media_saved", mapOf("sensorTimestamp" to timestamp,
                    "uris" to uris.map { it.uri.toString() }))
            }
            deliver(pending, result.map { PhotoResult(pending.requestId, pending.name, timestamp, it.map { file -> file.uri }, it) })
            main.post {
                inFlight = false
                val message = result.fold({ "Saved ${it.size} files" }, { "Photo save failed: ${it.message}" })
                if (!host.active || result.isFailure) Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
                if (host.active) host.report(result.fold({ message }, { "$message · Ready for another shot." }), true)
            }
        }
    }

}
