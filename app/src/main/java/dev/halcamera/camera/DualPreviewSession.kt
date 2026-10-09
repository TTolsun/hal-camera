package dev.halcamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import dev.halcamera.telemetry.Telemetry
import android.hardware.camera2.CaptureFailure

/** Reads the rear logical multi-cameras and their physical lenses (#171). Characteristics only; nothing is opened. */
fun readLogicalMultiCameras(manager: CameraManager): List<LogicalMultiCamera> {
    val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
    return ids.mapNotNull { id ->
        val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return@mapNotNull null
        val caps = c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES] ?: IntArray(0)
        val logical = Build.VERSION.SDK_INT >= 28 &&
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps
        val physical = if (Build.VERSION.SDK_INT >= 28) c.physicalCameraIds.map { pid -> physicalLens(manager, pid) } else emptyList()
        LogicalMultiCamera(id, c[CameraCharacteristics.LENS_FACING], logical,
            if (Build.VERSION.SDK_INT >= 28) c[CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE] else null,
            physical)
    }
}

private fun physicalLens(manager: CameraManager, id: String): PhysicalLens {
    val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
        ?: return PhysicalLens(id, LensRole.UNKNOWN, null, emptyList())
    val focal = c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.minOrNull()
    val sensor = c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]
    val equivalent = LensRoles.equivalentFocalMm(focal, sensor?.width, sensor?.height)
    val sizes = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]?.getOutputSizes(SurfaceTexture::class.java)
        ?.map { LiveSize(it.width, it.height) }.orEmpty()
    return PhysicalLens(id, LensRoles.roleFor(equivalent), equivalent, sizes)
}

/**
 * One logical camera streaming two of its physical cameras into two SurfaceTextures (#171).
 *
 * Kept apart from the LIVE engines on purpose: it opens the logical id only, routes each output with
 * OutputConfiguration.setPhysicalCameraId, and never touches Benchmark profiles. Changing the pair or leaving the
 * screen closes the whole device, so no buffer from the previous pair can land in the next one's view.
 *
 * Callbacks arrive on the main thread. Only [start] needs API 28; [close] is safe on any device.
 */
interface DualCameraSession {
    fun start()
    fun startRecording()
    fun close(done: () -> Unit)
    fun setZoom(ratio: Float) {}
    fun setControls(controls: LiveControls) {}
    fun capturePhoto(displayDegrees: Int) {}
}

