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
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.RequiresApi
import java.util.UUID
import java.util.concurrent.Executor

@RequiresApi(30)
fun readConcurrentPlans(manager: CameraManager, maxDevices: Int = Int.MAX_VALUE): List<ConcurrentPlan> =
    ConcurrentPlanner.plans(30, manager.concurrentCameraIds, manager.cameraIdList.map { readConcurrentCamera(manager, it) }, maxDevices)

@RequiresApi(30)
fun readConcurrentCamera(manager: CameraManager, id: String): ConcurrentCamera {
    val c = manager.getCameraCharacteristics(id)
    val map = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
    val logical = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in
        (c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES] ?: intArrayOf())
    return ConcurrentCamera(id, c[CameraCharacteristics.LENS_FACING],
        map?.getOutputSizes(SurfaceTexture::class.java)?.map { LiveSize(it.width,it.height) }.orEmpty(),
        map?.getOutputSizes(ImageFormat.JPEG)?.map { LiveSize(it.width,it.height) }.orEmpty(),
        if (logical) c.physicalCameraIds.sorted() else emptyList())
}

fun readPipSources(manager: CameraManager, parent: String): List<PipSource> {
    if (android.os.Build.VERSION.SDK_INT < 30 || parent.isEmpty()) return emptyList()
    return runCatching {
        val combinations = runCatching { manager.concurrentCameraIds }.getOrDefault(emptySet())
        val physical = readConcurrentCamera(manager,parent).physicalIds.map { PipSource(it,true,"Physical $it") }
        val service = manager.cameraIdList.filter { it != parent }.map { id ->
            val camera = runCatching { readConcurrentCamera(manager,id) }.getOrNull()
            val reason = when {
                camera == null -> "Unavailable"
                camera.previews.isEmpty() -> "No preview"
                else -> PipAvailability.serviceReason(setOf(parent,id),combinations)
            }
            PipSource(id,false,"${camera?.label ?: "Camera $id"} · Service",reason)
        }
        physical + service
    }.getOrDefault(emptyList())
}

