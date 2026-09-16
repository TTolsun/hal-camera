package dev.halcamera.cts

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The Camera2 calls every transcribed case shares — open, configure, first frame, close — with the
 * CameraTestUtils timeouts, on one HandlerThread the runner owns. Every wait is blocking and throws
 * [IllegalStateException] carrying the CTS wording on timeout or error, so a runner turns it into a FAIL step
 * with the same message CTS would print. Call from the runner thread, never from [handler]'s thread.
 */
class Camera2Ops(private val manager: CameraManager, threadName: String) {
    private val thread = HandlerThread(threadName).apply { start() }
    val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }

    fun quit() { thread.quitSafely() }

    /** A device plus the latch its StateCallback trips on onClosed, so close() can wait the CTS close budget. */
    class OpenedCamera(val device: CameraDevice, private val closed: CountDownLatch, private val fault: AtomicReference<String?>) {
        val id: String get() = device.id
        /** onDisconnected/onError received since the open, in CTS words; null while the device is healthy. */
        val error: String? get() = fault.get()

        /** Close and wait for onClosed. Returns false when the callback did not arrive within the budget. */
        fun close(): Boolean {
            device.close()
            val inTime = closed.await(CAMERA_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!inTime) Log.w(TAG, "camera ${device.id} did not report closed in time")
            return inTime
        }
    }

    /** The activity has checked CAMERA before starting the run; a revoked permission surfaces as SecurityException in the step. */
    @SuppressLint("MissingPermission")
    fun open(cameraId: String): OpenedCamera {
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val ref = AtomicReference<CameraDevice?>()
        val fault = AtomicReference<String?>()
        // Set once the caller has given up waiting: a device that arrives after that is closed, not leaked.
        val abandoned = AtomicBoolean(false)
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (abandoned.get()) { Log.w(TAG, "camera $cameraId opened after the wait expired; closing"); camera.close(); return }
                ref.set(camera); opened.countDown()
            }
            override fun onClosed(camera: CameraDevice) { closed.countDown() }
            override fun onDisconnected(camera: CameraDevice) { fault.compareAndSet(null, "Camera $cameraId disconnected"); camera.close(); opened.countDown() }
            override fun onError(camera: CameraDevice, code: Int) { fault.compareAndSet(null, "Camera $cameraId error $code"); camera.close(); opened.countDown() }
        }, handler)
        if (!opened.await(CAMERA_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            abandoned.set(true)
            ref.getAndSet(null)?.close()
            error("Timeout waiting for the camera to open")
        }
        fault.get()?.let { error(it) }
        return OpenedCamera(ref.get()!!, closed, fault)
    }

    /** A configured session plus the latch its StateCallback trips on onClosed. */
    class Session(val session: CameraCaptureSession, private val closed: CountDownLatch) {
        /** Close and wait for onClosed. Returns false when the callback did not arrive within the budget. */
        fun close(): Boolean {
            session.close()
            return closed.await(SESSION_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** CameraTestUtils.configureCameraSessionWithParameters; [sessionParameters] is what CTS passes as the initial request. */
    fun configure(camera: CameraDevice, surfaces: List<Surface>, sessionParameters: CaptureRequest? = null): Session {
        val configured = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val ref = AtomicReference<CameraCaptureSession?>()
        val failed = AtomicBoolean(false)
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) { ref.set(session); configured.countDown() }
            override fun onConfigureFailed(session: CameraCaptureSession) { failed.set(true); configured.countDown() }
            override fun onClosed(session: CameraCaptureSession) { closed.countDown() }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, surfaces.map { OutputConfiguration(it) }, executor, callback)
            if (sessionParameters != null) config.sessionParameters = sessionParameters
            camera.createCaptureSession(config)
        } else {
            // API 26-27 has no session parameters; whatever they carried still travels in the repeating request.
            @Suppress("DEPRECATION")
            camera.createCaptureSession(surfaces, callback, handler)
        }
        check(configured.await(SESSION_CONFIGURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Timeout waiting for the session to configure" }
        check(!failed.get()) { "Camera session configuration failed" }
        return Session(ref.get()!!, closed)
    }

    /** The first completed frame of a repeating request, with the app clock at its start and completion. */
    class FirstFrame(val startedAtNs: Long, val completedAtNs: Long, val result: TotalCaptureResult)

    /**
     * Set [request] repeating and wait for its first onCaptureCompleted. The repeating request stays running
     * afterwards so the caller can keep the preview up, stop it, or close the session.
     */
    fun startRepeatingAndAwaitFirstFrame(session: CameraCaptureSession, request: CaptureRequest, timeoutMs: Long = CAPTURE_RESULT_TIMEOUT_MS): FirstFrame {
        val done = CountDownLatch(1)
        val started = AtomicReference<Long?>()
        val completed = AtomicReference<FirstFrame?>()
        val failure = AtomicReference<String?>()
        session.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(s: CameraCaptureSession, r: CaptureRequest, timestamp: Long, frameNumber: Long) {
                started.compareAndSet(null, SystemClock.elapsedRealtimeNanos())
            }
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                if (completed.compareAndSet(null, FirstFrame(started.get() ?: SystemClock.elapsedRealtimeNanos(), SystemClock.elapsedRealtimeNanos(), result))) done.countDown()
            }
            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                if (failure.compareAndSet(null, "Capture failed: reason ${f.reason}, frame ${f.frameNumber}")) done.countDown()
            }
        }, handler)
        check(done.await(timeoutMs, TimeUnit.MILLISECONDS)) { "Timeout waiting for the first capture result" }
        failure.get()?.let { error(it) }
        return completed.get()!!
    }

    /** One still capture: whether its result completed in time, and the image [reader] delivered for it, if any. */
    class Still(val resultReceived: Boolean, val image: android.media.Image?, val requestedAtNs: Long, val imageAtNs: Long?)

    /**
     * CameraTestUtils takePicture + waitForImage: submit [request] once and wait for its result, then for the
     * image on [reader], each within the CTS budget. A capture failure throws with its reason. The caller
     * closes the image.
     */
    fun captureStill(session: CameraCaptureSession, request: CaptureRequest, reader: JpegReader): Still {
        val done = CountDownLatch(1)
        val failure = AtomicReference<String?>()
        val requestedAt = SystemClock.elapsedRealtimeNanos()
        session.capture(request, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) { done.countDown() }
            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                failure.compareAndSet(null, "Capture failed: reason ${f.reason}, frame ${f.frameNumber}"); done.countDown()
            }
        }, handler)
        val resultReceived = done.await(CAPTURE_RESULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        failure.get()?.let { error(it) }
        val image = reader.next(CAPTURE_IMAGE_TIMEOUT_MS)
        return Still(resultReceived, image, requestedAt, if (image != null) SystemClock.elapsedRealtimeNanos() else null)
    }

    companion object {
        private const val TAG = "Camera2Ops"
        const val CAMERA_OPEN_TIMEOUT_MS = 3000L
        const val SESSION_CONFIGURE_TIMEOUT_MS = 3000L
        const val SESSION_CLOSE_TIMEOUT_MS = 3000L
        const val CAPTURE_RESULT_TIMEOUT_MS = 3000L
        const val CAPTURE_IMAGE_TIMEOUT_MS = 3000L
        const val CAMERA_CLOSE_TIMEOUT_MS = 3000L
        const val WAIT_FOR_SURFACE_CHANGE_TIMEOUT_MS = 1000L
    }
}
