package dev.halcamera.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import dev.halcamera.telemetry.Telemetry
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The photo taken while a LIVE recording runs (#175), kept out of [Camera2LiveRecorder]: the JPEG reader that joins
 * the recording session, the TEMPLATE_VIDEO_SNAPSHOT request, and the save to the gallery.
 *
 * One snapshot at a time. A tap while another is in flight is refused rather than queued, because each one asks the
 * HAL for a full-size still inside a running encoder session. A snapshot that fails or times out only reports
 * through [Host.notice]: the recording, its file and its session are left alone.
 *
 * [capture] is called from the main thread and claims the slot there; everything else runs on the camera thread.
 */
internal class Camera2VideoSnapshot(
    private val handler: Handler,
    private val main: Handler,
    private val telemetry: Telemetry,
    private val sessionId: String,
    private val library: MediaLibrary,
    private val mediaIo: Executor,
    private val host: Host,
) {
    interface Host {
        val camera: CameraDevice?
        val session: CameraCaptureSession?
        val characteristics: CameraCharacteristics?
        val active: Boolean
        /** The telemetry callback every capture of the engine also reports to. */
        val captureCallback: CameraCaptureSession.CaptureCallback
        fun orientation(c: CameraCharacteristics): Int
        /** TEMPLATE_VIDEO_SNAPSHOT aimed at the preview, the encoder and the snapshot output of the recording session. */
        fun snapshotRequest(camera: CameraDevice, c: CameraCharacteristics, tag: String, rotation: Int): CaptureRequest
        /** A short message on the main thread that leaves the recording state alone. */
        fun notice(text: String)
    }

    private class Pending(val name: String, val requestId: String?, val done: (Result<PhotoResult>) -> Unit) {
        val delivered = AtomicBoolean(false)
    }

    private val claimed = AtomicBoolean(false)
    private var reader: ImageReader? = null
    private var pending: Pending? = null
    /** True from the tap until the JPEG is saved or the snapshot failed; the main thread reads it for the button. */
    val inFlight: Boolean get() = claimed.get()
    var size: Size? = null
        private set

    /** Creates the JPEG stream for the next recording session. Camera thread. */
    fun open(size: Size): ConfiguredOutput<Surface> {
        release("New recording")
        this.size = size
        val output = OutputDescriptor("video_snapshot", OutputKind.JPEG, repeating = false, stillCapture = true)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).also { this.reader = it }
        reader.setOnImageAvailableListener({ source -> onImage(source) }, handler)
        return ConfiguredOutput(output, reader.surface)
    }

    /** Main thread. The result is delivered on the main thread too. */
    fun capture(requestId: String?, done: (Result<PhotoResult>) -> Unit) {
        if (!claimed.compareAndSet(false, true)) {
            done(Result.failure(IllegalStateException("Saving the last shot… One masterpiece at a time.")))
            return
        }
        val request = Pending(library.name(), requestId, done)
        handler.post { submit(request) }
    }

    private fun submit(request: Pending) {
        val camera = host.camera
        val session = host.session
        val c = host.characteristics
        if (reader == null || camera == null || session == null || c == null || !host.active) {
            deliver(request, Result.failure(IllegalStateException("Snapshot unavailable: recording is not ready.")))
            claimed.set(false)
            return
        }
        pending = request
        try {
            val tag = "snapshot-${SystemClock.elapsedRealtimeNanos()}"
            telemetry.event(sessionId, "video_snapshot_submit", mapOf("requestTag" to tag, "size" to size?.toString(),
                "api" to "CameraCaptureSession.capture", "template" to "TEMPLATE_VIDEO_SNAPSHOT"))
            session.capture(host.snapshotRequest(camera, c, tag, host.orientation(c)), callback(request), handler)
            handler.postDelayed({ if (pending === request) fail(request, "No photo arrived within 5 seconds.") }, TIMEOUT_MS)
        } catch (e: Exception) { fail(request, e.message ?: e.toString()) }
    }

    private fun callback(request: Pending) = object : CameraCaptureSession.CaptureCallback() {
        val cb = host.captureCallback
        override fun onCaptureStarted(session: CameraCaptureSession, r: CaptureRequest, timestamp: Long, frameNumber: Long) =
            cb.onCaptureStarted(session, r, timestamp, frameNumber)
        override fun onCaptureProgressed(session: CameraCaptureSession, r: CaptureRequest, result: CaptureResult) =
            cb.onCaptureProgressed(session, r, result)
        override fun onCaptureCompleted(session: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) =
            cb.onCaptureCompleted(session, r, result)
        override fun onCaptureFailed(session: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
            cb.onCaptureFailed(session, r, failure)
            fail(request, "The camera could not complete the photo request.")
        }
        override fun onCaptureBufferLost(session: CameraCaptureSession, r: CaptureRequest, target: Surface, frameNumber: Long) {
            cb.onCaptureBufferLost(session, r, target, frameNumber)
            fail(request, "The camera lost the photo buffer.")
        }
    }

    private fun onImage(source: ImageReader) {
        val image = try { source.acquireNextImage() } catch (e: Exception) { null } ?: return
        val request = pending
        val timestamp = image.timestamp
        val bytes = try {
            if (host.active) telemetry.image(sessionId, timestamp, image.width, image.height, image.format, "video_snapshot")
            if (request == null) null else ByteArray(image.planes[0].buffer.remaining()).also { image.planes[0].buffer.get(it) }
        } finally { image.close() }
        if (request == null || bytes == null) return
        pending = null // The slot stays claimed until the file is written.
        try {
            mediaIo.execute { save(request, bytes, timestamp) }
        } catch (e: RejectedExecutionException) { failNow(request, "The camera closed before the photo could be saved.") }
    }

    private fun save(request: Pending, jpeg: ByteArray, timestamp: Long) {
        val result = runCatching { library.savePhotos(request.name, null, jpeg) }
        result.onSuccess { telemetry.event(sessionId, "media_saved", mapOf("sensorTimestamp" to timestamp, "source" to "video_snapshot",
            "uris" to it.map { uri -> uri.toString() })) }
        deliver(request, result.map { PhotoResult(request.requestId, request.name, timestamp, it) })
        claimed.set(false)
        host.notice(result.fold({ "Snapshot saved. The show goes on." }, { "Snapshot save failed: ${it.message}" }))
    }

    private fun fail(request: Pending, message: String) {
        if (pending !== request) return
        pending = null
        failNow(request, message)
    }

    private fun failNow(request: Pending, message: String) {
        telemetry.event(sessionId, "video_snapshot_failed", mapOf("message" to message))
        deliver(request, Result.failure(IllegalStateException(message)))
        claimed.set(false)
        host.notice("Snapshot failed: $message")
    }

    private fun deliver(request: Pending, result: Result<PhotoResult>) {
        if (request.delivered.compareAndSet(false, true)) main.post { request.done(result) }
    }

    /** The recording session is over: close the stream and answer a snapshot that never arrived. Camera thread. */
    fun release(reason: String) {
        reader?.close(); reader = null
        pending?.let { fail(it, "$reason: photo capture was interrupted.") }
    }

    private companion object { const val TIMEOUT_MS = 5000L }
}
