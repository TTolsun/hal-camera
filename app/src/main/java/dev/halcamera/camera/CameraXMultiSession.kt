package dev.halcamera.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.Looper
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraState
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import dev.halcamera.telemetry.Telemetry

/** CameraX ConcurrentCamera owns the devices; independent compositors preserve full-frame originals. */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
internal class CameraXMultiSession(
    private val context: Context, private val owner: LifecycleOwner, private val plan: ConcurrentPlan,
    private val textures: List<SurfaceTexture>, private val listener: MultiSession.Listener,
    private val outputSizes: List<LiveSize>, private val telemetry: Telemetry,
    private val sessionId: String, private val videoMode: Boolean,
) : MultiSession {
    private val main = Handler(Looper.getMainLooper())
    private val executor = java.util.concurrent.Executor { main.post(it) }
    private var provider: ProcessCameraProvider? = null
    private var loading = false
    private var started = false
    @Volatile private var closing = false
    private var disposed = false
    private var finished = false
    private var ready = false
    private var provided = 0
    private var mediaClosed = false
    private val closeCallbacks = mutableListOf<() -> Unit>()
    private var cameras = emptyMap<String,Camera>()
    private val requests = mutableMapOf<String,SurfaceRequest>()
    private val previews = mutableMapOf<String,androidx.camera.core.Preview>()
    private val compositors = linkedMapOf<String,DeviceCompositor>()
    private val scenes = mutableSetOf<String>()
    private val observers = mutableListOf<Pair<Camera,Observer<CameraState>>>()
    private val controls = java.util.concurrent.ConcurrentHashMap<String,CameraXControls>()
    private val photos = CameraXMultiPhotos(context,plan,main,telemetry,sessionId,listener)
    private val video = MultiRecording(context,main,compositors,listener::onRecording,listener::onVideoResult)
    private val timeout = Runnable { if (!ready && !closing) fail("CameraX Multi timed out") }

    override fun start() {
        if (started || closing) return
        started = true
        MultiSessionLease.lease.acquire(this) { main.post {
            if (!closing) {
                loading = true
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    loading = false
                    runCatching { provider = future.get(); if (!closing) bind() }
                        .onFailure { fail("CameraX Multi unavailable: ${it.message}") }
                    if (closing) finishClose()
                },executor)
            }
        } }
    }

    private fun bind() {
        val provider = checkNotNull(provider)
        val ids = plan.streams.map { it.camera.id }.toSet()
        val pair = if (ids.size == 1) provider.availableCameraInfos.filter { Camera2CameraInfo.from(it).cameraId in ids }
        else provider.availableConcurrentCameraInfos.firstOrNull { group ->
            group.map { Camera2CameraInfo.from(it).cameraId }.toSet() == ids
        } ?: error("CameraX does not advertise this camera combination. Choose a supported pair or Camera2.")
        val infos = pair.associateBy { Camera2CameraInfo.from(it).cameraId }
        val configs = plan.streams.mapIndexed { index, stream ->
            val id = stream.camera.id
            val key = "${sessionId}_$id"
            telemetry.sessions[key] = mapOf("engine" to "CameraX", "cameraId" to id, "mode" to "Multi")
            telemetry.configureCallbackStreams(key,listOf(OutputDescriptor("preview",OutputKind.PREVIEW,true).metadata(),
                OutputDescriptor("photo",OutputKind.JPEG,false,stillCapture = true).metadata()))
            val measured = telemetry.callback(key,alive = { !closing })
            val builder = cameraXPipPreviewBuilder(stream.preview)
            Camera2Interop.Extender(builder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession,r: CaptureRequest,result: TotalCaptureResult) {
                    measured.onCaptureCompleted(s,r,result); controls[id]?.onResult(r,result)
                }
                override fun onCaptureStarted(s: CameraCaptureSession,r: CaptureRequest,t: Long,f: Long) = measured.onCaptureStarted(s,r,t,f)
            })
            val preview = builder.build()
            previews[id] = preview
            preview.setSurfaceProvider(executor) { request ->
                if (closing) request.willNotProvideSurface()
                else if (requests.putIfAbsent(id,request) != null) { request.willNotProvideSurface(); fail("CameraX stream changed; retry") }
                else {
                    if (request.resolution.width != stream.preview.width || request.resolution.height != stream.preview.height) {
                        request.willNotProvideSurface(); fail("CameraX did not configure the requested preview size")
                    } else prepare(index,stream,request)
                }
            }
            androidx.camera.core.ConcurrentCamera.SingleCameraConfig(infos.getValue(id).cameraSelector,
                UseCaseGroup.Builder().addUseCase(preview).addUseCase(photos.captures.getValue(id)).build(),owner)
        }
        val bound = if (configs.size == 1) listOf(provider.bindToLifecycle(owner,configs.single().cameraSelector,configs.single().useCaseGroup))
            else provider.bindToLifecycle(configs).cameras
        cameras = bound.associateBy { Camera2CameraInfo.from(it.cameraInfo).cameraId }
        cameras.forEach { (id,camera) ->
            controls[id] = CameraXControls(null,main,telemetry,"${sessionId}_$id",object : CameraXControls.Host {
                override val camera get() = cameras[id]
                override val imageCapture get() = photos.captures[id]
                override val active get() = !closing && ready
                override fun notice(text: String) = Unit
            })
            val observer = Observer<CameraState> { state ->
                if (closing) finishClose() else if (state.error != null) fail("CameraX camera $id: ${state.error}")
            }
            observers += camera to observer; camera.cameraInfo.cameraState.observeForever(observer)
        }
        plan.streams.forEach { stream ->
            val actual = photos.captures.getValue(stream.camera.id).resolutionInfo?.resolution
            check(actual?.width == stream.photo.width && actual.height == stream.photo.height) { "CameraX did not configure the requested JPEG size" }
        }
        main.postDelayed(timeout,15_000)
    }

    private fun prepare(index: Int, stream: ConcurrentStream, request: SurfaceRequest) {
        val id = stream.camera.id
        val compositor = DeviceCompositor(context,textures[index],outputSizes[index],listOf(stream.preview),id,emptyList(),
            onReady = { main.post { if (!closing) {
                scenes += id
                if (scenes.size == plan.streams.size) { ready = true; main.removeCallbacks(timeout); listener.onReady() }
            } } }, onError = { main.post { fail("Camera $id: ${it.message}") } }, prepareRecording = videoMode,
            preserveFullFrame = true, inputFrame = { _, timestamp ->
                telemetry.recorder.record("${sessionId}_$id","preview_available",sensorNs = timestamp,values = mapOf("stream" to "preview"))
            })
        compositors[id] = compositor
        request.addRequestCancellationListener(executor) { if (!closing) fail("CameraX preview cancelled") }
        compositor.start { surfaces -> main.post {
            if (closing) { request.willNotProvideSurface(); finishClose() }
            else {
                provided++
                request.provideSurface(surfaces.single(),executor) {
                    provided--
                    if (closing) finishClose() else fail("CameraX preview ended")
                }
            }
        } }
    }

    override fun capture(displayDegrees: Int) { if (ready && !closing && !video.pending) photos.capture(displayDegrees) }
    override fun startVideo(audio: Boolean) { if (ready && !closing && !photos.busy) video.start(audio) }
    override fun stopVideo() = video.stop()
    override fun setControls(id: String,next: LiveControls) {
        if (!closing) controls[id]?.setControls(next.copy(manual = ManualControls(),bracket = false),false)
    }
    override fun setZoom(id: String,ratio: Float) { if (!closing) cameras[id]?.cameraControl?.setZoomRatio(ratio) }
    override fun meterAt(id: String,u: Double,v: Double,exposure: Boolean,feedback: (TouchPhase) -> Unit): Boolean {
        if (!ready || closing || photos.busy || video.pending) return false
        val info = cameras[id]?.cameraInfo ?: return false
        val sensor = TouchMeter.toSensor(u,v,info.getSensorRotationDegrees(android.view.Surface.ROTATION_0),
            info.lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT)
        val point = SurfaceOrientedMeteringPointFactory(1f,1f,previews.getValue(id)).createPoint(sensor.first.toFloat(),sensor.second.toFloat())
        return controls[id]?.meterAt(u.toFloat(),v.toFloat(),exposure,feedback,point) == true
    }

    private fun fail(message: String) { if (!closing) { listener.onFailed(message); close {} } }
    override fun close(done: () -> Unit) {
        if (finished) { done(); return }
        closeCallbacks += done
        if (closing) return
        closing = true; ready = false; main.removeCallbacks(timeout)
        requests.values.forEach { it.willNotProvideSurface() }
        photos.close { video.close { mediaClosed = true; finishClose() } }
        provider?.unbindAll(); finishClose()
    }

    private fun finishClose() {
        if (!closing || disposed || loading || !mediaClosed || provided != 0 ||
            cameras.values.any { it.cameraInfo.cameraState.value?.type != CameraState.Type.CLOSED }) return
        disposed = true
        observers.forEach { (camera,observer) -> camera.cameraInfo.cameraState.removeObserver(observer) }; observers.clear()
        var count = compositors.size
        fun complete() {
            // Release CameraX's retained camera graph before another engine can open these IDs.
            val finish = {
                finished = true; MultiSessionLease.lease.release(this)
                closeCallbacks.toList().also { closeCallbacks.clear() }.forEach { it() }
            }
            @android.annotation.SuppressLint("VisibleForTests")
            val future = provider?.shutdownAsync()
            if (future == null) finish() else future.addListener(finish,executor)
        }
        if (count == 0) complete() else compositors.values.forEach { it.close { main.post { if (--count == 0) complete() } } }
    }
}