class DualPreviewSession(
    private val manager: CameraManager,
    private val plan: DualPreviewPlan,
    private val textures: Pair<SurfaceTexture, SurfaceTexture>,
    private val listener: Listener,
    private val videoContext: Context? = null,
    private val telemetry: Telemetry? = null,
    private val sessionId: String = "dual",
    private val photoContext: Context? = null,
    val mainControls: DualMainControls? = null,
) : DualCameraSession {
    interface Listener {
        fun onStatus(message: String)
        /** The size every output streams at; called once the repeating request is running. */
        fun onStreaming(size: LiveSize)
        /** Per physical id, the SENSOR_TIMESTAMP of its result, or null when that physical result was missing. */
        fun onResult(physical: Map<String, Long?>)
        fun onFailed(message: String)
        fun onRecording() {}
        fun onMediaSaved(uris: List<android.net.Uri>, video: Boolean) {}
        fun onVideoSaved(result: Result<Int>) {}
        fun onPhotoSaved(result: Result<Int>) {}
        fun onZoomRange(range: Pair<Float, Float>) {}
    }

    private val thread = HandlerThread("CD.DualPreview").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }
    private val openRetry = CameraOpenRetry()
    @Volatile private var active = true
    private var device: CameraDevice? = null
    private var opening = false
    private var finished = false
    private var failed = false
    private var session: CameraCaptureSession? = null
    private var surfaces: List<Surface> = emptyList()
    private var sizeIndex = 0
    private var lastConfigurationFailure = ""
    private var onClosed: (() -> Unit)? = null
    private var video: DualVideoRecording? = null
    private var recording = false
    private var still: DualStillCapture? = null
    private var photoPending = false
    private var afterSessionClosed: (() -> Unit)? = null
    private val focusWatch = TouchFocusWatch()
    private val exposureWatch = TouchExposureWatch()
    private var focusFeedback: ((TouchPhase) -> Unit)? = null
    private var exposureFeedback: ((TouchPhase) -> Unit)? = null
    private val timelineCallback = telemetry?.callback(sessionId, streams = {
        if (it.tag == "dual_photo") listOf("photo_main", "photo_sub") else listOf("preview_main", "preview_sub")
    }, alive = { active })

    @android.annotation.SuppressLint("NewApi")
    private fun request(trigger: Int? = null): CaptureRequest = checkNotNull(device).let { camera ->
        camera.createCaptureRequest(if (video != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW,
            setOf(plan.first.id, plan.second.id)).apply {
            surfaces.forEach(::addTarget)
            if (recording) video?.surfaces?.forEach(::addTarget)
            mainControls?.apply(camera, this, video != null, trigger)
            setTag("dual_preview")
        }.build()
    }

    private fun repeat() {
        if (!active || failed || photoPending || session == null) return
        try { session?.setRepeatingRequest(request(), captureCallback, handler) }
        catch (e: Exception) { status("Main controls failed: ${e.message}") }
    }
    override fun setZoom(ratio: Float) { handler.post { mainControls?.zoom = ratio; repeat() } }
    override fun setControls(controls: LiveControls) { handler.post {
        val old = mainControls?.controls
        mainControls?.controls = controls
        if (old?.afLock == true && !controls.afLock) { mainControls?.afRegion = null; focusWatch.cancel() }
        if (old?.aeLock == true && !controls.aeLock) { mainControls?.aeRegion = null; exposureWatch.cancel() }
        repeat()
        if (active && !photoPending && old?.afLock != controls.afLock) runCatching {
            session?.capture(request(if (controls.afLock) CaptureRequest.CONTROL_AF_TRIGGER_START else CaptureRequest.CONTROL_AF_TRIGGER_CANCEL), captureCallback, handler)
        }.onFailure { status("AF control failed: ${it.message}") }
    } }

    @android.annotation.SuppressLint("NewApi")
    fun meterAt(u: Double, v: Double, exposure: Boolean, feedback: (TouchPhase) -> Unit): Boolean {
        val control = mainControls ?: return false
        val key = if (exposure) CaptureRequest.CONTROL_AE_REGIONS else CaptureRequest.CONTROL_AF_REGIONS
        val support = touchSupport(control.characteristics)
        if (!active || photoPending || !control.supports(key) || !(if (exposure) support.second else support.first)) return false
        if (!exposure && (!control.support.afLock || control.controls.manual.focusDiopters != null)) return false
        if (exposure && control.controls.manual.exposure != null) return false
        val crop = control.crop()
        val point = TouchMeter.toSensor(u, v, control.characteristics[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90, false)
        val size = plan.sizes[sizeIndex]
        val rect = TouchMeter.region(point.first, point.second, MeterRect(crop.left, crop.top, crop.right, crop.bottom), size.width.toDouble() / size.height)
        val region = android.hardware.camera2.params.MeteringRectangle(android.graphics.Rect(rect.left, rect.top, rect.right, rect.bottom), 1000)
        handler.post {
            if (!active || photoPending) return@post
            if (exposure) {
                control.aeRegion = region
                exposureFeedback = feedback
                val gen = exposureWatch.press()
                repeat()
                main.post { feedback(TouchPhase.SCANNING) }
                handler.postDelayed({ if (active && exposureWatch.timedOut(gen)) main.post { feedback(TouchPhase.FAILED) } }, 3000)
            } else {
                control.afRegion = region
                focusFeedback = feedback
                val gen = focusWatch.tap()
                repeat()
                main.post { feedback(TouchPhase.SCANNING) }
                runCatching { session?.capture(request(CaptureRequest.CONTROL_AF_TRIGGER_START), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureStarted(s: CameraCaptureSession, r: CaptureRequest, t: Long, f: Long) {
                        captureCallback.onCaptureStarted(s, r, t, f)
                    }
                    override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                        captureCallback.onCaptureFailed(s, r, failure)
                        if (focusWatch.timedOut(gen)) main.post { if (active) feedback(TouchPhase.FAILED) }
                    }
                    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                        captureCallback.onCaptureCompleted(s, r, result)
                        focusWatch.triggerCompleted(gen, result.physicalCameraResults[control.physicalId]?.get(CaptureResult.CONTROL_AF_STATE))
                            ?.let { phase -> main.post { if (active) feedback(phase) } }
                    }
                }, handler) }.onFailure { focusWatch.cancel(); main.post { feedback(TouchPhase.FAILED) } }
                handler.postDelayed({ if (active && focusWatch.timedOut(gen)) main.post { feedback(TouchPhase.FAILED) } }, TouchMeter.SCAN_TIMEOUT_MS)
                handler.postDelayed({
                    if (active && focusWatch.generation == gen && !control.controls.afLock) {
                        focusWatch.cancel(); control.afRegion = null
                        if (!photoPending) runCatching {
                            session?.capture(request(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL), captureCallback, handler)
                        }
                        repeat()
                        main.post { if (active) feedback(TouchPhase.DONE) }
                    }
                }, TouchMeter.HOLD_MS)
            }
        }
        return true
    }

    @android.annotation.SuppressLint("NewApi")
    override fun capturePhoto(displayDegrees: Int) { handler.post {
        if (!active || failed || photoPending || recording || session == null) return@post
        val context = photoContext ?: return@post
        val controls = mainControls ?: return@post
        photoPending = true
        status("Capturing both sensors…")
        afterSessionClosed = {
            val camera = device
            if (active && !failed && camera != null) {
                still = DualStillCapture(camera, manager, plan, handler, controls, telemetry, sessionId, displayDegrees, timelineCallback) { result ->
                    still = null
                    val saved = result.mapCatching {
                        check(active && !failed) { "Capture cancelled: camera closed." }
                        MediaLibrary(context).saveDualPhotos(it).also { uris -> main.post { listener.onMediaSaved(uris, false) } }.size
                    }
                    saved.exceptionOrNull()?.let { android.util.Log.w("DualPreview", "Dual photo failed", it) }
                    photoPending = false
                    main.post { if (active && !failed) listener.onPhotoSaved(saved) }
                    if (active && !failed) device?.let(::configure)
                }.also { it.start() }
            }
        }
        runCatching { session?.stopRepeating() }
        session?.close(); session = null
    } }

    override fun startRecording() { handler.post {
        if (!active || session == null || recording) return@post
        try {
            checkNotNull(video).start()
            recording = true
            checkNotNull(session).setRepeatingRequest(request(), captureCallback, handler)
            main.post { if (active) listener.onRecording() }
        } catch (e: Exception) { fail("Dual video start failed: ${e.message.orEmpty()}") }
    } }

    @RequiresApi(28)
    override fun start() { handler.post { open() } }

    /** [done] runs on the main thread after the device has closed. */
    override fun close(done: () -> Unit) { handler.post {
        active = false
        onClosed = done
        afterSessionClosed = null
        if (Build.VERSION.SDK_INT >= 28) still?.cancel()
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        val camera = device
        if (camera != null) camera.close() else if (!opening) finish()
    } }

    @RequiresApi(28)
    @SuppressLint("MissingPermission")
    private fun open() {
        if (!active) return
        status("Opening camera ${plan.logicalId}")
        try {
            opening = true
            manager.openCamera(plan.logicalId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opening = false
                    device = camera
                    if (!active) camera.close() else configure(camera)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    opening = false
                    device = camera
                    retryOrFail(CameraOpenRetry.Cause.DISCONNECTED, "Camera disconnected.")
                    camera.close()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    opening = false
                    device = camera
                    retryOrFail(CameraOpenRetry.Cause.fromStateError(error), "Camera2 error $error")
                    camera.close()
                }
                override fun onClosed(camera: CameraDevice) {
                    device = null
                    val delay = pendingRetryMs.also { pendingRetryMs = null }
                    if (active && delay != null) handler.postDelayed({ open() }, delay)
                    else if (!active) finish()
                }
            }, handler)
        } catch (e: CameraAccessException) {
            opening = false
            val delay = if (active && session == null) openRetry.next(CameraOpenRetry.Cause.fromAccessReason(e.reason)) else null
            if (delay != null) handler.postDelayed({ open() }, delay) else fail("Could not open camera. ${e.message.orEmpty()}")
        } catch (e: Exception) {
            opening = false
            fail("Could not open camera. ${e.message.orEmpty()}")
        }
    }

    private var pendingRetryMs: Long? = null

    private fun retryOrFail(cause: CameraOpenRetry.Cause, message: String) {
        if (!active) return
        pendingRetryMs = if (session == null) openRetry.next(cause) else null
        if (pendingRetryMs == null) fail(message)
    }

    /** Tries the candidate sizes in order; the HAL's answer, not the stream map, decides which one runs. */
    @RequiresApi(28)
    private fun configure(camera: CameraDevice) {
        if (!active) return
        val size = plan.sizes.getOrNull(sizeIndex)
            ?: return fail("Unsupported output combination · $lastConfigurationFailure · Tried: ${plan.sizes.joinToString()}")
        surfaces.forEach { it.release() }
        video?.discard()
        video = null
        if (videoContext != null) {
            val supported = runCatching { listOf(plan.first.id, plan.second.id).all { id ->
                manager.getCameraCharacteristics(id)[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
                    ?.getOutputSizes(MediaRecorder::class.java)?.any { it.width == size.width && it.height == size.height } == true
            } }.getOrElse { return fail("MediaRecorder size query failed: ${it.message.orEmpty()}") }
            if (!supported) return nextSize(camera, "$size MediaRecorder output unsupported")
            try {
                video = DualVideoRecording(videoContext, listOf(plan.first.id, plan.second.id), size) {
                    handler.post { if (active) fail("Dual video encoder error") }
                }
            } catch (e: Exception) { return nextSize(camera, "$size encoder configuration failed: ${e.message.orEmpty()}") }
        }
        textures.first.setDefaultBufferSize(size.width, size.height)
        textures.second.setDefaultBufferSize(size.width, size.height)
        surfaces = listOf(Surface(textures.first), Surface(textures.second))
        val outputs = listOf(plan.first.id, plan.second.id).mapIndexed { index, id ->
            OutputConfiguration(surfaces[index]).apply {
                setPhysicalCameraId(id)
                video?.let { enableSurfaceSharing(); addSurface(it.surfaces[index]) }
            }
        }
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (!active) { s.close(); return }
                session = s
                try {
                    val characteristics = manager.getCameraCharacteristics(plan.logicalId)
                    telemetry?.sessions?.putIfAbsent(sessionId, mapOf("engine" to "Camera2", "cameraId" to plan.logicalId,
                        "physicalIds" to listOf(plan.first.id, plan.second.id),
                        "timestampSource" to characteristics[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE]))
                    if (telemetry?.sessions?.get(sessionId)?.containsKey("callbackStreams") != true)
                        telemetry?.configureCallbackStreams(sessionId, dualCallbackStreams())
                    s.setRepeatingRequest(request(), captureCallback, handler)
                    main.post { if (active) listener.onStreaming(size) }
                    status("${plan.first.id} + ${plan.second.id} · $size")
                } catch (e: Exception) {
                    fail("Could not start preview request. ${e.message.orEmpty()}")
                }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { s.close(); nextSize(camera, "$size configuration rejected") }
            override fun onClosed(s: CameraCaptureSession) {
                afterSessionClosed?.also { afterSessionClosed = null; it() }
            }
        }
        val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback)
        if (Build.VERSION.SDK_INT >= 29) {
            val supported = try { camera.isSessionConfigurationSupported(config) } catch (_: Exception) { null }
            if (supported == false) return nextSize(camera, "$size unsupported (isSessionConfigurationSupported)")
        }
        try {
            camera.createCaptureSession(config)
        } catch (e: Exception) {
            nextSize(camera, "$size configuration failed: ${e.message.orEmpty()}")
        }
    }

    @RequiresApi(28)
    private fun nextSize(camera: CameraDevice, reason: String) {
        lastConfigurationFailure = reason
        status(reason)
        sizeIndex++
        configure(camera)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(s: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            timelineCallback?.onCaptureStarted(s, request, timestamp, frameNumber)
        }
        override fun onCaptureProgressed(s: CameraCaptureSession, request: CaptureRequest, result: CaptureResult) {
            timelineCallback?.onCaptureProgressed(s, request, result)
        }
        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            timelineCallback?.onCaptureFailed(s, request, failure)
        }
        override fun onCaptureBufferLost(s: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
            timelineCallback?.onCaptureBufferLost(s, request, target, frameNumber)
        }
        @RequiresApi(28)
        @Suppress("DEPRECATION")
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            timelineCallback?.onCaptureCompleted(s, request, result)
            val physical = result.physicalCameraResults
            val primary = physical[plan.first.id]
            physical.forEach { (id, r) ->
                telemetry?.recorder?.record(sessionId, if (id == plan.first.id) "dual_main_result" else "dual_sub_result", result.frameNumber,
                    r[CaptureResult.SENSOR_TIMESTAMP], mapOf("physicalId" to id,
                        "crop" to r[CaptureResult.SCALER_CROP_REGION]?.toShortString(),
                        "ev" to r[CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION], "aeLock" to r[CaptureResult.CONTROL_AE_LOCK],
                        "af" to r[CaptureResult.CONTROL_AF_STATE], "afRegions" to r[CaptureResult.CONTROL_AF_REGIONS]?.joinToString { it.rect.toShortString() },
                        "ae" to r[CaptureResult.CONTROL_AE_STATE], "iso" to r[CaptureResult.SENSOR_SENSITIVITY],
                        "exposureNs" to r[CaptureResult.SENSOR_EXPOSURE_TIME], "focusDiopters" to r[CaptureResult.LENS_FOCUS_DISTANCE]))
            }
            focusWatch.onResult(primary?.get(CaptureResult.CONTROL_AF_STATE))?.let { phase ->
                main.post { if (active) focusFeedback?.invoke(phase) }
            }
            if (exposureWatch.onResult(primary?.get(CaptureResult.CONTROL_AE_STATE)))
                main.post { if (active) exposureFeedback?.invoke(TouchPhase.METERED) }
            val timestamps = listOf(plan.first.id, plan.second.id).associateWith { id ->
                physical[id]?.get(CaptureResult.SENSOR_TIMESTAMP)
            }
            telemetry?.recorder?.record(sessionId, "dual_physical_result", result.frameNumber,
                values = mapOf("timestamps" to timestamps))
            main.post { if (active) listener.onResult(timestamps) }
        }
    }

    private fun status(message: String) = main.post { if (active) listener.onStatus(message) }

    /** A failure stops the stream and frees the camera; the screen keeps the message until it is left or retried. */
    private fun fail(message: String) {
        if (!active || failed) return
        failed = true
        afterSessionClosed = null
        if (Build.VERSION.SDK_INT >= 28) still?.cancel()
        video?.invalidate()
        main.post { if (active) listener.onFailed(message) }
        runCatching { session?.close() }
        session = null
        device?.close()
    }

    private fun finish() {
        if (finished) return
        finished = true
        val saved = video?.finish()
        video?.savedUris?.takeIf { it.isNotEmpty() }?.let { uris -> main.post { listener.onMediaSaved(uris, true) } }
        video = null
        if (saved != null) main.post { listener.onVideoSaved(saved) }
        surfaces.forEach { it.release() }
        surfaces = emptyList()
        thread.quitSafely()
        val done = onClosed.also { onClosed = null }
        if (done != null) main.post(done)
    }
}
