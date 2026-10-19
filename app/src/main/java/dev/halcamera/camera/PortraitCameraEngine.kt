package dev.halcamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.os.*
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RequiresApi
import dev.halcamera.telemetry.Telemetry
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** LIVE-only OEM Bokeh session: one preview, one JPEG, no benchmark or manual-control contract. */
@RequiresApi(31)
class PortraitCameraEngine(
    context: Context,
    private val view: TextureView,
    private val cameraId: String,
    private val config: PortraitConfig,
    private val sessionId: String,
    private val telemetry: Telemetry,
    private val releasedCameraId: String?,
    private val frame: () -> Unit,
    private val status: (String, Boolean) -> Unit,
) : CameraEngine, MediaCapture {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val library = MediaLibrary(context)
    private val thread = HandlerThread("HAL-portrait").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }
    private val io = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    override val mediaBusy get() = busy.get()
    @Volatile private var active = true
    @Volatile private var firstFrame = false
    private var opening = false
    private var finished = false
    private var device: CameraDevice? = null
    private var session: CameraExtensionSession? = null
    private var reader: ImageReader? = null
    private var surface: Surface? = null
    private var availability: CameraManager.AvailabilityCallback? = null
    private val closers = mutableListOf<() -> Unit>()
    private class Photo(val id: String, val done: (Result<PhotoResult>) -> Unit) {
        val delivered = AtomicBoolean(false)
    }
    private var pending: Photo? = null
    private val openAfterRelease = Runnable { stopWaiting(); open() }
    private val firstTimeout = Runnable { if (active && !firstFrame) fail("No Portrait preview frames within 15 seconds.") }
    private val captureTimeout = Runnable { if (pending != null) fail("Portrait capture timed out after 60 seconds. Select Photo to retry.") }

    override fun start() {
        main.post {
            if (!active) return@post
            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = prepare(texture)
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = fit()
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                    if (!active) return
                    firstFrame = true
                    frame()
                }
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    handler.post { if (active) fail("Portrait preview surface closed.") }
                    return true
                }
            }
            view.surfaceTexture?.let(::prepare)
        }
    }

    private fun fit() = view.fitPreview(android.util.Size(config.preview.width, config.preview.height))
    private fun prepare(texture: SurfaceTexture) {
        texture.setDefaultBufferSize(config.preview.width, config.preview.height)
        fit()
        handler.post {
            if (!active || surface != null) return@post
            try {
                val support = portraitSupport(manager, cameraId)
                require(support.available && config.preview in support.previews && config.jpeg in support.jpegs) {
                    support.unavailableReason ?: "Unsupported Portrait resolutions."
                }
                surface = Surface(texture)
                telemetry.registerSession(sessionId, "Camera2 · Portrait", manager, cameraId)
                if (releasedCameraId != null && releasedCameraId in manager.cameraIdList) {
                    availability = object : CameraManager.AvailabilityCallback() {
                        override fun onCameraAvailable(id: String) {
                            if (id == releasedCameraId && availability != null) openAfterRelease.run()
                        }
                    }.also { manager.registerAvailabilityCallback(it, handler) }
                    handler.postDelayed(openAfterRelease, CameraReleaseWait.LIMIT_MS)
                } else open()
            } catch (e: Exception) { fail(e.message ?: "Portrait unavailable") }
        }
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        if (!active || opening || device != null) return
        try {
            opening = true
            telemetry.event(sessionId, "open_call")
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opening = false; device = camera
                    if (!active) camera.close() else configure(camera)
                }
                override fun onDisconnected(camera: CameraDevice) = lost(camera, "Portrait camera disconnected")
                override fun onError(camera: CameraDevice, error: Int) = lost(camera, "Portrait camera error $error")
                private fun lost(camera: CameraDevice, reason: String) {
                    opening = false; device = camera
                    if (active) fail(reason) else camera.close()
                }
                override fun onClosed(camera: CameraDevice) {
                    if (device === camera) device = null
                    finishClose()
                }
            }, handler)
        } catch (e: Exception) { opening = false; fail("Cannot open Portrait camera: ${e.message}") }
    }

    private fun configure(camera: CameraDevice) {
        try {
            val output = ImageReader.newInstance(config.jpeg.width, config.jpeg.height, ImageFormat.JPEG, 2)
            reader = output
            output.setOnImageAvailableListener({ source ->
                try {
                    source.acquireNextImage()?.use { image ->
                        val photo = pending ?: return@use
                        if (!active) return@use
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                        val timestamp = image.timestamp
                        telemetry.image(sessionId, timestamp, image.width, image.height, image.format, "portrait_jpeg")
                        pending = null
                        handler.removeCallbacks(captureTimeout)
                        io.execute {
                            val result = runCatching {
                                val files = library.saveCapture(library.name(), null, bytes, mapOf(
                                    "requestId" to photo.id, "cameraId" to cameraId, "engine" to "Camera2",
                                    "effect" to "manufacturer_bokeh", "jpegSource" to "OEM_BOKEH",
                                    "previewSize" to config.preview.toString(), "jpegSize" to config.jpeg.toString(),
                                    "sensorTimestamp" to timestamp, "correlation" to "One extension capture in flight; OEM processed JPEG"))
                                PhotoResult(photo.id, files.first().name, timestamp, files.map { it.uri }, files)
                            }
                            deliver(photo, result)
                            main.post { if (active) status(result.fold({ "Saved Portrait JPEG" }, { "Portrait save failed: ${it.message}" }), true) }
                        }
                    }
                } catch (e: Exception) { if (active) fail("Portrait JPEG failed: ${e.message}") }
            }, handler)
            val outputs = listOf(OutputConfiguration(requireNotNull(surface)), OutputConfiguration(output.surface))
            camera.createExtensionSession(ExtensionSessionConfiguration(CameraExtensionCharacteristics.EXTENSION_BOKEH, outputs, executor,
                object : CameraExtensionSession.StateCallback() {
                    override fun onConfigured(value: CameraExtensionSession) {
                        if (!active || device !== camera) { value.close(); return }
                        session = value
                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(requireNotNull(surface)); setTag("portrait_preview")
                            }.build()
                            value.setRepeatingRequest(request, executor, object : CameraExtensionSession.ExtensionCaptureCallback() {
                                override fun onCaptureFailed(s: CameraExtensionSession, r: CaptureRequest) {
                                    if (active) fail("The manufacturer rejected the Portrait preview.")
                                }
                            })
                            telemetry.sessions.computeIfPresent(sessionId) { _, old -> old + mapOf("negotiatedStreams" to mapOf(
                                "preview" to config.preview.toString(), "jpeg" to config.jpeg.toString(), "portrait" to "Manufacturer Bokeh")) }
                            main.post { if (active) status("Portrait · Manufacturer Bokeh · LIVE", true) }
                            handler.postDelayed(firstTimeout, 15000)
                        } catch (e: Exception) { fail("Portrait preview failed: ${e.message}") }
                    }
                    override fun onConfigureFailed(value: CameraExtensionSession) {
                        runCatching { value.close() }
                        if (active) fail("The manufacturer rejected Portrait at P ${config.preview} / JPEG ${config.jpeg}. Select another resolution or Photo.")
                    }
                }))
        } catch (e: Exception) { fail("Portrait session failed: ${e.message}") }
    }

    override fun capture() = capturePhoto(UUID.randomUUID().toString()) { }
    override fun capturePhoto(requestId: String, done: (Result<PhotoResult>) -> Unit) {
        if (!active || !firstFrame || !busy.compareAndSet(false, true)) {
            main.post { done(Result.failure(IllegalStateException("Portrait camera not ready or busy"))) }; return
        }
        @Suppress("DEPRECATION")
        val displayRotation = view.display?.rotation ?: Surface.ROTATION_0
        val photo = Photo(requestId, done)
        if (!handler.post {
            pending = photo
            try {
                check(active) { "Portrait camera closed" }
                val camera = requireNotNull(device)
                val chars = manager.getCameraCharacteristics(cameraId)
                val degrees = displayRotation * 90
                val sensor = chars[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0
                val front = chars[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT
                val orientation = (sensor + if (front) degrees else -degrees + 360) % 360
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(requireNotNull(reader).surface); setTag(photo.id)
                    set(CaptureRequest.JPEG_ORIENTATION, orientation)
                }.build()
                telemetry.event(sessionId, "portrait_capture_submit", mapOf("requestId" to photo.id, "jpegOrientation" to orientation))
                main.post { if (active) status("Capturing Portrait…", false) }
                handler.postDelayed(captureTimeout, 60000)
                requireNotNull(session).capture(request, executor, object : CameraExtensionSession.ExtensionCaptureCallback() {
                    override fun onCaptureStarted(s: CameraExtensionSession, r: CaptureRequest, timestamp: Long) {
                        if (active) telemetry.event(sessionId, "portrait_capture_started", mapOf("requestId" to photo.id, "sensorTimestamp" to timestamp))
                    }
                    override fun onCaptureFailed(s: CameraExtensionSession, r: CaptureRequest) {
                        if (pending === photo) fail("Manufacturer Portrait capture failed. Select Photo to retry.")
                    }
                    override fun onCaptureSequenceAborted(s: CameraExtensionSession, sequenceId: Int) {
                        if (pending === photo) fail("Manufacturer Portrait capture aborted. Select Photo to retry.")
                    }
                })
            } catch (e: Exception) { fail("Portrait capture failed: ${e.message}") }
        }) deliver(photo, Result.failure(IllegalStateException("Portrait camera closed")))
    }

    private fun deliver(photo: Photo, result: Result<PhotoResult>) {
        if (photo.delivered.compareAndSet(false, true)) main.post { busy.set(false); photo.done(result) }
    }
    override fun setZoom(ratio: Float) = Unit
    override fun startRecording(audio: Boolean, started: () -> Unit, done: ((Result<android.net.Uri>) -> Unit)?) {
        main.post { done?.invoke(Result.failure(IllegalStateException("Manufacturer Portrait supports photos only."))) }
    }
    override fun stopRecording() = Unit
    override fun close(done: () -> Unit) {
        active = false
        if (!handler.post { if (finished) main.post(done) else { closers += done; release() } }) main.post(done)
    }
    private fun fail(reason: String) {
        active = false
        telemetry.event(sessionId, "portrait_failed", mapOf("reason" to reason))
        main.post { status(reason, false) }
        release()
    }
    private fun stopWaiting() {
        handler.removeCallbacks(openAfterRelease)
        availability?.let { manager.unregisterAvailabilityCallback(it) }; availability = null
    }
    private fun release() {
        stopWaiting(); handler.removeCallbacks(firstTimeout); handler.removeCallbacks(captureTimeout)
        pending?.let { deliver(it, Result.failure(IllegalStateException("Portrait closed before JPEG arrived"))) }; pending = null
        runCatching { session?.close() }; session = null
        if (device != null) device?.close() else if (!opening) finishClose()
    }
    private fun finishClose() {
        if (device != null || opening || finished) return
        finished = true
        reader?.close(); reader = null
        surface?.release(); surface = null
        val callbacks = closers.toList().also { closers.clear() }
        main.post { view.surfaceTextureListener = null; callbacks.forEach { it() } }
        io.shutdown(); thread.quitSafely()
    }
}
