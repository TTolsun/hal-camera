package dev.halcamera.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraState
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import dev.halcamera.telemetry.Telemetry
import java.util.concurrent.Executors

/** CameraX owns both physical preview outputs; GL copies each stream to its own video encoder. */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class DualCameraXSession(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val plan: DualPreviewPlan,
    private val textures: Pair<SurfaceTexture, SurfaceTexture>,
    private val videoMode: Boolean,
    private val listener: DualPreviewSession.Listener,
    private val telemetry: Telemetry,
    private val sessionId: String,
) : DualCameraSession {
    private val main = ContextCompat.getMainExecutor(context)
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var loading = false
    private var active = true
    private var released = false
    private var closing = false
    private var done: (() -> Unit)? = null
    private var failed = false
    private var streaming = false
    private var recording: DualVideoRecording? = null
    private var recordingRequested = false
    private val relays = mutableListOf<DualPreviewRelay>()
    private var pendingSurfaces = 0
    private var unbound = false
    private var saving = false
    private var deviceOpen = false
    private var boundCameras = emptyList<androidx.camera.core.Camera>()
    private val stateObservers = mutableMapOf<CameraInfo, Observer<CameraState>>()
    private val size = if (videoMode) LiveSize(1280, 720) else plan.sizes.first()
    private val ids = listOf(plan.first.id, plan.second.id)
    private val callback = telemetry.callback(sessionId, alive = { active })
    private val openingTimeout = Runnable { if (active && !streaming) fail("CameraX dual preview start timed out") }

    override fun start() {
        loading = true
        handler.postDelayed(openingTimeout, 15_000)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            loading = false
            try {
                provider = future.get()
                if (!active) releaseProvider() else bind()
            } catch (e: Exception) {
                if (active) fail("CameraX configuration failed", e) else finish()
            }
        }, main)
    }

    private fun bind() {
        telemetry.sessions[sessionId] = mapOf("engine" to "CameraX", "cameraId" to plan.logicalId, "physicalIds" to ids)
        telemetry.configureCallbackStreams(sessionId, dualCallbackStreams().take(2))
        val configs = ids.mapIndexed { index, physicalId ->
            val selector = CameraSelector.Builder()
                .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == plan.logicalId } }
                .setPhysicalCameraId(physicalId).build()
            val builder = Preview.Builder().setTargetRotation(Surface.ROTATION_0)
                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(size.width, size.height), ResolutionStrategy.FALLBACK_RULE_NONE)).build())
            if (index == 0) Camera2Interop.Extender(builder).setDeviceStateCallback(object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { main.execute { deviceOpen = true } }
                override fun onDisconnected(camera: CameraDevice) { main.execute { if (active) fail("CameraX disconnected") } }
                override fun onError(camera: CameraDevice, error: Int) { main.execute { if (active) fail("CameraX camera error $error") } }
                override fun onClosed(camera: CameraDevice) { main.execute { deviceOpen = false; finishOutputs() } }
            }).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(s: CameraCaptureSession, r: CaptureRequest, timestamp: Long, frame: Long) {
                    callback.onCaptureStarted(s, r, timestamp, frame)
                }
                override fun onCaptureProgressed(s: CameraCaptureSession, r: CaptureRequest, result: CaptureResult) {
                    callback.onCaptureProgressed(s, r, result)
                }
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    callback.onCaptureCompleted(s, r, result)
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        val values = ids.associateWith { result.physicalCameraResults[it]?.get(CaptureResult.SENSOR_TIMESTAMP) }
                        telemetry.recorder.record(sessionId, "dual_physical_result", result.frameNumber,
                            values = mapOf("timestamps" to values))
                        main.execute { if (active) {
                            if (!streaming) {
                                streaming = true; handler.removeCallbacks(openingTimeout)
                                boundCameras.firstOrNull()?.cameraInfo?.zoomState?.value?.let {
                                    listener.onZoomRange(it.minZoomRatio to it.maxZoomRatio)
                                }
                                listener.onStreaming(size)
                                listener.onStatus("CameraX · ${ids.joinToString(" + ")}")
                            }
                            listener.onResult(values)
                        } }
                    }
                }
            })
            val preview = builder.build()
            preview.setSurfaceProvider(main) { request ->
                if (!active) { request.willNotProvideSurface(); return@setSurfaceProvider }
                var provided = false
                lateinit var relay: DualPreviewRelay
                relay = DualPreviewRelay(if (index == 0) textures.first else textures.second,
                    LiveSize(request.resolution.width, request.resolution.height)) { error ->
                    main.execute {
                        if (!provided) {
                            provided = true
                            request.willNotProvideSurface()
                            relay.close { main.execute { pendingSurfaces--; finishOutputs() } }
                        }
                        if (active) fail("CameraX preview processing failed", error)
                    }
                }
                relays += relay
                pendingSurfaces++
                relay.start { surface -> main.execute {
                    provided = true
                    request.provideSurface(surface, main) {
                        relay.close { main.execute { pendingSurfaces--; finishOutputs() } }
                    }
                } }
            }
            ConcurrentCamera.SingleCameraConfig(selector, UseCaseGroup.Builder().addUseCase(preview).build(), owner)
        }
        boundCameras = checkNotNull(provider).bindToLifecycle(configs).cameras
        boundCameras.firstOrNull()?.cameraInfo?.zoomState?.value?.let {
            listener.onZoomRange(it.minZoomRatio to it.maxZoomRatio)
        }
        boundCameras.map { it.cameraInfo }.distinct().forEach { info ->
            val observer = Observer<CameraState> { state ->
                if (active && state.error != null) fail("CameraX camera error ${state.error!!.code}", state.error!!.cause)
                if (closing) finishOutputs()
            }
            stateObservers[info] = observer
            // Closure must complete even after the Activity stops. Remove at finish, not at onStop.
            info.cameraState.observeForever(observer)
        }
    }

    override fun setZoom(ratio: Float) {
        if (!active) return
        boundCameras.map { it.cameraControl }.distinct().forEach { control ->
            val future = control.setZoomRatio(ratio)
            future.addListener({ runCatching { future.get() }.onFailure {
                if (active) listener.onStatus("Zoom failed: ${it.cause?.message ?: it.message}")
            } }, main)
        }
    }

    override fun startRecording() {
        if (!active || !videoMode || recordingRequested || !streaming || relays.size != 2) return
        recordingRequested = true
        try {
            val output = DualVideoRecording(context, ids, size) { main.execute { fail("CameraX encoder error") } }
            recording = output
            output.start()
            var attached = 0
            relays.forEachIndexed { index, relay -> relay.encode(output.surfaces[index]) { main.execute {
                attached++
                if (active && attached == 2) listener.onRecording()
            } } }
        } catch (e: Exception) { fail("CameraX recording start failed", e) }
    }

    private fun fail(message: String, cause: Throwable? = null) {
        if (failed || !active) return
        failed = true
        recording?.invalidate()
        telemetry.event(sessionId, "camera_error", mapOf("message" to message, "detail" to cause?.toString()))
        handler.removeCallbacks(openingTimeout)
        listener.onFailed(message)
    }

    override fun close(done: () -> Unit) {
        if (released) { done(); return }
        this.done = done
        active = false; closing = true
        handler.removeCallbacks(openingTimeout)
        releaseProvider()
    }

    private fun releaseProvider() {
        if (loading || released || unbound) return
        provider?.unbindAll()
        provider = null
        unbound = true
        finishOutputs()
    }

    private fun finishOutputs() {
        if (!closing || !unbound || deviceOpen || pendingSurfaces != 0 || saving) return
        // Also cover close during OPENING, before the device onOpened callback reaches us.
        if (stateObservers.keys.any { it.cameraState.value?.type != CameraState.Type.CLOSED }) return
        saving = true
        io.execute {
            val result = recording?.finish()
            main.execute { result?.let { listener.onVideoSaved(it) }; finish() }
        }
    }

    private fun finish() {
        if (released) return
        released = true
        stateObservers.forEach { (info, observer) -> info.cameraState.removeObserver(observer) }
        stateObservers.clear()
        io.shutdown()
        done?.also { done = null }?.invoke()
    }
}
