package dev.halcamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
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
    private var photoInFlight = false
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
            if (!active) return@post
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
                try {
                    source.acquireLatestImage()?.use { image ->
                        if (active) telemetry.image(sessionId, image.timestamp, image.width, image.height, image.format, stream)
                        if (stream == "still") { photoInFlight = false; report("Camera2 · capture received", true) }
                    }
                } catch (e: IllegalStateException) { if (active) fail(e) }
            }, handler)
        }
    override fun capture() {
        handler.post {
            val camera = device ?: return@post
            val session = captureSession ?: return@post
            if (!active || photoInFlight) return@post
            try {
                val tag = "still-${android.os.SystemClock.elapsedRealtimeNanos()}"
                val c = chars ?: manager.getCameraCharacteristics(cameraId)
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(jpeg!!.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, afMode(c))
                    applyZoom(this, c)
                    setTag(tag)
                }.build()
                photoInFlight = true
                telemetry.event(sessionId, "capture_submit", mapOf("requestTag" to tag, "api" to "CameraCaptureSession.capture", "zoomRequested" to zoomRatio))
                session.capture(request, callback, handler)
                handler.postDelayed({ if (photoInFlight && active) { photoInFlight = false; telemetry.event(sessionId, "capture_timeout"); report("Capture timed out (5s)", false) } }, 5000)
            } catch (e: Exception) { photoInFlight = false; fail(e) }
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
        captureSession?.close(); captureSession = null
        yuv?.close(); yuv = null
        jpeg?.close(); jpeg = null
        previewSurface?.release(); previewSurface = null
        telemetry.event(sessionId, "closed")
        closeDone?.let { main.post(it) }
        thread.quitSafely()
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
