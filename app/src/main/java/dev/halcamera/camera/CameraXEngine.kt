package dev.halcamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import dev.halcamera.telemetry.Telemetry
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The CameraX engine of Live. It offers what Camera2Engine offers there: gallery stills and video
 * ([CameraXStillCapture], [CameraXLiveRecorder]), EV, AE/AF lock and flash, and touch metering ([CameraXControls]).
 * Benchmark profiles stay Camera2-only (PLAN-BenchMarker-v0.3 8.2), so nothing here takes a StreamSpec.
 *
 * The ordinary session is Preview + ImageAnalysis + ImageCapture, which is Camera2Engine's preview + YUV + JPEG. While a
 * recording runs it is Preview + VideoCapture, as Camera2's recording session is preview + encoder.
 * PIP uses [CameraXPipSession]'s concurrent previews and the shared compositor for stills and video.
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class CameraXEngine(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val view: PreviewView,
    private val cameraId: String,
    private val session: String,
    private val telemetry: Telemetry,
    private val executor: ExecutorService,
    private val previewReady: () -> Unit = {},
    private val recordingState: (Boolean) -> Unit = {},
    /** A short notice that leaves the camera state alone, such as an AE relock that changed the exposure. */
    private val notice: (String) -> Unit = {},
    private val status: (String, Boolean) -> Unit,
    private val liveStreams: LiveStreamSettings? = null,
    private val streamsConfigured: (Map<String, Any?>) -> Unit = {},
    private val streamsFailed: (String) -> Unit = {},
) : CameraEngine, MediaCapture, LiveTuning, TouchMetering, PipCamera {
    @Volatile private var active = true
    /**
     * Whether close() shuts CameraX down so the camera service releases the camera at once (#230). CameraX 1.6
     * keeps a closed camera open for 1 s in case it is bound again, so a Camera2 open or another screen right after
     * close(done) finds it still held. LIVE clears it when the next engine is CameraX, which reopens through the
     * same provider and would pay a new provider instead.
     */
    @Volatile var releaseOnClose = true
    private var provider: ProcessCameraProvider? = null
    private var selector: CameraSelector? = null
    private var pip: CameraXPipSession? = null
    private var pipPreview: Preview? = null
    private var pipChanging = false
    var pipSources: List<PipSource> = emptyList()
        private set
    @Volatile private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    /** The VideoCapture bound while a recording runs, so closing the camera unbinds it too. */
    private var recording: VideoCapture<Recorder>? = null
    /** ImageCapture is bound next to the VideoCapture of this recording (#175); false when the camera refused that. */
    @Volatile private var photoBound = false
    @Volatile private var previewSeen = false
    private val previewOutput = OutputDescriptor("preview", OutputKind.PREVIEW, repeating = true, observable = false)
    private val analysisOutput = OutputDescriptor("analysis_keep_latest", OutputKind.YUV, repeating = true)
    private val captureOutput = OutputDescriptor("still", OutputKind.JPEG, repeating = false, stillCapture = true)
    private val snapshotOutput = OutputDescriptor("video_snapshot", OutputKind.JPEG, repeating = false, stillCapture = true)
    /** CameraX gives the app no buffer callback for the encoder, so the recording output is not observable. */
    private val recordingOutput = OutputDescriptor("recording", OutputKind.RECORDING, repeating = true, observable = false)
    @Volatile private var zoomRatio = 1f
    private val main = ContextCompat.getMainExecutor(context)
    private val handler = Handler(Looper.getMainLooper())
    private val mediaIo = Executors.newSingleThreadExecutor()
    private val library = MediaLibrary(context)
    override val mediaBusy: Boolean get() = stills.inFlight || video.busy || pipChanging || pip?.busy == true

    private val controls: CameraXControls = CameraXControls(view, handler, telemetry, session, object : CameraXControls.Host {
        override val camera: Camera? get() = this@CameraXEngine.camera
        override val imageCapture: ImageCapture? get() = if (pip == null) capture else null
        override val active: Boolean get() = this@CameraXEngine.active
        override fun notice(text: String) = this@CameraXEngine.notice(text)
    })
    private val stills: CameraXStillCapture = CameraXStillCapture(context, handler, main, telemetry, session, library, mediaIo, object : CameraXStillCapture.Host {
        override val imageCapture: ImageCapture? get() = capture
        override val analysisEnabled: Boolean get() = analysis != null
        override val active: Boolean get() = this@CameraXEngine.active
        override val recordingBusy: Boolean get() = video.busy
        override val flashName: String get() = controls.controls.flash.name
        override val zoomRequested: Float get() = zoomRatio
        override val stillStream: String get() = captureOutput.id
        override fun updateRotation() { val r = displayRotation(); capture?.targetRotation = r; analysis?.targetRotation = r }
        override fun report(message: String, ok: Boolean) = this@CameraXEngine.report(message, ok)
    })
    /** The photo during a recording (#175); the ImageCapture it uses is bound only while recording. */
    private val snapshots: CameraXVideoSnapshot = CameraXVideoSnapshot(handler, main, telemetry, session, library, mediaIo, object : CameraXVideoSnapshot.Host {
        override val imageCapture: ImageCapture? get() = if (photoBound) capture else null
        override val active: Boolean get() = this@CameraXEngine.active
        override val flashName: String get() = controls.controls.flash.name
        override val zoomRequested: Float get() = zoomRatio
        override val snapshotStream: String get() = snapshotOutput.id
        override fun updateRotation() { capture?.targetRotation = displayRotation() }
        override fun notice(text: String) = this@CameraXEngine.notice(text)
    })
    private val video: CameraXLiveRecorder = CameraXLiveRecorder(context, main, telemetry, session, library, mediaIo, object : CameraXLiveRecorder.Host {
        override val active: Boolean get() = this@CameraXEngine.active
        override val settings: LiveVideo? get() = liveStreams?.video
        override val stabilization: LiveStabilization get() = liveStreams?.stabilization ?: LiveStabilization.AUTO
        override val cameraInfo: CameraInfo? get() = camera?.cameraInfo
        override val stillInFlight: Boolean get() = stills.inFlight
        override val displayRotation: Int get() = displayRotation()
        override fun bindRecording(video: VideoCapture<Recorder>) {
            val provider = provider ?: error("Camera not bound")
            val stills = listOfNotNull(analysis, capture).toTypedArray()
            provider.unbind(*stills)
            // Preview + VideoCapture + ImageCapture is a combination every LIMITED camera guarantees, but CameraX decides
            // at bind time; a refusal falls back to the recording alone and the photo button says why.
            // The Live stream settings can turn the JPEG output off, and then there is no ImageCapture to bind.
            val photo = capture
            photoBound = photo != null && try { camera = provider.bindToLifecycle(owner, selector!!, video, photo); true }
            catch (e: Exception) {
                telemetry.event(session, "video_snapshot_unavailable", mapOf("reason" to "bind_refused", "message" to e.toString()))
                false
            }
            if (!photoBound) {
                try { camera = provider.bindToLifecycle(owner, selector!!, video) }
                catch (e: Exception) { camera = provider.bindToLifecycle(owner, selector!!, *stills); throw e }
            }
            val outputs = StreamConfiguration<UseCase>(listOfNotNull(ConfiguredOutput(previewOutput, preview!!), ConfiguredOutput(recordingOutput, video),
                if (photoBound) ConfiguredOutput(snapshotOutput, photo!!) else null))
            recording = video
            rebuilt(outputs, mapOf("preview" to preview?.resolutionInfo?.resolution?.toString(),
                "recording" to video.resolutionInfo?.resolution?.toString(), "recordingFormat" to "Auto",
                "snapshot" to if (photoBound) photo!!.resolutionInfo?.resolution?.toString() else null))
        }
        override fun unbindRecording(video: VideoCapture<Recorder>) {
            snapshots.release("Recording ended")
            val provider = provider ?: return
            provider.unbind(*listOfNotNull<UseCase>(video, if (photoBound) capture else null).toTypedArray())
            recording = null; photoBound = false
            camera = provider.bindToLifecycle(owner, selector!!, *listOfNotNull(analysis, capture).toTypedArray())
            rebuilt(liveOutputs(), streamSizes())
            report("CameraX · LIVE", true)
        }
        override fun recordingState(recording: Boolean) = this@CameraXEngine.recordingState(recording)
        override fun streamingStarted() = controls.resendMetering()
        override val snapshotUnavailable: String? get() = snapshotUnavailableReason()
        override fun notice(text: String) { handler.post { if (active) this@CameraXEngine.notice(text) } }
        override fun status(message: String, ok: Boolean) { if (active) this@CameraXEngine.status(message, ok) }
        override fun report(message: String, ok: Boolean) = this@CameraXEngine.report(message, ok)
    })

    override fun start() {
        telemetry.registerSession(session, "CameraX", context.getSystemService(CameraManager::class.java), cameraId)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!active) return@addListener
            try {
                require(liveStreams?.yuvSaveFormat != YuvSaveFormat.NV21) { "NV21 requires Camera2." }
                require(liveStreams?.raw == null) { "RAW/DNG requires Camera2." }
                provider = future.get()
                pipSources = CameraXPipSources.forParent(cameraId, runCatching { provider!!.availableConcurrentCameraInfos.map { pair ->
                    pair.map { Camera2CameraInfo.from(it).cameraId }
                } }.getOrDefault(emptyList()))
                val info = provider!!.availableCameraInfos.first { Camera2CameraInfo.from(it).cameraId == cameraId }
                val characteristics = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
                val mode = liveStreams?.stabilization ?: LiveStabilization.AUTO
                require(mode in cameraXStabilizationModes(info, hardwareStabilizationModes(characteristics))) {
                    "Unsupported stabilization mode. Select Auto or a supported mode."
                }
                val stabilization = CameraXStabilizationPlan.forMode(mode)
                val builder = Preview.Builder()
                stabilization.preview?.let(builder::setPreviewStabilizationEnabled)
                if (CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE in characteristics.availableCaptureRequestKeys) {
                    stabilization.optical?.let { Camera2Interop.Extender(builder)
                        .setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, it) }
                }
                liveStreams?.let { settings ->
                    builder.setResolutionSelector(exactResolution(settings.preview))
                    settings.fps?.let { builder.setTargetFrameRate(Range(it.min, it.max)) }
                }
                Camera2Interop.Extender(builder).setSessionCaptureCallback(resultCallback(telemetry.callback(session) { active }))
                preview = builder.build().also { it.setSurfaceProvider(view.surfaceProvider) }
                analysis = if (liveStreams != null && liveStreams.yuv == null) null else
                    ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).apply {
                        liveStreams?.yuv?.let { setResolutionSelector(exactResolution(it)) }
                    }.build().also { useCase ->
                    useCase.setAnalyzer(executor) { image ->
                        try {
                            if (active) {
                                telemetry.image(session, image.imageInfo.timestamp, image.width, image.height, image.format, analysisOutput.id)
                                if (!previewSeen) { previewSeen = true; handler.post { if (active) previewReady() } }
                                stills.onFrame(image)
                            }
                        } finally { image.close() }
                    }
                }
                capture = if (liveStreams != null && liveStreams.jpeg == null) null else
                    ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).apply {
                        liveStreams?.jpeg?.let { setResolutionSelector(exactResolution(it)) }
                    }.build()
                selector = CameraSelector.Builder().addCameraFilter { infos ->
                    infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
                }.build()
                val outputs = liveOutputs()
                camera = provider!!.bindToLifecycle(owner, selector!!, *outputs.targets.toTypedArray())
                camera!!.cameraInfo.cameraState.observe(owner) { state ->
                    if (!active) return@observe
                    if (pip == null && !pipChanging) {
                        state.error?.let { status("CameraX error ${it.code}", false); telemetry.event(session, "camera_error", mapOf("code" to it.code)) }
                        if (state.type == CameraState.Type.OPEN && !video.busy) status("CameraX · LIVE", true)
                    }
                }
                val sizes = streamSizes()
                telemetry.sessions.computeIfPresent(session) { _, old -> old + mapOf("negotiatedStreams" to sizes) }
                telemetry.configureCallbackStreams(session, outputs.metadata())
                telemetry.event(session, "bound", sizes)
                streamsConfigured(sizes)
            } catch (e: Exception) {
                val reason = if (e.message?.contains("No supported surface combination") == true)
                    "Unsupported output, size, FPS, or stabilization combination. Change Live Streams or restore the previous settings."
                else e.message ?: "CameraX configuration failed"
                status("CameraX: $reason", false)
                telemetry.event(session, "camera_error", mapOf("message" to e.toString()))
                streamsFailed(reason)
            }
        }, main)
    }

    /** Null while the running recording has a photo use case; otherwise why it has none. */
    private fun snapshotUnavailableReason(): String? = when {
        photoBound -> null
        capture == null -> VideoSnapshotPlan.OFF_REASON
        else -> VideoSnapshotPlan.CAMERAX_REASON
    }

    /** The same outputs bind the use cases and describe the Callback graph, as on Camera2. */
    private fun liveOutputs() = StreamConfiguration<UseCase>(buildList {
        add(ConfiguredOutput(previewOutput, preview!!))
        analysis?.let { add(ConfiguredOutput(analysisOutput, it)) }
        capture?.let { add(ConfiguredOutput(captureOutput, it)) }
    })

    /** Keep only the requested sensor-oriented size; never silently choose another resolution. */
    private fun exactResolution(size: LiveSize) = ResolutionSelector.Builder()
        .setResolutionFilter { sizes, _ -> sizes.filter { it == size.androidSize() } }.build()

    private fun streamSizes() = mapOf("preview" to preview?.resolutionInfo?.resolution?.toString(),
        "analysis" to analysis?.resolutionInfo?.resolution?.toString(), "jpeg" to capture?.resolutionInfo?.resolution?.toString())

    /** A recording start or stop rebound the use cases: the zoom and the controls go on the new session again. */
    private fun rebuilt(outputs: StreamConfiguration<UseCase>, sizes: Map<String, Any?>) {
        telemetry.sessions.computeIfPresent(session) { _, old -> old + mapOf("negotiatedStreams" to sizes) }
        telemetry.configureCallbackStreams(session, outputs.metadata())
        telemetry.event(session, "bound", sizes)
        camera?.cameraControl?.setZoomRatio(zoomRatio)
        controls.sessionRebuilt()
    }

    /** The telemetry callback, plus every repeating result for the AE relock and the long-press exposure watch. */
    private fun resultCallback(telemetry: CameraCaptureSession.CaptureCallback) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) =
            telemetry.onCaptureStarted(session, request, timestamp, frameNumber)
        override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) =
            telemetry.onCaptureProgressed(session, request, partialResult)
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            telemetry.onCaptureCompleted(session, request, result)
            if (active) controls.onResult(request, result)
        }
        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) =
            telemetry.onCaptureFailed(session, request, failure)
        override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) =
            telemetry.onCaptureBufferLost(session, request, target, frameNumber)
    }

    private fun displayRotation(): Int = view.display?.rotation ?: Surface.ROTATION_0

    override fun capture() {
        val current = pip
        if (current != null) current.capture(null) { report(if (it.isSuccess) "Saved PIP photo" else "Photo failed",true) }
        else stills.capture(null,null)
    }
    override fun capturePhoto(requestId: String, done: (Result<PhotoResult>) -> Unit) {
        val current = pip
        if (current != null) current.capture(requestId,done) else stills.capture(requestId,done)
    }
    override fun startRecording(audio: Boolean, started: () -> Unit, done: ((Result<android.net.Uri>) -> Unit)?) {
        val current = pip
        if (current != null) current.startVideo(audio,started,done) else video.start(audio,started,done)
    }
    override fun stopRecording() { pip?.stopVideo() ?: video.stop() }
    override val snapshot: SnapshotStatus get() =
        SnapshotStatus.of(video.live, video.stopping, snapshotUnavailableReason(), snapshots.inFlight)
    override fun captureSnapshot(done: (Result<PhotoResult>) -> Unit) = snapshots.capture(null, done)
    override fun setControls(next: LiveControls, restore: Boolean) = controls.setControls(next, restore)

    /** Also works while recording: CameraControl changes the zoom of whatever session is bound. */
    override fun setZoom(ratio: Float) {
        zoomRatio = ratio
        val cam = camera ?: return
        if (!active) return
        val state = cam.cameraInfo.zoomState.value
        val clamped = if (state != null) ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio) else ratio
        telemetry.event(session, "zoom_set", mapOf("zoomRequested" to clamped, "api" to "CameraControl.setZoomRatio"))
        cam.cameraControl.setZoomRatio(clamped)
    }

    /**
     * CameraX maps the touch through PreviewView itself (rotation, mirroring, fill-crop, zoom). The rules are
     * Camera2's: a tap focuses and ends after [TouchMeter.HOLD_MS], a long press meters exposure and stays until
     * released; see [CameraXControls].
     */
    override fun meterAt(x: Float, y: Float, exposure: Boolean, feedback: (TouchPhase) -> Unit): Boolean =
        controls.meterAt(x, y, exposure, feedback, pipMeteringPoint(x,y))

    /** The hidden single-camera PreviewView no longer owns PIP's crop/rotation mapping. */
    private fun pipMeteringPoint(x: Float, y: Float): MeteringPoint? {
        if (pip == null || view.width == 0 || view.height == 0) return null
        val info = camera?.cameraInfo ?: return null
        val size = pipPreview?.resolutionInfo?.resolution ?: return null
        val rotated = info.getSensorRotationDegrees(displayRotation()) % 180 != 0
        val width = (if (rotated) size.height else size.width).toFloat()
        val height = (if (rotated) size.width else size.height).toFloat()
        val sourceAspect = width / height
        val viewAspect = view.width.toFloat() / view.height
        val px = (.5f + (x/view.width-.5f)*minOf(1f,viewAspect/sourceAspect))*width
        val py = (.5f + (y/view.height-.5f)*minOf(1f,sourceAspect/viewAspect))*height
        return DisplayOrientedMeteringPointFactory(view.display,info,width,height).createPoint(px,py)
    }

    override fun close(done: () -> Unit) {
        active = false
        val current = pip
        if (current != null) current.close { pip = null; closeSingle(done) } else closeSingle(done)
    }

    private fun closeSingle(done: () -> Unit) {
        analysis?.clearAnalyzer()
        stills.close()
        snapshots.release("Camera closed")
        video.close { mediaIo.shutdown() }
        val info = camera?.cameraInfo
        val bound = listOfNotNull(preview, analysis, capture, recording).toTypedArray()
        if (info == null || provider == null) { done(); return }
        var finished = false
        lateinit var observer: Observer<CameraState>
        fun finish() {
            if (finished) return
            finished = true
            info.cameraState.removeObserver(observer)
            telemetry.event(session, "closed")
            if (releaseOnClose) shutDown(done) else done()
        }
        observer = Observer { if (it.type == CameraState.Type.CLOSED) finish() }
        // Observe forever so releasing the camera also completes while the Activity is stopped.
        info.cameraState.observeForever(observer)
        provider!!.unbind(*bound)
        if (info.cameraState.value?.type == CameraState.Type.CLOSED) finish()
    }

    /** [done] waits for the shutdown, at most [SHUTDOWN_LIMIT_MS]; the Camera2 open still waits for the release. */
    @SuppressLint("VisibleForTests")
    private fun shutDown(done: () -> Unit) {
        val started = SystemClock.elapsedRealtime()
        var called = false
        val finish = Runnable { if (!called) { called = true; done() } }
        provider!!.shutdownAsync().addListener({
            telemetry.event(session, "provider_shutdown", mapOf("ms" to SystemClock.elapsedRealtime() - started))
            handler.removeCallbacks(finish)
            finish.run()
        }, main)
        handler.postDelayed(finish, SHUTDOWN_LIMIT_MS)
    }

    private fun report(message: String, ok: Boolean) { handler.post { if (active) status(message, ok) } }

    override fun setPip(source: PipSource?, texture: android.graphics.SurfaceTexture?, output: LiveSize?,
        position: PipRect, done: (Result<Unit>) -> Unit) {
        val provider = provider
        if (!active || provider == null || pipChanging || stills.inFlight || video.busy || pip?.busy == true) {
            done(Result.failure(IllegalStateException("Camera busy"))); return
        }
        if (source != null && pipSources.none { it.key == source.key }) {
            done(Result.failure(IllegalArgumentException("PIP combination unavailable"))); return
        }
        pipChanging = true
        fun restore(result: Result<Unit>) {
            pip = null; pipPreview = null; pipChanging = false
            if (active) {
                try {
                    camera = provider.bindToLifecycle(owner,selector!!,*liveOutputs().targets.toTypedArray())
                    rebuilt(liveOutputs(),streamSizes())
                    report("CameraX · LIVE",true)
                } catch (e: Exception) { report("CameraX: ${e.message}",false) }
            }
            done(result)
        }
        fun startSession() {
            if (!active) { pipChanging = false; done(Result.failure(IllegalStateException("Camera closed"))); return }
            if (source == null) { restore(Result.success(Unit)); return }
            val infos = provider.availableCameraInfos.associateBy { Camera2CameraInfo.from(it).cameraId }
            lateinit var added: CameraXPipSession
            added = CameraXPipSession(context,owner,provider,handler,cameraId,source,
                listOf(infos.getValue(cameraId).cameraSelector,infos.getValue(source.id).cameraSelector),
                checkNotNull(texture),checkNotNull(output),position,
                resultCallback(telemetry.callback(session) { active }),
                bound = { mainCamera, previews ->
                    camera = mainCamera
                    pipPreview = previews.first()
                    rebuilt(StreamConfiguration(listOf(ConfiguredOutput(previewOutput,previews.first()))),
                        mapOf("preview" to previews.first().resolutionInfo?.resolution?.toString(),
                            "pip" to previews.last().resolutionInfo?.resolution?.toString(), "analysis" to null,"jpeg" to null))
                    telemetry.event(session,"pip_configured",mapOf("sourceId" to source.id,"physical" to false,"api" to "CameraX.ConcurrentCamera"))
                }, ready = {
                    if (pip === added && active) {
                        pipChanging = false; previewReady(); report("CameraX · LIVE",true); done(Result.success(Unit))
                    }
                }, failed = { reason ->
                    if (pip === added) added.close { restore(Result.failure(IllegalStateException(reason))) }
                }, recordingChanged = recordingState,
                notice = { message -> if (active) notice(message) })
            pip = added
            added.start()
        }
        fun start() {
            try { startSession() }
            catch (e: Exception) { restore(Result.failure(e)) }
        }
        val previous = pip
        if (previous != null) previous.close { if (pip === previous) pip = null; start() }
        else {
            val info = camera?.cameraInfo
            // CameraX requires leaving single-camera mode before a concurrent bind. Keep the selected
            // main ID/controls, and wait for its release instead of switching engines or opening Camera2.
            var released = false
            lateinit var observer: Observer<CameraState>
            fun proceed() {
                if (released) return
                released = true; info?.cameraState?.removeObserver(observer); start()
            }
            observer = Observer { if (it.type == CameraState.Type.CLOSED) proceed() }
            provider.unbindAll()
            if (info != null) info.cameraState.observeForever(observer)
            if (info == null || info.cameraState.value?.type == CameraState.Type.CLOSED) proceed()
        }
    }

    override fun movePip(rect: PipRect) { pip?.move(rect) }

    companion object {
        /** shutdownAsync took about 175 ms on device; a slower one must not hold the next engine for long. */
        const val SHUTDOWN_LIMIT_MS = 1000L
    }
}
