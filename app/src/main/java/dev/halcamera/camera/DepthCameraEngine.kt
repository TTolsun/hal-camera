package dev.halcamera.camera

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import dev.halcamera.telemetry.Telemetry
import java.util.concurrent.atomic.AtomicBoolean

/** LIVE-only depth session. No color targets, recording, photo saving or benchmark inputs. */
class DepthCameraEngine(
    private val manager: CameraManager,
    private val cameraId: String,
    private val size: LiveSize,
    private val sessionId: String,
    private val telemetry: Telemetry,
    private val releasedCameraId: String?,
    private val frame: (DepthFrame) -> Unit,
    private val status: (String, Boolean) -> Unit,
) : CameraEngine {
    private val thread = HandlerThread("HAL-depth").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active = true
    private var opening = false
    private var finished = false
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var availability: CameraManager.AvailabilityCallback? = null
    private var lastFrameMs: Long? = null
    private val pendingFrame = AtomicBoolean(false)
    private val closers = mutableListOf<() -> Unit>()
    private val openAfterRelease = Runnable { stopWaiting(); open() }
    private val firstFrameTimeout = Runnable { if (active && lastFrameMs == null) fail("No DEPTH16 frames received within 8 seconds.") }

    override fun start() {
        handler.post {
            if (!active) return@post
            try {
                val support = depthSupport(manager.getCameraCharacteristics(cameraId))
                require(support.available && size in support.sizes) { support.unavailableReason ?: "Unsupported DEPTH16 resolution." }
                telemetry.registerSession(sessionId, "Camera2 · Depth", manager, cameraId)
                if (releasedCameraId != null && releasedCameraId in manager.cameraIdList) {
                    availability = object : CameraManager.AvailabilityCallback() {
                        override fun onCameraAvailable(id: String) {
                            if (id == releasedCameraId && availability != null) openAfterRelease.run()
                        }
                    }.also { manager.registerAvailabilityCallback(it, handler) }
                    handler.postDelayed(openAfterRelease, CameraReleaseWait.LIMIT_MS)
                } else open()
            } catch (e: Exception) { fail(e.message ?: "Depth unavailable") }
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
                    opening = false
                    device = camera
                    if (!active) camera.close() else configure(camera)
                }
                override fun onDisconnected(camera: CameraDevice) = lost(camera, "Depth camera disconnected")
                override fun onError(camera: CameraDevice, error: Int) = lost(camera, "Depth camera error $error")
                private fun lost(camera: CameraDevice, reason: String) {
                    opening = false
                    device = camera
                    if (active) fail(reason) else camera.close()
                }
                override fun onClosed(camera: CameraDevice) {
                    if (device === camera) device = null
                    finishClose()
                }
            }, handler)
        } catch (e: Exception) { opening = false; fail(e.message ?: "Cannot open depth camera") }
    }

    @Suppress("DEPRECATION")
    private fun configure(camera: CameraDevice) {
        try {
            val output = ImageReader.newInstance(size.width, size.height, ImageFormat.DEPTH16, 3)
            reader = output
            output.setOnImageAvailableListener({ source ->
                try {
                    source.acquireLatestImage()?.use { image ->
                        if (!active) return@use
                        telemetry.image(sessionId, image.timestamp, image.width, image.height, image.format, "depth")
                        val now = SystemClock.elapsedRealtime()
                        if (lastFrameMs?.let { now - it < 100 } == true || !pendingFrame.compareAndSet(false, true)) return@use
                        try {
                            val plane = image.planes[0]
                            val crop = image.cropRect
                            val copy = DepthFrame.render(plane.buffer, crop.width(), crop.height(), plane.rowStride, plane.pixelStride, crop.left, crop.top)
                            lastFrameMs = now
                            main.post {
                                try { if (active) frame(copy) } finally { pendingFrame.set(false) }
                            }
                        } catch (e: Exception) { pendingFrame.set(false); throw e }
                    }
                } catch (e: Exception) { if (active) fail("DEPTH16 read failed: ${e.message}") }
            }, handler)
            telemetry.configureCallbackStreams(sessionId, listOf(mapOf("id" to "depth", "kind" to "DEPTH16", "label" to "Depth",
                "eventKind" to "image_available", "repeating" to true, "observable" to true, "width" to size.width, "height" to size.height)))
            camera.createCaptureSession(listOf(output.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(value: CameraCaptureSession) {
                    if (!active || device !== camera) { value.close(); return }
                    session = value
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(output.surface)
                            setTag("depth")
                        }.build()
                        value.setRepeatingRequest(request, telemetry.callback(sessionId, { listOf("depth") }) { active }, handler)
                        telemetry.sessions.computeIfPresent(sessionId) { _, old -> old + mapOf("negotiatedStreams" to mapOf("depth" to size.toString())) }
                        main.post { if (active) status("DEPTH16 · $size · LIVE", true) }
                        handler.postDelayed(firstFrameTimeout, 8000)
                    } catch (e: Exception) { fail("Depth request failed: ${e.message}") }
                }
                override fun onConfigureFailed(value: CameraCaptureSession) {
                    value.close()
                    if (active) fail("The HAL rejected the DEPTH16 session at $size.")
                }
            }, handler)
        } catch (e: Exception) { fail("Depth session failed: ${e.message}") }
    }

    override fun capture() = Unit
    override fun setZoom(ratio: Float) = Unit

    override fun close(done: () -> Unit) {
        active = false
        if (!handler.post {
            if (finished) main.post(done) else {
                closers += done
                release()
            }
        }) main.post(done)
    }

    private fun fail(reason: String) {
        active = false
        telemetry.event(sessionId, "depth_failed", mapOf("reason" to reason))
        main.post { status(reason, false) }
        release()
    }

    private fun stopWaiting() {
        handler.removeCallbacks(openAfterRelease)
        availability?.let { manager.unregisterAvailabilityCallback(it) }
        availability = null
    }

    private fun release() {
        stopWaiting()
        handler.removeCallbacks(firstFrameTimeout)
        session?.close(); session = null
        if (device != null) device?.close() else if (!opening) finishClose()
    }

    private fun finishClose() {
        if (device != null || opening || finished) return
        reader?.close(); reader = null
        finished = true
        val callbacks = closers.toList().also { closers.clear() }
        main.post { callbacks.forEach { it() } }
        thread.quitSafely()
    }
}
