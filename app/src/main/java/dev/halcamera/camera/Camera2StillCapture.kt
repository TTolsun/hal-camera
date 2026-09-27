package dev.halcamera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.os.Handler
import android.view.Surface
import android.widget.Toast
import dev.halcamera.telemetry.Telemetry
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor

/**
 * The still capture of Camera2Engine, kept out of the engine: the flash precapture (#176), the still request, and
 * on LIVE the YUV + JPEG pair that is matched by sensor timestamp and saved to the gallery. A benchmark still has
 * no pair; its JPEG arrival is the measurement and nothing is saved.
 *
 * Camera thread only, except [inFlight], which the main thread reads for the CLI's BUSY answer.
 */
internal class Camera2StillCapture(
    private val context: Context,
    private val handler: Handler,
    private val main: Handler,
    private val telemetry: Telemetry,
    private val sessionId: String,
    private val library: MediaLibrary,
    private val mediaIo: Executor,
    /** A benchmark engine: stills are measured, never paired or saved. */
    private val benchmark: Boolean,
    private val host: Host,
) {
    interface Host {
        val camera: CameraDevice?
        val session: CameraCaptureSession?
        val characteristics: CameraCharacteristics
        val active: Boolean
        val recordingBusy: Boolean
        /** Flash auto or on with AE not locked on the request the still will carry. */
        val needsPrecapture: Boolean
        val flashName: String
        val zoomRequested: Float
        /** The telemetry callback, which every capture of this class also reports to. */
        val captureCallback: CameraCaptureSession.CaptureCallback
        fun orientation(c: CameraCharacteristics): Int
        /** The still request; with [rotation] it also feeds the YUV stream for the pair and sets the JPEG orientation. */
        fun stillRequest(camera: CameraDevice, c: CameraCharacteristics, tag: String, rotation: Int?): CaptureRequest
        /** One preview capture with AE precapture trigger START; false when no session could take it. */
        fun precaptureTrigger(callback: CameraCaptureSession.CaptureCallback): Boolean
        fun report(message: String, ok: Boolean)
        fun fail(e: Exception)
    }

    private class YuvFrame(val bytes: ByteArray, val width: Int, val height: Int)
    private class Photo(val name: String, val rotation: Int, val requestId: String?, val done: ((Result<PhotoResult>) -> Unit)?) {
        val pair = StillPair<YuvFrame, ByteArray>()
        val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    private var photo: Photo? = null
    @Volatile var inFlight = false
        private set
    /** Called with every LIVE repeating result while a precapture waits for AE. */
    private var resultHook: ((TotalCaptureResult) -> Unit)? = null
    /** Ends a precapture still waiting for AE, so closing the camera still answers the capture caller. */
    private var precaptureFinish: ((String) -> Unit)? = null

    /** A still pair is waiting for its images: the readers must drain in order so the YUV frame is not discarded. */
    val pairing: Boolean get() = photo != null

    fun capture(requestId: String?, done: ((Result<PhotoResult>) -> Unit)?) {
        handler.post {
            if (host.camera == null || host.session == null || !host.active || inFlight || host.recordingBusy || (done != null && benchmark)) {
                main.post { done?.invoke(Result.failure(IllegalStateException("Camera not ready or busy"))) }
                return@post
            }
            if (!benchmark && host.needsPrecapture) { inFlight = true; precapture { shoot(requestId, done) } }
            else shoot(requestId, done)
        }
    }

    fun onLiveResult(result: TotalCaptureResult) { resultHook?.invoke(result) }

    /** An image from the YUV or JPEG reader, after the engine has recorded it. */
    fun onImage(image: Image, format: Int, stream: String) {
        val pending = photo
        if (host.active && pending != null) {
            if (format == ImageFormat.JPEG) {
                pending.pair.jpeg(image.timestamp, ByteArray(image.planes[0].buffer.remaining()).also { image.planes[0].buffer.get(it) })
            } else if (pending.pair.accepts(image.timestamp)) {
                val crop = image.cropRect
                pending.pair.yuv(image.timestamp, YuvFrame(YuvPacking.nv21(image.planes.map {
                    YuvPacking.Plane(it.buffer, it.rowStride, it.pixelStride)
                }, crop.left, crop.top, crop.width(), crop.height()), crop.width(), crop.height()))
            }
            savePhotoIfComplete(pending)
        } else if (stream == "still" && benchmark) {
            inFlight = false; host.report("Camera2 · capture received", true)
        }
    }

    fun onImageFailed(e: Exception) {
        photo?.let { deliverPhoto(it, Result.failure(e)) }; photo = null; inFlight = false
        if (host.active) { host.fail(e); host.report("Capture failed: ${e.message} · retry", true) }
    }

    /** The camera is closing: answer a waiting precapture and a pending pair. */
    fun close() {
        precaptureFinish?.invoke("closed")
        photo?.let { deliverPhoto(it, Result.failure(IllegalStateException("Camera closed before capture completed"))) }
        photo = null
    }

    private fun deliverPhoto(pending: Photo, result: Result<PhotoResult>) {
        if (pending.delivered.compareAndSet(false, true)) main.post { pending.done?.invoke(result) }
    }

    /**
     * Flash auto/on metering before the still (#176). The trigger rides on one preview capture; the repeating
     * results after it are watched until AE leaves PRECAPTURE. A sequence that has not settled after 3 s is logged
     * as `precapture_timeout` and the still fires anyway, because a stuck AE must not leave the shutter dead.
     */
    private fun precapture(then: () -> Unit) {
        val watch = PrecaptureWatch()
        var finished = false
        val finish = { reason: String ->
            if (!finished) {
                finished = true; resultHook = null; precaptureFinish = null
                telemetry.event(sessionId, "precapture_done", mapOf("reason" to reason))
                if (reason == "timeout") host.report("플래시 측광이 3초 안에 끝나지 않아 그대로 촬영합니다", false)
                then()
            }
        }
        precaptureFinish = finish
        host.report("플래시 측광 중…", false)
        telemetry.event(sessionId, "precapture_trigger", mapOf("flash" to host.flashName))
        val cb = host.captureCallback
        // A trigger that could not be sent has no result to wait for: shoot now rather than after the 3 s timeout.
        val sent = host.precaptureTrigger(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(s: CameraCaptureSession, r: CaptureRequest, timestamp: Long, frameNumber: Long) =
                cb.onCaptureStarted(s, r, timestamp, frameNumber)
            override fun onCaptureProgressed(s: CameraCaptureSession, r: CaptureRequest, result: CaptureResult) =
                cb.onCaptureProgressed(s, r, result)
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                cb.onCaptureCompleted(s, r, result)
                if (finished) return
                if (watch.onResult(result[CaptureResult.CONTROL_AE_STATE])) finish("settled")
                else resultHook = { next -> if (watch.onResult(next[CaptureResult.CONTROL_AE_STATE])) finish("settled") }
            }
            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                cb.onCaptureFailed(s, r, failure); finish("trigger_failed")
            }
        })
        if (!sent) { finish("trigger_not_sent"); return }
        handler.postDelayed({ if (!finished) { telemetry.event(sessionId, "precapture_timeout"); finish("timeout") } }, 3000)
    }

    private fun shoot(requestId: String?, done: ((Result<PhotoResult>) -> Unit)?) {
        val camera = host.camera
        val session = host.session
        if (camera == null || session == null || !host.active) {
            inFlight = false
            main.post { done?.invoke(Result.failure(IllegalStateException("Camera closed before capture"))) }
            return
        }
        try {
            val tag = "still-${android.os.SystemClock.elapsedRealtimeNanos()}"
            val c = host.characteristics
            val pending = if (!benchmark) Photo(library.name(), host.orientation(c), requestId, done) else null
            val request = host.stillRequest(camera, c, tag, pending?.rotation)
            inFlight = true
            photo = pending
            if (pending != null) host.report("YUV + JPEG 촬영 중…", false)
            telemetry.event(sessionId, "capture_submit", mapOf("requestTag" to tag, "api" to "CameraCaptureSession.capture", "zoomRequested" to host.zoomRequested))
            session.capture(request, if (pending == null) host.captureCallback else photoCallback(pending), handler)
            handler.postDelayed({
                if (inFlight && host.active && (pending == null || photo === pending)) {
                    pending?.let { deliverPhoto(it, Result.failure(IllegalStateException("Capture timed out"))) }
                    photo = null; inFlight = false; telemetry.event(sessionId, "capture_timeout")
                    host.report("Capture timed out (5s) · retry", !benchmark)
                }
            }, 5000)
        } catch (e: Exception) {
            val pending = photo
            if (pending != null) deliverPhoto(pending, Result.failure(e)) else main.post { done?.invoke(Result.failure(e)) }
            photo = null; inFlight = false; host.fail(e); if (!benchmark) host.report("Capture failed: ${e.message} · retry", true)
        }
    }

    private fun photoCallback(pending: Photo) = object : CameraCaptureSession.CaptureCallback() {
        val cb = host.captureCallback
        override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, result: CaptureResult) =
            cb.onCaptureProgressed(session, request, result)
        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            cb.onCaptureStarted(session, request, timestamp, frameNumber)
            if (photo === pending) { pending.pair.timestamp = timestamp; savePhotoIfComplete(pending) }
        }
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            cb.onCaptureCompleted(session, request, result)
            if (photo === pending) { result[CaptureResult.SENSOR_TIMESTAMP]?.let { pending.pair.timestamp = it }; savePhotoIfComplete(pending) }
        }
        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            cb.onCaptureFailed(session, request, failure)
            if (photo === pending) { deliverPhoto(pending, Result.failure(IllegalStateException("Capture failed"))); photo = null; inFlight = false; host.report("Capture failed · retry", true) }
        }
        override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
            cb.onCaptureBufferLost(session, request, target, frameNumber)
            if (photo === pending) { deliverPhoto(pending, Result.failure(IllegalStateException("Capture buffer lost"))); photo = null; inFlight = false; host.report("Capture buffer lost · retry", true) }
        }
    }

    private fun savePhotoIfComplete(pending: Photo) {
        val timestamp = pending.pair.timestamp ?: return
        val (yuvFrame, jpegBytes) = pending.pair.complete() ?: return
        photo = null // Keep inFlight until the pair has been written.
        mediaIo.execute {
            val result = runCatching {
                val stream = ByteArrayOutputStream()
                check(YuvImage(yuvFrame.bytes, ImageFormat.NV21, yuvFrame.width, yuvFrame.height, null)
                    .compressToJpeg(Rect(0, 0, yuvFrame.width, yuvFrame.height), 95, stream))
                var converted = stream.toByteArray()
                if (pending.rotation != 0) {
                    val bitmap = BitmapFactory.decodeByteArray(converted, 0, converted.size) ?: error("Cannot decode YUV JPEG")
                    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                        Matrix().apply { postRotate(pending.rotation.toFloat()) }, true)
                    try {
                        stream.reset(); check(rotated.compress(Bitmap.CompressFormat.JPEG, 95, stream))
                        converted = stream.toByteArray()
                    } finally { if (rotated !== bitmap) rotated.recycle(); bitmap.recycle() }
                }
                library.savePair(pending.name, converted, jpegBytes)
            }
            result.onSuccess { uris ->
                telemetry.event(sessionId, "media_saved", mapOf("sensorTimestamp" to timestamp, "uris" to uris.map { it.toString() }))
            }
            deliverPhoto(pending, result.map { PhotoResult(pending.requestId, pending.name, timestamp, it) })
            main.post {
                if (!host.active || result.isFailure) {
                    val message = result.fold({ "갤러리에 YUV · JPEG 사진 2장을 저장했습니다" }, { "사진 저장 실패: ${it.message}" })
                    Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
                }
            }
            handler.post {
                inFlight = false
                result.fold({
                    host.report("갤러리에 YUV · JPEG 사진 2장을 저장했습니다", true)
                }, { host.report("사진 저장 실패: ${it.message} · 다시 촬영할 수 있습니다", true) })
            }
        }
    }
}
