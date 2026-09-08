package dev.cameradoctor.camera

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
import dev.cameradoctor.telemetry.Telemetry

class Camera2Engine(
    private val context: Context,
    private val view: TextureView,
    private val cameraId: String,
    private val sessionId: String,
    private val telemetry: Telemetry,
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
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
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
            val chars = manager.getCameraCharacteristics(cameraId)
            val map = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] ?: error("No stream configuration")
            val size = choose(map.getOutputSizes(SurfaceTexture::class.java), 1280L * 720)
            val yuvSize = choose(map.getOutputSizes(ImageFormat.YUV_420_888), 640L * 480)
            val jpegSize = choose(map.getOutputSizes(ImageFormat.JPEG), 1920L * 1080)
            val texture = view.surfaceTexture ?: error("Preview surface unavailable")
            texture.setDefaultBufferSize(size.width, size.height)
            previewSurface = Surface(texture)
            main.post { transform(size, chars) }
            yuv = reader(yuvSize, ImageFormat.YUV_420_888, "analysis_acquire_latest")
            jpeg = reader(jpegSize, ImageFormat.JPEG, "still")
            val sizes = mapOf("preview" to size.toString(), "analysis" to yuvSize.toString(), "jpeg" to jpegSize.toString())
            telemetry.sessions.computeIfPresent(sessionId) { _, old -> old + mapOf("negotiatedStreams" to sizes) }
            telemetry.event(sessionId, "configure_requested", sizes)
            camera.createCaptureSession(listOf(previewSurface!!, yuv!!.surface, jpeg!!.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!active) { session.close(); return }
                    captureSession = session
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(previewSurface!!); addTarget(yuv!!.surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, afMode(chars))
                            setTag("preview")
                        }.build()
                        telemetry.event(sessionId, "repeating_submit")
                        session.setRepeatingRequest(request, callback, handler)
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
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(jpeg!!.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, afMode(manager.getCameraCharacteristics(cameraId)))
                    setTag(tag)
                }.build()
                photoInFlight = true
                telemetry.event(sessionId, "capture_submit", mapOf("requestTag" to tag, "api" to "CameraCaptureSession.capture"))
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
    @Suppress("DEPRECATION")
    private fun transform(size: Size, chars: CameraCharacteristics) {
        if (!active || view.width == 0) return
        val displayDegrees = (view.display?.rotation ?: Surface.ROTATION_0) * 90
        val sensorDegrees = chars[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0
        val front = chars[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT
        val relative = (sensorDegrees - (if (front) -displayDegrees else displayDegrees) + 360) % 360
        val w = view.width.toFloat(); val h = view.height.toFloat()
        val matrix = Matrix()
        // Undo TextureView's default stretch, rotate around the center, then fill-crop uniformly.
        matrix.setScale(size.width / w, size.height / h, w / 2, h / 2)
        matrix.postRotate(relative.toFloat(), w / 2, h / 2)
        val rotatedW = if (relative % 180 == 0) size.width else size.height
        val rotatedH = if (relative % 180 == 0) size.height else size.width
        val scale = maxOf(w / rotatedW, h / rotatedH)
        matrix.postScale(if (front) -scale else scale, scale, w / 2, h / 2)
        view.setTransform(matrix)
    }
}
