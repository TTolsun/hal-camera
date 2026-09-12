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
) : CameraEngine {
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
    val mediaBusy: Boolean get() = photoInFlight || videoBusy
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
    @Volatile private var zoomRatio = 1f
    private var chars: CameraCharacteristics? = null
    private val callback = telemetry.callback(sessionId) { active }
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
                        session.setRepeatingRequest(previewRequest(camera, chars), callback, handler)
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
    private fun previewRequest(camera: CameraDevice, chars: CameraCharacteristics): CaptureRequest =
        camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface!!); addTarget(yuv!!.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, afMode(chars))
            spec?.fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            applyZoom(this, chars)
            setTag("preview")
        }.build()
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
    override fun setZoom(ratio: Float) {
        handler.post {
            val camera = device ?: return@post
            val session = captureSession ?: return@post
            val c = chars ?: return@post
            if (!active || videoBusy) return@post
            zoomRatio = ratio
            try {
                telemetry.event(sessionId, "zoom_set", mapOf("zoomRequested" to ratio, "api" to "setRepeatingRequest"))
                session.setRepeatingRequest(previewRequest(camera, c), callback, handler)
            } catch (e: Exception) { fail(e) }
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
    fun capturePhoto(requestId: String, done: (Result<PhotoResult>) -> Unit) = requestCapture(requestId, done)

    private fun deliverPhoto(pending: Photo, result: Result<PhotoResult>) {
        if (pending.delivered.compareAndSet(false, true)) main.post { pending.done?.invoke(result) }
    }

    private fun requestCapture(requestId: String?, done: ((Result<PhotoResult>) -> Unit)?) {
        handler.post {
            val camera = device
            val session = captureSession
            if (camera == null || session == null || !active || photoInFlight || videoBusy || (done != null && spec != null)) {
                main.post { done?.invoke(Result.failure(IllegalStateException("Camera not ready or busy"))) }
                return@post
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
    fun startRecording() {
        handler.post {
            val camera = device ?: return@post
            if (!active || spec != null || photoInFlight || videoBusy || captureSession == null) return@post
            videoBusy = true
            report("녹화를 준비하고 있습니다…", false)
            try {
                val c = chars ?: error("Camera characteristics unavailable")
                val sizes = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(MediaRecorder::class.java)
                val size = choose(sizes.filter { it.width >= it.height }.toTypedArray(), 1920L * 1080)
                val file = File.createTempFile("hal_recording_", ".mp4", context.cacheDir).also { videoFile = it }
                val recorder = (if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()).also { videoRecorder = it }
                recorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(file.absolutePath)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setVideoSize(size.width, size.height)
                    setVideoFrameRate(30)
                    setVideoEncodingBitRate(10_000_000)
                    setAudioEncodingBitRate(128_000)
                    setAudioSamplingRate(44_100)
                    setOrientationHint(outputRotation(c))
                    setOnErrorListener { _, what, extra -> handler.post {
                        telemetry.event(sessionId, "recording_error", mapOf("what" to what, "extra" to extra))
                        stopRecording()
                    } }
                    prepare()
                }
                camera.createCaptureSession(listOf(previewSurface!!, recorder.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!active) { session.close(); return }
                        captureSession = session
                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(previewSurface!!); addTarget(recorder.surface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                val modes = c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
                                set(CaptureRequest.CONTROL_AF_MODE, if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in modes)
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CaptureRequest.CONTROL_AF_MODE_OFF)
                                applyZoom(this, c)
                                setTag("recording")
                            }.build()
                            session.setRepeatingRequest(request, callback, handler)
                            recorder.start(); videoStarted = true
                            telemetry.event(sessionId, "recording_started", mapOf("size" to size.toString(), "audio" to true))
                            main.post { if (active) recordingState(true) }
                            report("REC · 영상과 소리를 녹화하고 있습니다", false)
                        } catch (e: Exception) { fail(e); session.close() }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        report("녹화 스트림 구성을 지원하지 않습니다", false)
                        session.close()
                    }
                    override fun onClosed(session: CameraCaptureSession) {
                        if (captureSession === session) captureSession = null
                        finishVideo()
                        if (active) { device?.let { configure(it) } }
                    }
                }, handler)
            } catch (e: Exception) {
                finishVideo()
                report("녹화 준비 실패: ${e.message} · 다시 시도할 수 있습니다", captureSession != null)
            }
        }
    }

    fun stopRecording() {
        handler.post {
            if (!videoBusy) return@post
            report("녹화를 저장하고 있습니다…", false)
            captureSession?.close()
        }
    }

    private fun finishVideo() {
        if (videoRecorder == null && videoFile == null && !videoBusy) return
        val recorder = videoRecorder
        videoRecorder = null
        val file = videoFile; videoFile = null
        val wasStarted = videoStarted
        val stopped = wasStarted && recorder != null && runCatching { recorder.stop() }.isSuccess
        runCatching { recorder?.reset() }; runCatching { recorder?.release() }
        videoStarted = false; videoBusy = false
        main.post { recordingState(false) }
        if (stopped && file != null) {
            mediaIo.execute {
                try {
                    val uri = library.saveVideo(file)
                    telemetry.event(sessionId, "video_saved", mapOf("uri" to uri.toString()))
                    main.post { android.widget.Toast.makeText(context.applicationContext, "갤러리에 동영상을 저장했습니다", android.widget.Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    main.post { android.widget.Toast.makeText(context.applicationContext, "동영상 저장 실패: ${e.message}", android.widget.Toast.LENGTH_LONG).show() }
                } finally { file.delete() }
            }
        } else {
            file?.delete()
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
        photo?.let { deliverPhoto(it, Result.failure(IllegalStateException("Camera closed before capture completed"))) }
        photo = null
        finishVideo()
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
