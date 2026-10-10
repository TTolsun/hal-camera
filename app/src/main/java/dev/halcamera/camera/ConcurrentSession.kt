package dev.halcamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.RequiresApi
import java.util.UUID
import java.util.concurrent.Executor

@RequiresApi(30)
fun readConcurrentPlans(manager: CameraManager, maxDevices: Int = Int.MAX_VALUE): List<ConcurrentPlan> {
    val combinations = manager.concurrentCameraIds
    val cameras = manager.cameraIdList.map { id ->
        val c = manager.getCameraCharacteristics(id)
        val map = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        ConcurrentCamera(id, c[CameraCharacteristics.LENS_FACING],
            map?.getOutputSizes(SurfaceTexture::class.java)?.map { LiveSize(it.width, it.height) }.orEmpty(),
            map?.getOutputSizes(ImageFormat.JPEG)?.map { LiveSize(it.width, it.height) }.orEmpty())
    }
    return ConcurrentPlanner.plans(30, combinations, cameras, maxDevices)
}

/** Owns independent devices; every callback and photo transaction is serialized on the worker. */
@RequiresApi(30)
class ConcurrentSession(
    context: Context,
    private val manager: CameraManager,
    private val plan: ConcurrentPlan,
    private val textures: List<SurfaceTexture>,
    private val listener: Listener,
) {
    interface Listener {
        fun onState(id: String, state: String)
        fun onReady()
        fun onFailed(reason: String)
        fun onPhoto(message: String)
    }
    private val thread = HandlerThread("HAL.Concurrent").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { worker.post(it) }
    private val lifecycle = ConcurrentLifecycle(plan.streams.map { it.camera.id })
    private val devices = mutableMapOf<String, CameraDevice>()
    private val sessions = mutableMapOf<String, CameraCaptureSession>()
    private val surfaces = mutableMapOf<String, Surface>()
    private val readers = mutableMapOf<String, ImageReader>()
    private val configs = mutableMapOf<String, SessionConfiguration>()
    private val timestamps = mutableMapOf<String, Int?>()
    private val closeCallbacks = mutableListOf<() -> Unit>()
    private val store = ConcurrentPhotoStore(context.applicationContext)
    @Volatile private var finished = false
    private var started = false
    private var capture: ConcurrentCapture<ConcurrentPhoto>? = null
    private val expected = mutableMapOf<String, Long>()
    private val images = mutableMapOf<String, MutableMap<Long, ByteArray>>()

    fun start() { worker.post {
        if (started || lifecycle.stopping) return@post
        started = true
        lease.acquire(this) { worker.post { startOwned() } }
    } }

    private fun startOwned() {
        if (lifecycle.stopping) return
        try {
            prepare()
            if (!manager.isConcurrentSessionConfigurationSupported(configs)) {
                fail("This camera combination is unavailable.")
                return
            }
            // All opens must be requested and completed before any createCaptureSession.
            plan.streams.forEach { open(it.camera.id) }
            worker.postDelayed({
                if (!lifecycle.stopping && !lifecycle.ready) fail("Timed out opening cameras.")
            }, 15_000)
        } catch (e: SecurityException) { fail("Camera permission unavailable: ${e.message}") }
        catch (e: Exception) { fail("Concurrent preflight failed: ${e.message}") }
    }

    private fun prepare() {
        plan.streams.forEachIndexed { index, stream ->
            val id = stream.camera.id
            state(id, "Checking requested ${stream.preview} preview + ${stream.photo} JPEG")
            timestamps[id] = manager.getCameraCharacteristics(id)[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE]
            textures[index].setDefaultBufferSize(stream.preview.width, stream.preview.height)
            val surface = Surface(textures[index]).also { surfaces[id] = it }
            val reader = ImageReader.newInstance(stream.photo.width, stream.photo.height, ImageFormat.JPEG, 3)
                .also { readers[id] = it }
            reader.setOnImageAvailableListener({ source ->
                try {
                    source.acquireNextImage()?.use { image ->
                        val pending = capture
                        if (pending != null && pending.outcomes[id] == null) {
                            val bytes = ByteArray(image.planes[0].buffer.remaining()).also { image.planes[0].buffer.get(it) }
                            val buffered = images.getOrPut(id) { linkedMapOf() }
                            buffered[image.timestamp] = bytes
                            while (buffered.size > 3) buffered.remove(buffered.keys.first())
                            matchImage(id)
                        }
                    }
                } catch (e: Exception) { fail("Camera $id image read failed: ${e.message}") }
            }, worker)
            configs[id] = SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(surface), OutputConfiguration(reader.surface)), executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (lifecycle.stopping) { session.close(); return }
                        sessions[id] = session
                        try {
                            val request = checkNotNull(devices[id]).createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                            }.build()
                            session.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                                    if (lifecycle.stopping || lifecycle.phases[id] == ConcurrentLifecycle.Phase.STREAMING) return
                                    lifecycle.streaming(id)
                                    state(id, "Active ${stream.preview} preview + ${stream.photo} JPEG")
                                    if (lifecycle.ready) main.post { listener.onReady() }
                                }
                            }, worker)
                        } catch (e: Exception) { fail("Camera $id preview failed: ${e.message}") }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        fail("Camera $id configure rejected ${stream.preview} preview + ${stream.photo} JPEG")
                    }
                })
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(id: String) {
        if (lifecycle.stopping) return
        lifecycle.opening(id)
        state(id, "Opening")
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    devices[id] = camera
                    lifecycle.opened(id)
                    if (lifecycle.stopping) { camera.close(); return }
                    state(id, "Open; waiting for peer")
                    if (lifecycle.canConfigure) {
                        try {
                            devices.forEach { (cameraId, device) ->
                                state(cameraId, "Configuring")
                                device.createCaptureSession(checkNotNull(configs[cameraId]))
                            }
                        } catch (e: Exception) { fail("Concurrent configure failed: ${e.message}") }
                    }
                }
                override fun onDisconnected(camera: CameraDevice) = cameraError(camera, "disconnected / camera ownership lost")
                override fun onError(camera: CameraDevice, error: Int) = cameraError(camera, when (error) {
                    ERROR_CAMERA_IN_USE -> "camera in use"
                    ERROR_MAX_CAMERAS_IN_USE -> "camera resource limit reached"
                    ERROR_CAMERA_DISABLED -> "camera disabled"
                    else -> "Camera2 error $error"
                })
                private fun cameraError(camera: CameraDevice, reason: String) {
                    devices[id] = camera
                    lifecycle.opened(id)
                    fail("Camera $id: $reason")
                    camera.close()
                }
                override fun onClosed(camera: CameraDevice) {
                    devices.remove(id)
                    lifecycle.closed(id)
                    state(id, "Closed")
                    finishIfClosed()
                }
            }, worker)
        } catch (e: Exception) {
            lifecycle.closed(id)
            fail("Camera $id open failed: ${e.message}")
        }
    }

    fun capture(displayDegrees: Int) { worker.post {
        if (!lifecycle.ready || capture != null) return@post
        val group = ConcurrentCapture<ConcurrentPhoto>("HAL_concurrent_${UUID.randomUUID()}", SystemClock.elapsedRealtimeNanos(), devices.keys.toList())
        capture = group
        images.clear(); expected.clear()
        plan.streams.forEach { stream ->
            val id = stream.camera.id
            try {
                val c = manager.getCameraCharacteristics(id)
                val rotation = ((c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0) +
                    if (stream.camera.facing == CameraLabel.FACING_FRONT) -displayDegrees else displayDegrees) % 360
                val request = checkNotNull(devices[id]).createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(checkNotNull(readers[id]).surface)
                    set(CaptureRequest.JPEG_ORIENTATION, (rotation + 360) % 360)
                    setTag(group.groupId)
                }.build()
                checkNotNull(sessions[id]).capture(request, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                        if (capture !== group || group.outcomes[id] != null) return
                        val timestamp = result[CaptureResult.SENSOR_TIMESTAMP]
                        if (timestamp == null) settle(id, Result.failure(IllegalStateException("Missing sensor timestamp")))
                        else { expected[id] = timestamp; matchImage(id) }
                    }
                    override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                        if (capture === group) settle(id, Result.failure(IllegalStateException("Capture failed: ${failure.reason}")))
                    }
                    override fun onCaptureBufferLost(s: CameraCaptureSession, r: CaptureRequest, target: Surface, frameNumber: Long) {
                        if (capture === group) settle(id, Result.failure(IllegalStateException("JPEG buffer lost")))
                    }
                }, worker)
            } catch (e: Exception) { settle(id, Result.failure(e)) }
        }
        worker.postDelayed({ if (capture === group) fail("Photo timed out. Retry to reconnect.") }, 10_000)
    } }

    private fun matchImage(id: String) {
        val timestamp = expected[id] ?: return
        val bytes = images[id]?.remove(timestamp) ?: return
        settle(id, Result.success(ConcurrentPhoto(bytes, timestamp, timestamp)))
    }

    private fun settle(id: String, outcome: Result<ConcurrentPhoto>) {
        val group = capture ?: return
        group.settle(id, outcome)
        if (group.complete) saveGroup(group)
    }

    private fun saveGroup(group: ConcurrentCapture<ConcurrentPhoto>) {
        capture = null
        expected.clear(); images.clear()
        val message = runCatching { store.save(group, plan, timestamps) }
            .getOrElse { "Could not save capture group; files rolled back: ${it.message}" }
        main.post { listener.onPhoto(message) }
    }

    fun close(done: () -> Unit) {
        if (finished) { main.post(done); return }
        // finish and close are serialized; a rejected post means the worker has already exited.
        if (!worker.post {
            if (finished) main.post(done)
            else { closeCallbacks += done; stop("Capture cancelled: screen closed") }
        }) main.post(done)
    }

    private fun fail(reason: String) {
        if (lifecycle.stopping) return
        main.post { listener.onFailed(reason) }
        stop(reason)
    }

    private fun stop(reason: String) {
        lifecycle.stop()
        capture?.let { it.cancel(reason); saveGroup(it) }
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
        devices.forEach { (id, device) -> state(id, "Closing"); device.close() }
        finishIfClosed()
    }

    private fun finishIfClosed() {
        if (finished || !lifecycle.closed) return
        readers.values.forEach { it.close() }
        surfaces.values.forEach { it.release() }
        readers.clear(); surfaces.clear()
        finished = true
        lease.release(this)
        closeCallbacks.toList().forEach { main.post(it) }
        closeCallbacks.clear()
        thread.quitSafely()
    }

    private fun state(id: String, state: String) { main.post { listener.onState(id, state) } }

    companion object {
        private val lease = ConcurrentLease()
    }
}
