package dev.halcamera.camera

import android.os.Handler
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import dev.halcamera.telemetry.Telemetry
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The photo taken while a CameraX LIVE recording runs (#175), the counterpart of [Camera2VideoSnapshot]. The
 * recording session binds ImageCapture next to VideoCapture; CameraX then takes the JPEG through the video
 * snapshot path of the same session. When the camera refuses that combination the engine binds the recording alone
 * and [Host.imageCapture] stays null, so nothing here runs.
 *
 * One snapshot at a time, JPEG only (the analysis stream is unbound while recording, so there is no YUV half). A
 * failure only reports through [Host.notice]; the recording goes on. Main thread.
 */
internal class CameraXVideoSnapshot(
    private val main: Handler,
    private val mainExecutor: Executor,
    private val telemetry: Telemetry,
    private val sessionId: String,
    private val library: MediaLibrary,
    private val mediaIo: Executor,
    private val host: Host,
    private val takePicture: (ImageCapture, Executor, ImageCapture.OnImageCapturedCallback) -> Unit =
        { capture, executor, callback -> capture.takePicture(executor, callback) },
) {
    interface Host {
        /** The ImageCapture bound with the recording, or null when the recording runs without a photo use case. */
        val imageCapture: ImageCapture?
        val active: Boolean
        val flashName: String
        val zoomRequested: Float
        val snapshotStream: String
        fun updateRotation()
        fun notice(text: String)
    }

    private val claimed = AtomicBoolean(false)
    private var pending: SnapshotRequest<PhotoResult>? = null
    val inFlight: Boolean get() = claimed.get()

    fun capture(requestId: String?, done: (Result<PhotoResult>) -> Unit) {
        val useCase = host.imageCapture
        if (useCase == null || !host.active) { done(Result.failure(IllegalStateException("Snapshot unavailable: recording is not ready."))); return }
        if (!claimed.compareAndSet(false, true)) { done(Result.failure(IllegalStateException("Saving the last shot… One masterpiece at a time."))); return }
        val name = library.name()
        lateinit var timeout: Runnable
        val request = SnapshotRequest<PhotoResult> { result ->
            main.removeCallbacks(timeout)
            main.post {
                pending = null
                claimed.set(false)
                done(result)
                host.notice(result.fold({ "Snapshot saved. The show goes on." }, { "Snapshot failed: ${it.message}" }))
            }
            result.exceptionOrNull()?.let { telemetry.event(sessionId, "video_snapshot_failed", mapOf("message" to it.message)) }
        }
        timeout = Runnable { request.failCapture(IllegalStateException("No photo arrived within 5 seconds.")) }
        pending = request
        main.postDelayed(timeout, TIMEOUT_MS)
        try {
            host.updateRotation()
            telemetry.event(sessionId, "video_snapshot_submit", mapOf("api" to "ImageCapture.takePicture", "flash" to host.flashName,
                "zoomRequested" to host.zoomRequested, "size" to useCase.resolutionInfo?.resolution?.toString()))
            takePicture(useCase, mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    if (!request.acceptImage()) { image.close(); return }
                    main.removeCallbacks(timeout)
                    var timestamp = 0L
                    val bytes = try {
                        timestamp = image.imageInfo.timestamp
                        if (host.active) telemetry.image(sessionId, timestamp, image.width, image.height, image.format, host.snapshotStream)
                        val buffer = image.planes[0].buffer
                        ByteArray(buffer.remaining()).also { buffer.get(it) }
                    } catch (e: Exception) { request.finishSave(Result.failure(e)); return } finally { image.close() }
                    try {
                        mediaIo.execute {
                            val saved = runCatching { library.savePhotos(name, null, bytes) }
                            saved.onSuccess { telemetry.event(sessionId, "media_saved", mapOf("sensorTimestamp" to timestamp,
                                "source" to "video_snapshot", "uris" to it.map { uri -> uri.toString() })) }
                            request.finishSave(saved.map { PhotoResult(requestId, name, timestamp, it) })
                        }
                    } catch (e: RejectedExecutionException) {
                        request.finishSave(Result.failure(IllegalStateException("The camera closed before the photo could be saved.", e)))
                    }
                }
                override fun onError(exception: ImageCaptureException) =
                    request.failCapture(exception)
            })
        } catch (e: Exception) { request.failCapture(e) }
    }

    /** Main thread. A copied JPEG keeps saving; a camera request still waiting for its image is interrupted. */
    fun release(reason: String) {
        pending?.failCapture(IllegalStateException("$reason: photo capture was interrupted."))
    }

    private companion object { const val TIMEOUT_MS = 5000L }
}