/** Owns independent devices; every callback and photo transaction is serialized on the worker. */
@RequiresApi(30)
class ConcurrentSession(
    context: Context,
    private val manager: CameraManager,
    private val plan: ConcurrentPlan,
    private val textures: List<SurfaceTexture>,
    private val listener: Listener,
    private val physical: Map<String, List<String>> = emptyMap(),
    private val outputSizes: List<LiveSize> = plan.streams.map { LiveSize(it.preview.height, it.preview.width) },
    private val servicePip: Map<String,String> = emptyMap(),
    private val positions: Map<String,List<PipRect>> = emptyMap(),
    private val telemetry: dev.halcamera.telemetry.Telemetry? = null,
    private val sessionId: String = "concurrent",
    private val videoMode: Boolean = false,
) {
    interface Listener {
        fun onState(id: String, state: String)
        fun onReady()
        fun onFailed(reason: String)
        fun onPhoto(message: String)
        fun onRecording(active: Boolean, message: String) {}
        fun onPhotoResult(result: Result<PhotoResult>) {}
        fun onVideoResult(result: Result<List<android.net.Uri>>) {}
    }
    private val thread = HandlerThread("HAL.Concurrent").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { worker.post(it) }
    private val ownedIds = (plan.streams.map { it.camera.id } + servicePip.values).distinct()
    private val lifecycle = ConcurrentLifecycle(ownedIds)
    private val preparedInputs = mutableMapOf<String,List<Surface>>()
    private val readyScenes = mutableSetOf<String>()
    private val devices = mutableMapOf<String, CameraDevice>()
    private val sessions = mutableMapOf<String, CameraCaptureSession>()
    private val surfaces = mutableMapOf<String, List<Surface>>()
    private val compositors = mutableMapOf<String, DeviceCompositor>()
    private val app = context.applicationContext
    private var disposing = false
    private var recording = false
    private var videoPending = false
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
    private val controls = mutableMapOf<String,LiveControls>()
    private val zooms = mutableMapOf<String,Float>()
    private val callbacks = mutableMapOf<String,CameraCaptureSession.CaptureCallback>()
    private val touch = mutableMapOf<String,Camera2TouchFocus>()

    fun meterAt(id: String, u: Double, v: Double, exposure: Boolean, feedback: (TouchPhase) -> Unit): Boolean {
        val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return false
        val supported = touchSupport(c)
        if (!(if (exposure) supported.second else supported.first)) return false
        return worker.post {
            if (!lifecycle.ready || capture != null || videoPending) return@post
            val focus = touch[id] ?: return@post
            val stream = plan.streams.first { it.camera.id == id }.preview
            val size = android.util.Size(stream.width,stream.height)
            if (exposure) focus.exposeAt(c,u,v,zooms[id] ?: 1f,size,feedback)
            else focus.focusAt(c,u,v,zooms[id] ?: 1f,size,feedback)
        }
    }

    /** Multi has automatic exposure/focus; manual sensor controls never reach a request. */
    fun setControls(id: String, next: LiveControls) { worker.post {
        if (lifecycle.stopping || id !in devices) return@post
        val previous = controls[id] ?: LiveControls()
        val support = liveControlSupport(manager,id).copy(autoFlash = false,alwaysFlash = false)
        controls[id] = next.copy(manual = ManualControls(),bracket = false).coerce(support,false)
        if (previous.aeLock && controls[id]?.aeLock != true) touch[id]?.dropExposure()
        try {
            if (previous.afLock != controls[id]?.afLock) sessions[id]?.capture(previewRequest(id,
                if (controls[id]?.afLock == true) CaptureRequest.CONTROL_AF_TRIGGER_START else CaptureRequest.CONTROL_AF_TRIGGER_CANCEL),callbacks[id],worker)
            sessions[id]?.setRepeatingRequest(previewRequest(id),callbacks[id],worker)
        } catch (e: Exception) { fail("Camera $id controls failed: ${e.message}") }
    } }

    fun setZoom(id: String, ratio: Float) { worker.post {
        if (lifecycle.stopping || id !in devices || !ratio.isFinite()) return@post
        val range = zoomRange(manager,id)
        zooms[id] = ratio.coerceIn(range.first,range.second)
        try { sessions[id]?.setRepeatingRequest(previewRequest(id),callbacks[id],worker) }
        catch (e: Exception) { fail("Camera $id zoom failed: ${e.message}") }
    } }

    private fun CaptureRequest.Builder.applyControls(id: String) {
        val c = manager.getCameraCharacteristics(id)
        applyLiveControls(controls[id] ?: LiveControls())
        val afModes = c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
        set(CaptureRequest.CONTROL_AF_MODE,when {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            CaptureRequest.CONTROL_AF_MODE_AUTO in afModes -> CaptureRequest.CONTROL_AF_MODE_AUTO
            else -> CaptureRequest.CONTROL_AF_MODE_OFF
        })
        touch[id]?.let(::applyTouch)
        val ratio = zooms[id] ?: 1f
        if (c[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE] != null) set(CaptureRequest.CONTROL_ZOOM_RATIO,ratio)
        else c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]?.let { a ->
            val w = (a.width()/ratio).toInt(); val h = (a.height()/ratio).toInt()
            set(CaptureRequest.SCALER_CROP_REGION,android.graphics.Rect(a.centerX()-w/2,a.centerY()-h/2,a.centerX()+w/2,a.centerY()+h/2))
        }
    }

    private fun previewRequest(id: String, trigger: Int? = null): CaptureRequest = checkNotNull(devices[id])
        .createCaptureRequest(if (videoMode) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW).apply {
            surfaces[id].orEmpty().forEach(::addTarget); applyControls(id)
            if (Build.VERSION.SDK_INT >= 31) set(CaptureRequest.SCALER_ROTATE_AND_CROP,CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)
            trigger?.let { set(CaptureRequest.CONTROL_AF_TRIGGER,it) }
        }.build()

    fun start() { worker.post {
        if (started || lifecycle.stopping) return@post
        started = true
        lease.acquire(this) { worker.post { startOwned() } }
    } }

    private fun startOwned() {
        if (lifecycle.stopping) return
        try {
            plan.streams.forEachIndexed { index, stream ->
                val id = stream.camera.id
                val ids = PipScene.selected(stream.camera.physicalIds.toSet(), physical[id].orEmpty())
                val routes = PipRouting.inputs(id,ids,servicePip[id])
                val allIds = routes.map { it.physicalId ?: it.deviceId }
                val sizes = allIds.mapIndexed { inputIndex, inputId ->
                    if (inputIndex == 0) stream.preview else {
                        val c = manager.getCameraCharacteristics(inputId)
                        c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]?.getOutputSizes(SurfaceTexture::class.java)
                            ?.filter { it.width.toLong()*it.height <= 1280L*720 }
                            ?.maxByOrNull { it.width.toLong()*it.height }?.let { LiveSize(it.width,it.height) }
                            ?: error("Physical camera $inputId has no preview stream")
                    }
                }
                timestamps[id] = manager.getCameraCharacteristics(id)[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE]
                val compositor = DeviceCompositor(app,textures[index],outputSizes[index],sizes,id,ids,
                    onReady = { worker.post {
                        if (!lifecycle.stopping) {
                            readyScenes += id
                            state(id,"Active ${stream.preview}")
                            if (readyScenes.size == plan.streams.size) {
                                ownedIds.forEach(lifecycle::streaming)
                                main.post { listener.onReady() }
                            }
                        }
                    } }, onError = { error -> worker.post { fail("Camera $id: ${error.message}") } }, serviceIds = listOfNotNull(servicePip[id]),
                    initialRects = positions[id].orEmpty(),
                    prepareRecording = videoMode,
                    preserveFullFrame = ids.isEmpty() && servicePip[id] == null,
                    inputFrame = { index, timestamp ->
                        telemetry?.recorder?.record("${sessionId}_$id","preview_available",sensorNs = timestamp,
                            values = mapOf("stream" to "preview", "input" to index))
                    })
                compositors[id] = compositor
                compositor.start { inputs -> worker.post {
                    if (!lifecycle.stopping) {
                        preparedInputs[id] = inputs
                        if (preparedInputs.size == plan.streams.size) {
                            try { prepareAll(); if (!lifecycle.stopping) openPrepared() }
                            catch (e: Exception) { fail("Camera configuration failed: ${e.message}") }
                        }
                    }
                } }
            }
            worker.postDelayed({ if (!lifecycle.stopping && !lifecycle.ready) fail("Timed out opening cameras.") },15_000)
        } catch (e: Exception) { fail("Camera preparation failed: ${e.message}") }
    }

    private fun openPrepared() {
        try {
            if (configs.size > 1 && !manager.isConcurrentSessionConfigurationSupported(configs)) {
                fail("This camera / PIP combination is unavailable."); return
            }
            ownedIds.forEach(::open)
        } catch (e: SecurityException) { fail("Camera permission unavailable: ${e.message}") }
        catch (e: Exception) { fail("Camera preflight failed: ${e.message}") }
    }

    private fun prepareAll() {
        val outputs = ownedIds.associateWith { mutableListOf<OutputConfiguration>() }
        val targets = ownedIds.associateWith { mutableListOf<Surface>() }
        plan.streams.forEach { stream ->
            val id = stream.camera.id
            PipRouting.inputs(id,physical[id].orEmpty(),servicePip[id]).forEachIndexed { index, route ->
                val surface = checkNotNull(preparedInputs[id])[index]
                checkNotNull(targets[route.deviceId]).add(surface)
                checkNotNull(outputs[route.deviceId]).add(OutputConfiguration(surface).apply {
                    route.physicalId?.let(::setPhysicalCameraId)
                })
            }
        }
        ownedIds.forEach { id ->
            prepare(id,plan.streams.firstOrNull { it.camera.id == id },checkNotNull(targets[id]),checkNotNull(outputs[id]))
        }
    }

    private fun prepare(id: String, stream: ConcurrentStream?, inputs: List<Surface>, outputs: MutableList<OutputConfiguration>) {
        try {
            surfaces[id] = inputs
            val key = "${sessionId}_$id"
            telemetry?.sessions?.put(key,mapOf("engine" to "Camera2","cameraId" to id,"mode" to "Multi"))
            telemetry?.configureCallbackStreams(key,listOf(
                OutputDescriptor("preview",OutputKind.PREVIEW,true).metadata(),
                OutputDescriptor("photo",OutputKind.JPEG,false,stillCapture = true).metadata()))
            val measured = telemetry?.callback(key,streams = { request ->
                if (request.tag != null) listOf("photo") else listOf("preview")
            },alive = { !lifecycle.stopping }) ?: object : CameraCaptureSession.CaptureCallback() {}
            callbacks[id] = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(s: CameraCaptureSession,r: CaptureRequest,t: Long,f: Long) = measured.onCaptureStarted(s,r,t,f)
                override fun onCaptureProgressed(s: CameraCaptureSession,r: CaptureRequest,result: CaptureResult) = measured.onCaptureProgressed(s,r,result)
                override fun onCaptureCompleted(s: CameraCaptureSession,r: CaptureRequest,result: TotalCaptureResult) {
                    measured.onCaptureCompleted(s,r,result); touch[id]?.onResult(result)
                }
                override fun onCaptureFailed(s: CameraCaptureSession,r: CaptureRequest,f: CaptureFailure) = measured.onCaptureFailed(s,r,f)
                override fun onCaptureBufferLost(s: CameraCaptureSession,r: CaptureRequest,target: Surface,f: Long) = measured.onCaptureBufferLost(s,r,target,f)
            }
            touch[id] = Camera2TouchFocus(worker,main,object : Camera2TouchFocus.Host {
                override val live get() = lifecycle.ready
                override val afLocked get() = controls[id]?.afLock == true
                override val captureCallback get() = checkNotNull(callbacks[id])
                override fun event(kind: String,values: Map<String,Any?>) { telemetry?.event(key,kind,values) }
                override fun submitRepeating(kind: String,values: Map<String,Any?>) {
                    if (!live) return
                    try { sessions[id]?.setRepeatingRequest(previewRequest(id),captureCallback,worker); event(kind,values) }
                    catch (e: Exception) { fail("Camera $id metering failed: ${e.message}") }
                }
                override fun trigger(kind: String,afTrigger: Int,callback: CameraCaptureSession.CaptureCallback): Boolean {
                    if (!live) return false
                    return runCatching { checkNotNull(sessions[id]).capture(previewRequest(id,afTrigger),callback,worker); true }.getOrDefault(false)
                }
            })
            if (stream != null && physical[id].isNullOrEmpty() && servicePip[id] == null) {
                val reader = ImageReader.newInstance(stream.photo.width,stream.photo.height,ImageFormat.JPEG,3)
                readers[id] = reader
                outputs += OutputConfiguration(reader.surface)
                reader.setOnImageAvailableListener({ source ->
                    try { source.acquireNextImage()?.use { image ->
                        telemetry?.image(key,image.timestamp,image.width,image.height,image.format,"photo")
                        if (capture?.outcomes?.get(id) == null && capture != null) {
                            val bytes = ByteArray(image.planes[0].buffer.remaining()).also { image.planes[0].buffer.get(it) }
                            val buffered = images.getOrPut(id) { linkedMapOf() }
                            buffered[image.timestamp] = bytes
                            while (buffered.size > 3) buffered.remove(buffered.keys.first())
                            matchImage(id)
                        }
                    } } catch (e: Exception) { fail("Camera $id image read failed: ${e.message}") }
                },worker)
            }
            configs[id] = SessionConfiguration(SessionConfiguration.SESSION_REGULAR,outputs,executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (lifecycle.stopping) { session.close(); return }
                        sessions[id] = session
                        try {
                            session.setRepeatingRequest(previewRequest(id),callbacks[id],worker)
                        } catch (e: Exception) { fail("Camera $id preview failed: ${e.message}") }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close(); fail("Camera $id cannot configure the selected streams.")
                    }
                })
        } catch (e: Exception) { fail("Camera configuration failed: ${e.message}") }
    }

    fun movePhysical(id: String, index: Int, rect: PipRect) { worker.post { if (!lifecycle.stopping) compositors[id]?.move(index,rect) } }

    fun startVideo() { worker.post {
        if (!lifecycle.ready || capture != null || recording || videoPending) return@post
        videoPending = true
        val name = MediaLibrary(app).name()
        val results = mutableMapOf<String,Result<Unit>>()
        compositors.forEach { (id, compositor) -> compositor.startVideo(name) { result -> worker.post {
            if (!lifecycle.stopping) {
                results[id] = result
                if (results.size == compositors.size) {
                    videoPending = false
                    if (results.values.any { it.isFailure }) {
                        compositors.values.forEach { it.stopVideo(false) {} }
                        main.post { listener.onRecording(false,"Could not start recording") }
                    } else { recording = true; main.post { listener.onRecording(true,"Recording") } }
                }
            }
        } } }
    } }

    fun stopVideo() { worker.post {
        if (!recording || videoPending) return@post
        recording = false; videoPending = true
        val results = mutableMapOf<String,Result<String>>()
        compositors.forEach { (id, compositor) -> compositor.stopVideo(true) { result -> worker.post {
            results[id] = result
            if (results.size == compositors.size) {
                videoPending = false
                val message = results.entries.joinToString("\n") { "Camera ${it.key}: ${it.value.getOrElse { e -> "failed: ${e.message}" }}" }
                main.post {
                    listener.onRecording(false,message)
                    listener.onVideoResult(runCatching { results.values.map { android.net.Uri.parse(it.getOrThrow()) } })
                }
            }
        } } }
    } }
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
                                val configuration = checkNotNull(configs[cameraId])
                                if (devices.size == 1 && !device.isSessionConfigurationSupported(configuration)) { fail("This PIP combination is unavailable."); return }
                                device.createCaptureSession(configuration)
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
        if (!lifecycle.ready || capture != null || recording || videoPending) return@post
        val group = ConcurrentCapture<ConcurrentPhoto>("HAL_concurrent_${UUID.randomUUID()}", SystemClock.elapsedRealtimeNanos(), plan.streams.map { it.camera.id })
        capture = group
        images.clear(); expected.clear()
        plan.streams.forEach { stream ->
            val id = stream.camera.id
            if (physical[id].orEmpty().isNotEmpty() || servicePip[id] != null) {
                compositors[id]?.capture { result -> worker.post { if (capture === group) settle(id,result) } }
                return@forEach
            }
            try {
                val c = manager.getCameraCharacteristics(id)
                val rotation = ((c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0) +
                    if (stream.camera.facing == CameraLabel.FACING_FRONT) -displayDegrees else displayDegrees) % 360
                val request = checkNotNull(devices[id]).createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(checkNotNull(readers[id]).surface)
                    applyControls(id)
                    set(CaptureRequest.JPEG_ORIENTATION, (rotation + 360) % 360)
                    setTag(group.groupId)
                }.build()
                checkNotNull(sessions[id]).capture(request, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureStarted(s: CameraCaptureSession,r: CaptureRequest,t: Long,f: Long) {
                        callbacks[id]?.onCaptureStarted(s,r,t,f)
                    }
                    override fun onCaptureProgressed(s: CameraCaptureSession,r: CaptureRequest,result: CaptureResult) {
                        callbacks[id]?.onCaptureProgressed(s,r,result)
                    }
                    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                        callbacks[id]?.onCaptureCompleted(s,r,result)
                        if (capture !== group || group.outcomes[id] != null) return
                        val timestamp = result[CaptureResult.SENSOR_TIMESTAMP]
                        if (timestamp == null) settle(id, Result.failure(IllegalStateException("Missing sensor timestamp")))
                        else { expected[id] = timestamp; matchImage(id) }
                    }
                    override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                        callbacks[id]?.onCaptureFailed(s,r,failure)
                        if (capture === group) settle(id, Result.failure(IllegalStateException("Capture failed: ${failure.reason}")))
                    }
                    override fun onCaptureBufferLost(s: CameraCaptureSession, r: CaptureRequest, target: Surface, frameNumber: Long) {
                        callbacks[id]?.onCaptureBufferLost(s,r,target,frameNumber)
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
        var photo: PhotoResult? = null
        val message = runCatching { store.save(group, plan, timestamps) { photo = it } }
            .getOrElse { "Could not save capture group; files rolled back: ${it.message}" }
        main.post {
            listener.onPhoto(message)
            listener.onPhotoResult(photo?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException(message)))
        }
    }

    fun close(done: () -> Unit) {
        if (finished) { main.post(done); return }
        // finish and close are serialized; a rejected post means the worker has already exited.
        if (!worker.post {
            if (finished) main.post(done)
            else { closeCallbacks += done; stop("Capture cancelled: screen closed", true) }
        }) main.post(done)
    }

    private fun fail(reason: String) {
        if (lifecycle.stopping) return
        main.post { listener.onFailed(reason) }
        stop(reason)
    }

    private fun stop(reason: String, saveVideo: Boolean = false) {
        lifecycle.stop()
        if (recording || videoPending) compositors.values.forEach { it.stopVideo(saveVideo && recording) {} }
        recording = false; videoPending = false
        capture?.let { it.cancel(reason); saveGroup(it) }
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
        devices.forEach { (id, device) -> state(id, "Closing"); device.close() }
        finishIfClosed()
    }

    private fun finishIfClosed() {
        if (finished || disposing || !lifecycle.closed) return
        disposing = true
        readers.values.forEach { it.close() }; readers.clear(); surfaces.clear()
        var remaining = compositors.size
        fun complete() {
            finished = true; lease.release(this)
            closeCallbacks.toList().forEach { main.post(it) }; closeCallbacks.clear(); thread.quitSafely()
        }
        if (remaining == 0) complete()
        else compositors.values.forEach { it.close { worker.post { remaining--; if (remaining == 0) complete() } } }
    }
    private fun state(id: String, state: String) { main.post { listener.onState(id, state) } }

    companion object {
        private val lease = ConcurrentLease()
    }
}
