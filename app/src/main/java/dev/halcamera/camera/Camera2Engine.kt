package dev.halcamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaRecorder
import android.graphics.YuvImage
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.TextureView
import dev.halcamera.telemetry.Telemetry

class Camera2Engine(
    private val context: Context,
    private val view: TextureView,
    private val cameraId: String,
    private val sessionId: String,
    private val telemetry: Telemetry,
    /** Benchmark profile streams. null keeps the LIVE screen behaviour of picking sizes by pixel budget. */
    private val spec: StreamSpec? = null,
    private val previewReady: () -> Unit = {},
    private val recordingState: (Boolean) -> Unit = {},
    private val status: (String, Boolean) -> Unit
) : CameraEngine, MediaCapture, LiveTuning {
    private val thread = HandlerThread("CD.Camera2").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(CameraManager::class.java)
    @Volatile private var active = true
    private var opening = false
    private var device: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var yuv: ImageReader? = null
    private var jpeg: ImageReader? = null
    private var finished = false
    private var closeDone: (() -> Unit)? = null
    @Volatile private var photoInFlight = false
    override val mediaBusy: Boolean get() = photoInFlight || videoBusy || bench.recording
    private var previewSeen = false
    private val mediaIo = Executors.newSingleThreadExecutor()
    private val library = MediaLibrary(context)
    private data class YuvFrame(val bytes: ByteArray, val width: Int, val height: Int)
    private class Photo(val name: String, val rotation: Int, val requestId: String?, val done: ((Result<PhotoResult>) -> Unit)?) {
        val pair = StillPair<YuvFrame, ByteArray>()
        val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
    }
    private var photo: Photo? = null
    private var videoRecorder: MediaRecorder? = null
    private var videoFile: File? = null
    private var videoStarted = false
    @Volatile private var videoBusy = false
    private var videoDone: ((Result<android.net.Uri>) -> Unit)? = null
    private var videoStopRequested = false
    private var videoFailure: Exception? = null
    @Volatile private var zoomRatio = 1f
    /** One queued zoom submission at a time: a fast drag merges into the ratio that is current when it runs. */
    private val zoomQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var controls = LiveControls()
    /** The LIVE recorder's surface while a recording session is up; repeating requests then use the record template. */
    private var recorderSurface: Surface? = null
    /** Called with every LIVE repeating result; the flash precapture waits on it. Camera thread only. */
    private var resultHook: ((TotalCaptureResult) -> Unit)? = null
    /** Ends a precapture still waiting for AE, so closing the camera still answers the capture caller. */
    private var precaptureFinish: ((String) -> Unit)? = null
    private var chars: CameraCharacteristics? = null
    private val callback = telemetry.callback(sessionId) { active }
    private val liveCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) =
            callback.onCaptureStarted(session, request, timestamp, frameNumber)
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            callback.onCaptureCompleted(session, request, result)
            resultHook?.invoke(result)
        }
        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) =
            callback.onCaptureFailed(session, request, failure)
        override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) =
            callback.onCaptureBufferLost(session, request, target, frameNumber)
    }
    // The benchmark RECORD stage keeps its own recorder: the LIVE one picks its own size, records audio and
    // saves to the gallery, none of which a measurement may do (docs/PLAN-Recording-v0.1.md 4 and 6).
    private val bench = BenchmarkRecorder(context, handler, telemetry, sessionId, spec?.record, object : BenchmarkRecorder.Host {
        override val camera: CameraDevice? get() = device
        override val cameraActive: Boolean get() = active
        override val previewSurface: Surface? get() = this@Camera2Engine.previewSurface
        override val session: CameraCaptureSession? get() = captureSession
        override fun onSessionConfigured(session: CameraCaptureSession) { captureSession = session }
        override val characteristics: CameraCharacteristics? get() = chars
        override val captureCallback: CameraCaptureSession.CaptureCallback get() = callback
        override fun orientationHint(chars: CameraCharacteristics): Int = outputRotation(chars)
        override fun recordRequest(camera: CameraDevice, chars: CameraCharacteristics, recorderSurface: Surface, recording: Boolean, iteration: Int): CaptureRequest =
            this@Camera2Engine.recordRequest(camera, chars, recorderSurface, recording, iteration)
    })
    override fun start() {
        telemetry.registerSession(sessionId, "Camera2", manager, cameraId)
        if (view.isAvailable) handler.post { open() }
        else view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { handler.post { open() } }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }
    @SuppressLint("MissingPermission")
    private fun open() {
        if (!active || opening || device != null) return
        try {
            opening = true
            telemetry.event(sessionId, "open_call", mapOf("api" to "CameraManager.openCamera"))
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    telemetry.event(sessionId, "opened")
                    opening = false
                    device = camera
                    if (!active) camera.close() else configure(camera)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    opening = false
                    report("Camera disconnected", false)
                    camera.close()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    opening = false
                    report("Camera2 error $error", false)
                    telemetry.event(sessionId, "camera_error", mapOf("code" to error))
                    camera.close()
                }
                override fun onClosed(camera: CameraDevice) { device = null; finishClose() }
            }, handler)
        } catch (e: Exception) { opening = false; fail(e); if (!active) finishClose() }
    }
    private fun choose(sizes: Array<Size>, maxPixels: Long): Size =
        sizes.filter { it.width.toLong() * it.height <= maxPixels }.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minBy { it.width.toLong() * it.height }
    @Suppress("DEPRECATION")
    private fun configure(camera: CameraDevice) {
        try {
            yuv?.close(); jpeg?.close(); previewSurface?.release()
            val chars = manager.getCameraCharacteristics(cameraId).also { this.chars = it }
            val map = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] ?: error("No stream configuration")
            // With a profile spec the sizes are exact and unavailable ones fail the configure step: measuring a
            // smaller stream under the same profile id would corrupt every comparison made with that id.
            val size = spec?.preview?.also { require(it in map.getOutputSizes(SurfaceTexture::class.java)) { "preview $it unsupported" } }
                ?: choose(map.getOutputSizes(SurfaceTexture::class.java), 1280L * 720)
            val yuvSize = spec?.yuv?.also { require(it in map.getOutputSizes(ImageFormat.YUV_420_888)) { "yuv $it unsupported" } }
                ?: choose(map.getOutputSizes(ImageFormat.YUV_420_888), 640L * 480)
            val jpegSize = spec?.jpeg?.also { require(it in map.getOutputSizes(ImageFormat.JPEG)) { "jpeg $it unsupported" } }
                ?: choose(map.getOutputSizes(ImageFormat.JPEG), 1920L * 1080)
            val texture = view.surfaceTexture ?: error("Preview surface unavailable")
            texture.setDefaultBufferSize(size.width, size.height)
            previewSurface = Surface(texture)
            main.post { transform(size, chars) }
            yuv = reader(yuvSize, ImageFormat.YUV_420_888, "analysis_acquire_latest")
            jpeg = reader(jpegSize, ImageFormat.JPEG, "still")
            // The effective values go into the event so conditions.effective in the run JSON reports what the
            // camera actually ran with, not what the profile asked for (3.1, fixed-focus cameras run AF OFF).
            val sizes = mapOf(
                "preview" to size.toString(), "analysis" to yuvSize.toString(), "jpeg" to jpegSize.toString(),
                "afMode" to afMode(chars), "fpsRange" to spec?.fpsRange?.toString()
            )
            telemetry.sessions.computeIfPresent(sessionId) { _, old -> old + mapOf("negotiatedStreams" to sizes) }
            telemetry.event(sessionId, "configure_requested", sizes)
            camera.createCaptureSession(listOf(previewSurface!!, yuv!!.surface, jpeg!!.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    telemetry.event(sessionId, "session_configured", sizes)
                    if (!active) { session.close(); return }
                    captureSession = session
                    try {
                        telemetry.event(sessionId, "repeating_submit", mapOf("zoomRequested" to zoomRatio))
                        session.setRepeatingRequest(previewRequest(camera, chars), liveCallback, handler)
                        relockFocus()
                        telemetry.event(sessionId, "configured", sizes)
                        report("Camera2 · LIVE", true)
                    } catch (e: Exception) { fail(e) }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    telemetry.event(sessionId, "configure_failed", sizes)
                    report("Camera2 stream combination rejected; select another camera", false)
                }
            }, handler)
        } catch (e: Exception) { fail(e) }
    }
    /** [afTrigger] and [aeTrigger] go on a one-shot capture only; the repeating request always leaves them IDLE. */
    private fun previewRequest(camera: CameraDevice, chars: CameraCharacteristics, afTrigger: Int? = null, aeTrigger: Int? = null): CaptureRequest =
        camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface!!); addTarget(yuv!!.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, afMode(chars))
            spec?.fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            applyZoom(this, chars)
            if (spec == null) applyLiveControls(controls)
            afTrigger?.let { set(CaptureRequest.CONTROL_AF_TRIGGER, it) }
            aeTrigger?.let { set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, it) }
            setTag("preview")
        }.build()
    /** The LIVE recording request. Zoom and the controls change it in place: the targets stay preview + encoder. */
    private fun liveRecordRequest(camera: CameraDevice, c: CameraCharacteristics, recorder: Surface, afTrigger: Int? = null): CaptureRequest =
        camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface!!); addTarget(recorder)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            val modes = c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
            set(CaptureRequest.CONTROL_AF_MODE, if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in modes)
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CaptureRequest.CONTROL_AF_MODE_OFF)
            applyZoom(this, c)
            applyLiveControls(controls)
            afTrigger?.let { set(CaptureRequest.CONTROL_AF_TRIGGER, it) }
            setTag("recording")
        }.build()
    /** Whichever LIVE request is repeating now: the recording one while the recorder runs, the preview one otherwise. */
    private fun repeatingRequest(camera: CameraDevice, c: CameraCharacteristics, afTrigger: Int? = null, aeTrigger: Int? = null): CaptureRequest =
        recorderSurface?.let { liveRecordRequest(camera, c, it, afTrigger) } ?: previewRequest(camera, c, afTrigger, aeTrigger)
    /**
     * Runs [submit] against the current session, or skips it when there is no session to change. A recording that
     * is still being configured or already stopping has none that may be touched; the next session reads the
     * current zoom and controls when it configures, so a skipped input is not lost. A session closed under us by
     * a stop that raced the input is logged, not reported as a camera failure.
     */
    private fun withLiveSession(kind: String, submit: (CameraDevice, CameraCaptureSession, CameraCharacteristics) -> Unit) {
        val camera = device; val session = captureSession; val c = chars
        if (camera == null || session == null || c == null || !active || spec != null) return
        if (videoBusy && (recorderSurface == null || videoStopRequested)) { telemetry.event(sessionId, "request_deferred", mapOf("for" to kind)); return }
        try { submit(camera, session, c) }
        catch (e: IllegalStateException) { telemetry.event(sessionId, "request_skipped", mapOf("for" to kind, "reason" to e.toString())) }
        catch (e: CameraAccessException) { telemetry.event(sessionId, "request_skipped", mapOf("for" to kind, "reason" to e.toString())) }
        catch (e: Exception) { fail(e) }
    }
    private fun submitRepeating(kind: String, values: Map<String, Any?>) = withLiveSession(kind) { camera, session, c ->
        telemetry.event(sessionId, kind, values + mapOf("api" to "setRepeatingRequest", "recording" to (recorderSurface != null)))
        session.setRepeatingRequest(repeatingRequest(camera, c), liveCallback, handler)
    }
    /**
     * AF lock is the continuous AF mode's trigger transition (#169): START scans once and holds the lens
     * (FOCUSED_LOCKED or NOT_FOCUSED_LOCKED in the results) until CANCEL. The trigger goes on a single capture; the
     * repeating request keeps the same AF mode with the trigger IDLE, which leaves the lock in place.
     */
    private fun sendAfTrigger(start: Boolean) = withLiveSession("af_trigger") { camera, session, c ->
        telemetry.event(sessionId, "af_trigger", mapOf("trigger" to if (start) "START" else "CANCEL"))
        val trigger = if (start) CaptureRequest.CONTROL_AF_TRIGGER_START else CaptureRequest.CONTROL_AF_TRIGGER_CANCEL
        session.capture(repeatingRequest(camera, c, afTrigger = trigger), liveCallback, handler)
    }
    /** A new session (recording start or stop) may not keep the old AF state, so a held lock is taken again. */
    private fun relockFocus() { if (spec == null && controls.afLock) sendAfTrigger(true) }
    override fun setControls(next: LiveControls) {
        handler.post {
            val old = controls
            controls = next
            submitRepeating("controls_set", mapOf("evIndex" to next.evIndex, "aeLock" to next.aeLock, "afLock" to next.afLock, "flash" to next.flash.name))
            if (old.afLock != next.afLock) sendAfTrigger(next.afLock)
        }
    }
    /** API 30+ uses CONTROL_ZOOM_RATIO (ultra-wide below 1x possible). Older devices crop the active array, so only >= 1x. */
    private fun applyZoom(builder: CaptureRequest.Builder, chars: CameraCharacteristics) {
        val ratio = zoomRatio
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val range = chars[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, if (range != null) range.clamp(ratio) else 1f)
            return
        }
        val active = chars[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE] ?: return
        val max = chars[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM] ?: 1f
        val r = ratio.coerceIn(1f, max)
        val w = (active.width() / r).toInt(); val h = (active.height() / r).toInt()
        val left = active.left + (active.width() - w) / 2; val top = active.top + (active.height() - h) / 2
        builder.set(CaptureRequest.SCALER_CROP_REGION, android.graphics.Rect(left, top, left + w, top + h))
    }
    /**
     * Also works while recording (#174): the recording request is rebuilt with the new ratio and the same preview
     * and encoder targets. The ratio is stored at once and one submission is queued; inputs that arrive before it
     * runs only move the stored ratio, so a fast drag sends one request per camera-thread turn, not one per tap.
     */
    override fun setZoom(ratio: Float) {
        zoomRatio = ratio
        if (!zoomQueued.compareAndSet(false, true)) return
        handler.post {
            zoomQueued.set(false)
            submitRepeating("zoom_set", mapOf("zoomRequested" to zoomRatio))
        }
    }
    private fun afMode(chars: CameraCharacteristics): Int {
        val modes = chars[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
        return if (modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE else CaptureRequest.CONTROL_AF_MODE_OFF
    }
    private fun reader(size: Size, format: Int, stream: String): ImageReader =
        ImageReader.newInstance(size.width, size.height, format, 3).also { reader ->
            reader.setOnImageAvailableListener({ source ->
                if (source !== yuv && source !== jpeg) return@setOnImageAvailableListener
                try {
                    // Drain in order while a still is pending: acquireLatestImage can discard its YUV frame.
                    val next = if (photo != null) source.acquireNextImage() else source.acquireLatestImage()
                    next?.use { image ->
                        if (active) telemetry.image(sessionId, image.timestamp, image.width, image.height, image.format, stream)
                        if (active && !previewSeen && format == ImageFormat.YUV_420_888) {
                            previewSeen = true
                            main.post { if (active) previewReady() }
                        }
                        val pending = photo
                        if (active && pending != null) {
                            if (format == ImageFormat.JPEG) {
                                pending.pair.jpeg(image.timestamp, ByteArray(image.planes[0].buffer.remaining()).also { image.planes[0].buffer.get(it) })
                            } else if (pending.pair.accepts(image.timestamp)) {
                                val crop = image.cropRect
                                pending.pair.yuv(image.timestamp, YuvFrame(YuvPacking.nv21(image.planes.map {
                                    YuvPacking.Plane(it.buffer, it.rowStride, it.pixelStride)
                                }, crop.left, crop.top, crop.width(), crop.height()), crop.width(), crop.height()))
                            }
                            savePhotoIfComplete(pending)
                        } else if (stream == "still" && spec != null) {
                            photoInFlight = false; report("Camera2 · capture received", true)
                        }
                    }
                } catch (e: Exception) { photo?.let { deliverPhoto(it, Result.failure(e)) }; photo = null; photoInFlight = false; if (active) { fail(e); report("Capture failed: ${e.message} · retry", true) } }
            }, handler)
        }
    override fun capture() {
        requestCapture(null, null)
    }
    override fun capturePhoto(requestId: String, done: (Result<PhotoResult>) -> Unit) = requestCapture(requestId, done)

    private fun deliverPhoto(pending: Photo, result: Result<PhotoResult>) {
        if (pending.delivered.compareAndSet(false, true)) main.post { pending.done?.invoke(result) }
    }

    private fun requestCapture(requestId: String?, done: ((Result<PhotoResult>) -> Unit)?) {
        handler.post {
            if (device == null || captureSession == null || !active || photoInFlight || videoBusy || (done != null && spec != null)) {
                main.post { done?.invoke(Result.failure(IllegalStateException("Camera not ready or busy"))) }
                return@post
            }
            if (spec == null && controls.needsPrecapture) { photoInFlight = true; precapture { shoot(requestId, done) } }
            else shoot(requestId, done)
        }
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
                if (reason == "timeout") report("플래시 측광이 3초 안에 끝나지 않아 그대로 촬영합니다", false)
                then()
            }
        }
        precaptureFinish = finish
        report("플래시 측광 중…", false)
        telemetry.event(sessionId, "precapture_trigger", mapOf("flash" to controls.flash.name))
        withLiveSession("precapture_trigger") { camera, session, c ->
            session.capture(previewRequest(camera, c, aeTrigger = CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(s: CameraCaptureSession, r: CaptureRequest, timestamp: Long, frameNumber: Long) =
                    callback.onCaptureStarted(s, r, timestamp, frameNumber)
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    callback.onCaptureCompleted(s, r, result)
                    if (finished) return
                    if (watch.onResult(result[CaptureResult.CONTROL_AE_STATE])) finish("settled")
                    else resultHook = { next -> if (watch.onResult(next[CaptureResult.CONTROL_AE_STATE])) finish("settled") }
                }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                    callback.onCaptureFailed(s, r, failure); finish("trigger_failed")
                }
            }, handler)
        }
        handler.postDelayed({ if (!finished) { telemetry.event(sessionId, "precapture_timeout"); finish("timeout") } }, 3000)
    }

    private fun shoot(requestId: String?, done: ((Result<PhotoResult>) -> Unit)?) {
        val camera = device
        val session = captureSession
        if (camera == null || session == null || !active) {
            photoInFlight = false
            main.post { done?.invoke(Result.failure(IllegalStateException("Camera closed before capture"))) }
            return
        }
        try {
            val tag = "still-${android.os.SystemClock.elapsedRealtimeNanos()}"
            val c = chars ?: manager.getCameraCharacteristics(cameraId)
            val pending = if (spec == null) Photo(library.name(), outputRotation(c), requestId, done) else null
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jpeg!!.surface)
                if (pending != null) {
                    addTarget(yuv!!.surface)
                    set(CaptureRequest.JPEG_ORIENTATION, pending.rotation)
                    set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                }
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, afMode(c))
                applyZoom(this, c)
                // Same AE mode, EV, lock and torch as the preview, so the still is exposed as the preview showed it.
                if (spec == null) applyLiveControls(controls)
                setTag(tag)
            }.build()
            photoInFlight = true
            photo = pending
            if (pending != null) report("YUV + JPEG 촬영 중…", false)
            telemetry.event(sessionId, "capture_submit", mapOf("requestTag" to tag, "api" to "CameraCaptureSession.capture", "zoomRequested" to zoomRatio))
            session.capture(request, if (pending == null) callback else photoCallback(pending), handler)
            handler.postDelayed({
                if (photoInFlight && active && (pending == null || photo === pending)) {
                    pending?.let { deliverPhoto(it, Result.failure(IllegalStateException("Capture timed out"))) }
                    photo = null; photoInFlight = false; telemetry.event(sessionId, "capture_timeout")
                    report("Capture timed out (5s) · retry", spec == null)
                }
            }, 5000)
        } catch (e: Exception) {
            val pending = photo
            if (pending != null) deliverPhoto(pending, Result.failure(e)) else main.post { done?.invoke(Result.failure(e)) }
            photo = null; photoInFlight = false; fail(e); if (spec == null) report("Capture failed: ${e.message} · retry", true)
        }
    }

    private fun photoCallback(pending: Photo) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            callback.onCaptureStarted(session, request, timestamp, frameNumber)
            if (photo === pending) { pending.pair.timestamp = timestamp; savePhotoIfComplete(pending) }
        }
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            callback.onCaptureCompleted(session, request, result)
            if (photo === pending) { result[CaptureResult.SENSOR_TIMESTAMP]?.let { pending.pair.timestamp = it }; savePhotoIfComplete(pending) }
        }
        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            callback.onCaptureFailed(session, request, failure)
            if (photo === pending) { deliverPhoto(pending, Result.failure(IllegalStateException("Capture failed"))); photo = null; photoInFlight = false; report("Capture failed · retry", true) }
        }
        override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
            callback.onCaptureBufferLost(session, request, target, frameNumber)
            if (photo === pending) { deliverPhoto(pending, Result.failure(IllegalStateException("Capture buffer lost"))); photo = null; photoInFlight = false; report("Capture buffer lost · retry", true) }
        }
    }

    private fun savePhotoIfComplete(pending: Photo) {
        val timestamp = pending.pair.timestamp ?: return
        val (yuvFrame, jpegBytes) = pending.pair.complete() ?: return
        photo = null // Keep photoInFlight until the pair has been written.
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
                if (!active || result.isFailure) {
                    val message = result.fold({ "갤러리에 YUV · JPEG 사진 2장을 저장했습니다" }, { "사진 저장 실패: ${it.message}" })
                    android.widget.Toast.makeText(context.applicationContext, message, android.widget.Toast.LENGTH_LONG).show()
                }
            }
            handler.post {
                photoInFlight = false
                result.fold({
                    report("갤러리에 YUV · JPEG 사진 2장을 저장했습니다", true)
                }, { report("사진 저장 실패: ${it.message} · 다시 촬영할 수 있습니다", true) })
            }
        }
    }

    private fun outputRotation(c: CameraCharacteristics): Int {
        val degrees = when (view.display?.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        val sensor = c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0
        return (sensor + if (c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT) degrees else -degrees + 360) % 360
    }

    @Suppress("DEPRECATION")
    override fun startRecording(audio: Boolean, started: () -> Unit, done: ((Result<android.net.Uri>) -> Unit)?) {
        handler.post {
            val camera = device
            if (camera == null || !active || spec != null || photoInFlight || videoBusy || captureSession == null) {
                main.post { done?.invoke(Result.failure(IllegalStateException("Camera is not ready to record"))) }
                return@post
            }
            videoBusy = true
            videoDone = done; videoStopRequested = false; videoFailure = null
            report("녹화를 준비하고 있습니다…", false)
            try {
                val c = chars ?: error("Camera characteristics unavailable")
                val sizes = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(MediaRecorder::class.java)
                val size = choose(sizes.filter { it.width >= it.height }.toTypedArray(), 1920L * 1080)
                val file = File.createTempFile("hal_recording_", ".mp4", context.cacheDir).also { videoFile = it }
                val recorder = (if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()).also { videoRecorder = it }
                recorder.apply {
                    if (audio) setAudioSource(MediaRecorder.AudioSource.MIC)
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(file.absolutePath)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    if (audio) setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setVideoSize(size.width, size.height)
                    setVideoFrameRate(30)
                    setVideoEncodingBitRate(10_000_000)
                    if (audio) {
                        setAudioEncodingBitRate(128_000)
                        setAudioSamplingRate(44_100)
                    }
                    setOrientationHint(outputRotation(c))
                    setOnErrorListener { _, what, extra -> handler.post {
                        telemetry.event(sessionId, "recording_error", mapOf("what" to what, "extra" to extra))
                        videoFailure = IllegalStateException("Recorder error $what/$extra")
                        stopRecording()
                    } }
                    prepare()
                }
                camera.createCaptureSession(listOf(previewSurface!!, recorder.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!active || videoStopRequested) { session.close(); return }
                        captureSession = session
                        try {
                            recorderSurface = recorder.surface
                            session.setRepeatingRequest(liveRecordRequest(camera, c, recorder.surface), liveCallback, handler)
                            relockFocus()
                            recorder.start(); videoStarted = true
                            telemetry.event(sessionId, "recording_started", mapOf("size" to size.toString(), "audio" to audio))
                            main.post { if (active) { recordingState(true); started() } }
                            report(if (audio) "REC · 영상과 소리를 녹화하고 있습니다" else "REC · 영상을 녹화하고 있습니다", false)
                        } catch (e: Exception) { videoFailure = e; fail(e); session.close() }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        videoFailure = IllegalStateException("Recording stream configuration rejected")
                        report("녹화 스트림 구성을 지원하지 않습니다", false)
                        session.close()
                    }
                    override fun onClosed(session: CameraCaptureSession) {
                        if (captureSession === session) captureSession = null
                        recorderSurface = null
                        finishVideo()
                        if (active) { device?.let { configure(it) } }
                    }
                }, handler)
            } catch (e: Exception) {
                videoFailure = e
                finishVideo()
                report("녹화 준비 실패: ${e.message} · 다시 시도할 수 있습니다", captureSession != null)
            }
        }
    }

    // ---- Benchmark RECORD stage (docs/PLAN-Recording-v0.1.md 6) ----
    //
    // The four calls below are what BenchmarkRunner.Driver needs; the state machine itself lives in
    // [BenchmarkRecorder], which runs on this engine's camera thread through its Host.

    fun prepareBenchmarkRecording(iteration: Int) = bench.prepare(iteration)

    fun startBenchmarkRecording() = bench.start()

    fun stopBenchmarkRecording() = bench.stop()

    fun abortBenchmarkRecording() = bench.abort()

    /**
     * The recording request. [recording] decides the targets: before the recorder has started only the preview
     * is fed, afterwards the recorder surface joins and the request carries the cycle's tag so the extractor can
     * tell recording frames from the preview frames that came before them.
     */
    private fun recordRequest(camera: CameraDevice, c: CameraCharacteristics, recorderSurface: Surface, recording: Boolean, iteration: Int): CaptureRequest =
        camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface!!)
            if (recording) addTarget(recorderSurface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, afMode(c))
            spec?.fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            applyZoom(this, c)
            setTag(if (recording) RecordSpec.tag(iteration) else RecordSpec.prepareTag(iteration))
        }.build()

    override fun stopRecording() {
        handler.post {
            if (!videoBusy) return@post
            videoStopRequested = true
            report("녹화를 저장하고 있습니다…", false)
            captureSession?.close()
        }
    }

    private fun finishVideo() {
        if (videoRecorder == null && videoFile == null && !videoBusy) return
        val recorder = videoRecorder
        videoRecorder = null
        val file = videoFile; videoFile = null
        val done = videoDone; videoDone = null
        val failure = videoFailure; videoFailure = null
        val wasStarted = videoStarted
        val stopped = wasStarted && recorder != null && runCatching { recorder.stop() }.isSuccess
        runCatching { recorder?.reset() }; runCatching { recorder?.release() }
        recorderSurface = null
        videoStarted = false; videoBusy = false; videoStopRequested = false
        main.post { recordingState(false) }
        if (stopped && file != null) {
            mediaIo.execute {
                try {
                    val uri = library.saveVideo(file)
                    telemetry.event(sessionId, "video_saved", mapOf("uri" to uri.toString()))
                    main.post {
                        done?.invoke(if (failure == null) Result.success(uri) else Result.failure(failure))
                        // Same place as the photo notice: a toast at the bottom covered the photo/video mode buttons.
                        // Only a camera already closed has no notice line left, so that case keeps the toast.
                        if (active) status("갤러리에 동영상을 저장했습니다", true)
                        else android.widget.Toast.makeText(context.applicationContext, "갤러리에 동영상을 저장했습니다", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    main.post {
                        done?.invoke(Result.failure(e))
                        android.widget.Toast.makeText(context.applicationContext, "동영상 저장 실패: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                } finally { file.delete() }
            }
        } else {
            file?.delete()
            main.post { done?.invoke(Result.failure(failure ?: IllegalStateException("Recording did not produce a playable video; record for longer before stopping"))) }
            if (wasStarted) main.post { android.widget.Toast.makeText(context.applicationContext, "녹화가 너무 짧거나 실패하여 동영상을 저장하지 못했습니다", android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    override fun close(done: () -> Unit) {
        active = false
        view.surfaceTextureListener = null
        if (finished) { main.post(done); return }
        handler.post {
            closeDone = done
            captureSession?.close()
            captureSession = null
            device?.close()
            if (device == null && !opening) finishClose()
        }
    }
    private fun finishClose() {
        if (finished) return
        finished = true
        precaptureFinish?.invoke("closed")
        photo?.let { deliverPhoto(it, Result.failure(IllegalStateException("Camera closed before capture completed"))) }
        photo = null
        finishVideo()
        bench.release()
        captureSession?.close(); captureSession = null
        yuv?.close(); yuv = null
        jpeg?.close(); jpeg = null
        previewSurface?.release(); previewSurface = null
        telemetry.event(sessionId, "closed")
        closeDone?.let { main.post(it) }
        thread.quitSafely()
        mediaIo.shutdown()
    }
    private fun fail(e: Exception) { telemetry.event(sessionId, "camera_error", mapOf("message" to e.toString())); report("Camera2: ${e.message}", false) }
    private fun report(message: String, ok: Boolean) { main.post { if (active) status(message, ok) } }
    @Suppress("DEPRECATION", "UNUSED_PARAMETER")
    private fun transform(size: Size, chars: CameraCharacteristics) {
        if (!active || view.width == 0) return
        val rotation = view.display?.rotation ?: Surface.ROTATION_0
        val w = view.width.toFloat(); val h = view.height.toFloat()
        val cx = w / 2; val cy = h / 2
        val matrix = Matrix()
        // The camera pipeline already rotates buffers (and mirrors front cameras) for the device's natural orientation,
        // so in portrait a 1280x720 buffer is shown as 720x1280 stretched to the view. Only undo the stretch and fill-crop.
        // In landscape the display itself is rotated, so follow the Camera2Basic sample: map, scale, then rotate.
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
        view.setTransform(matrix)
    }
}
