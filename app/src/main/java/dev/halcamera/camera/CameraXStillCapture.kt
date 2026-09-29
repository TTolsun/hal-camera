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
import kotlin.math.abs

/**
 * The LIVE still of CameraXEngine: the camera JPEG from ImageCapture and a YUV frame from the analysis stream,
 * saved as one pair through MediaLibrary, like Camera2StillCapture's.
 *
 * CameraX gives an app no way to put the analysis stream on the still request, so the two buffers never come from
 * one capture as they do on Camera2. The YUV half is the analysis frame whose sensor timestamp is nearest the
 * JPEG's, and `media_saved` records the gap as `yuvOffsetNs`. Frames are copied only while a still is pending, and
 * the JPEG waits up to [FRAME_WAIT_MS] for a frame at or after its own timestamp. ImageCapture runs the flash
 * precapture itself, so there is no metering step here.
 *
 * Main thread, except [onFrame], which the analyzer calls on its executor.
 */
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
        /** Points ImageCapture and ImageAnalysis at the display rotation, so both halves come out upright. */
        fun updateRotation()
        fun report(message: String, ok: Boolean)
    }

    private class Frame(val yuv: YuvFrame, val rotation: Int)
    private class Photo(val name: String, val requestId: String?, val done: ((Result<PhotoResult>) -> Unit)?) {
        val frames = linkedMapOf<Long, Frame>()
        var jpeg: ByteArray? = null
        var timestamp: Long? = null
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
        val pending = Photo(library.name(), requestId, done)
        synchronized(lock) { photo = pending }
        inFlight = true
        host.updateRotation()
        host.report("사진 촬영 중…", false)
        telemetry.event(sessionId, "capture_submit", mapOf("api" to if (useCase == null) "ImageAnalysis" else "ImageCapture.takePicture", "flash" to host.flashName,
            "zoomRequested" to host.zoomRequested, "correlation" to "one app capture in flight; CameraX owns internal tags"))
        useCase?.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val timestamp = image.imageInfo.timestamp
                    if (host.active) telemetry.image(sessionId, timestamp, image.width, image.height, image.format, host.stillStream)
                    val buffer = image.planes[0].buffer
                    jpegArrived(pending, ByteArray(buffer.remaining()).also { buffer.get(it) }, timestamp)
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

    /** Every analysis frame. Copies it only while a still waits for its YUV half; the caller closes the image. */
    fun onFrame(image: ImageProxy) {
        val pending = synchronized(lock) { photo } ?: return
        val timestamp = image.imageInfo.timestamp
        synchronized(lock) {
            val target = pending.timestamp
            // Past the JPEG a single frame is enough: it bounds the nearest one from above.
            if (target != null && pending.frames.keys.any { it >= target }) return
        }
        val crop = image.cropRect
        val frame = Frame(YuvFrame(YuvPacking.nv21(image.planes.map { YuvPacking.Plane(it.buffer, it.rowStride, it.pixelStride) },
            crop.left, crop.top, crop.width(), crop.height()), crop.width(), crop.height()), image.imageInfo.rotationDegrees)
        val ready = synchronized(lock) {
            if (photo !== pending) return
            pending.frames[timestamp] = frame
            if (host.imageCapture == null) pending.timestamp = timestamp
            while (pending.frames.size > 8) pending.frames.remove(pending.frames.keys.first())
            pending.timestamp?.let { timestamp >= it } == true
        }
        if (ready) main.post { save(pending) }
    }

    /** The camera is closing: answer a pending still. */
    fun close() {
        val pending = synchronized(lock) { photo.also { photo = null } } ?: return
        deliver(pending, Result.failure(IllegalStateException("Camera closed before capture completed")))
        inFlight = false
    }

    private fun jpegArrived(pending: Photo, jpeg: ByteArray, timestamp: Long) {
        val ready = synchronized(lock) {
            if (photo !== pending) return
            pending.jpeg = jpeg; pending.timestamp = timestamp
            !host.analysisEnabled || pending.frames.keys.any { it >= timestamp }
        }
        if (ready) save(pending) else main.postDelayed({ save(pending) }, FRAME_WAIT_MS)
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

    /**
     * Main thread. Runs once per still, from whichever of the JPEG, a late frame or the wait comes first. With no
     * frame at all yet (a HAL that pauses the repeating streams for the still) it keeps waiting: the next frame
     * saves the pair, and the 5 s timeout answers a stream that never comes back.
     */
    private fun save(pending: Photo) {
        val (timestamp, jpeg, picked) = synchronized(lock) {
            if (photo !== pending) return
            val t = pending.timestamp ?: return
            val j = pending.jpeg
            val p = pending.frames.entries.minByOrNull { abs(it.key - t) }
            if ((host.imageCapture != null && j == null) || (host.analysisEnabled && p == null)) return
            photo = null // Keep inFlight until the pair has been written.
            Triple(t, j, p)
        }
        mediaIo.execute {
            val result = runCatching { library.savePhotos(pending.name,
                picked?.value?.let { encodeYuvStill(it.yuv, it.rotation) }, jpeg) }
            result.onSuccess { uris ->
                telemetry.event(sessionId, "media_saved", mapOf("sensorTimestamp" to timestamp, "yuvOffsetNs" to picked?.key?.minus(timestamp),
                    "uris" to uris.map { it.toString() }))
            }
            deliver(pending, result.map { PhotoResult(pending.requestId, pending.name, timestamp, it) })
            main.post {
                inFlight = false
                val message = result.fold({ "갤러리에 사진 ${it.size}장을 저장했습니다" }, { "사진 저장 실패: ${it.message}" })
                if (!host.active || result.isFailure) Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
                if (host.active) host.report(result.fold({ message }, { "$message · 다시 촬영할 수 있습니다" }), true)
            }
        }
    }

    companion object {
        /** About three analysis frames at 30 fps: long enough for the frame after the still, short for the user. */
        const val FRAME_WAIT_MS = 100L
    }
}
