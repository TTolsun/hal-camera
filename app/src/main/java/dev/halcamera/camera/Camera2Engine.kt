package dev.halcamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.TextureView
import dev.halcamera.telemetry.Telemetry

class Camera2Engine(
    private val context: Context,
    private val view: TextureView,
    private val cameraId: String,
    private val sessionId: String,
    private val telemetry: Telemetry,
    /** Benchmark profile streams. LIVE uses liveStreams or the default pixel budgets. */
    private val spec: StreamSpec? = null,
    private val liveStreams: LiveStreamSettings? = null,
    private val streamsConfigured: (Map<String, Any?>) -> Unit = {},
    private val streamsFailed: (String) -> Unit = {},
    private val previewReady: () -> Unit = {},
    private val previewFrame: () -> Unit = {},
    private val recordingState: (Boolean) -> Unit = {},
    /** A short notice that leaves the camera state alone, such as an AE relock that changed the exposure. */
    private val notice: (String) -> Unit = {},
    private val status: (String, Boolean) -> Unit
) : CameraEngine, MediaCapture, LiveTuning, TouchMetering {
    init { require(spec == null || liveStreams == null) { "LIVE settings cannot override a benchmark profile" } }
    private val thread = HandlerThread("CD.Camera2").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(CameraManager::class.java)
    @Volatile private var active = true
    private var opening = false
    private var device: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var displaySurface: Surface? = null
    private var previewRelay: PreviewBufferRelay? = null
    private var yuv: ImageReader? = null
    private var jpeg: ImageReader? = null
    private var finished = false
    private var closeDone: (() -> Unit)? = null
    override val mediaBusy: Boolean get() = stills.inFlight || video.busy || bench.recording
    private var previewSeen = false
    private val mediaIo = Executors.newSingleThreadExecutor()
    private val library = MediaLibrary(context)
    @Volatile private var zoomRatio = 1f
    /** One queued zoom submission at a time: a fast drag merges into the ratio that is current when it runs. */
    private val zoomQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var controls = LiveControls()
    @Volatile private var requestedControls = LiveControls()
    private val controlsQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Takes the AE lock again on a rebuilt session once AE has settled (#184). Camera thread only. */
    private val aeRelock = AeRelock()
    /** A pending [setControls] restores the chips of a reopened camera, so an AE lock in it waits like a rebuild. */
    private val restoreQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private var previewSize: Size? = null
    @Volatile private var chars: CameraCharacteristics? = null
    @Volatile private var configuredOutputs = StreamConfiguration<Surface>(emptyList())
    // Camera thread only. Requests still retained by Camera2 remain keys; retired requests do not leak.
    private val requestOutputs = java.util.WeakHashMap<CaptureRequest, List<String>>()
    private val callback = telemetry.callback(sessionId, streams = { requestOutputs[it] }) { active }

    private fun buildRequest(builder: CaptureRequest.Builder, outputs: List<ConfiguredOutput<Surface>>): CaptureRequest {
        outputs.forEach { builder.addTarget(it.target) }
        return builder.build().also { requestOutputs[it] = outputs.map { output -> output.descriptor.id } }
    }
    private val liveCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) =
            callback.onCaptureStarted(session, request, timestamp, frameNumber)
        override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) =
            callback.onCaptureProgressed(session, request, partialResult)
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            callback.onCaptureCompleted(session, request, result)
            stills.onLiveResult(result)
            if (spec == null) { relockStep(result); touchFocus.onResult(result) }
            if (spec == null && !previewSeen && request.tag == "preview") {
                previewSeen = true
                main.post { if (active) previewReady() }
            }
        }
        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) =
            callback.onCaptureFailed(session, request, failure)
        override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) =
            callback.onCaptureBufferLost(session, request, target, frameNumber)
    }
    // The benchmark RECORD stage keeps its own recorder: the LIVE one picks its own size, records audio and
    // saves to the gallery, none of which a measurement may do (docs/PLAN-Recording-v0.1.md 4 and 6).
    private val bench = BenchmarkRecorder(context, handler, telemetry, sessionId, spec?.record, object : BenchmarkRecorder.Host {
        override val camera: CameraDevice? get() = device
        override val cameraActive: Boolean get() = active
        override val previewSurface: Surface? get() = this@Camera2Engine.previewSurface
        override val session: CameraCaptureSession? get() = captureSession
        override fun onSessionConfigured(session: CameraCaptureSession) { captureSession = session }
        override val characteristics: CameraCharacteristics? get() = chars
        override val captureCallback: CameraCaptureSession.CaptureCallback get() = callback
        override fun orientationHint(chars: CameraCharacteristics): Int = outputRotation(chars)
        override fun recordRequest(camera: CameraDevice, chars: CameraCharacteristics, recorderSurface: Surface, recording: Boolean, iteration: Int): CaptureRequest =
            this@Camera2Engine.recordRequest(camera, chars, recorderSurface, recording, iteration)
    })
    /** Tap-to-focus (#168); the sequence lives in [Camera2TouchFocus]. */
    private val touchFocus = Camera2TouchFocus(handler, main, object : Camera2TouchFocus.Host {
        override val live get() = active
        override val afLocked get() = controls.afLock
        override val captureCallback: CameraCaptureSession.CaptureCallback get() = callback
        override fun event(kind: String, values: Map<String, Any?>) { telemetry.event(sessionId, kind, values) }
        override fun submitRepeating(kind: String, values: Map<String, Any?>) { this@Camera2Engine.submitRepeating(kind, values) }
        override fun trigger(kind: String, afTrigger: Int, callback: CameraCaptureSession.CaptureCallback) = withLiveSession(kind) { camera, session, c ->
            session.capture(repeatingRequest(camera, c, afTrigger = afTrigger), callback, handler)
        }
    })
    /** Stills and the flash precapture (#176); a benchmark engine measures its stills and saves none. */
    private val stills: Camera2StillCapture = Camera2StillCapture(context, handler, main, telemetry, sessionId, library, mediaIo, spec != null, object : Camera2StillCapture.Host {
        override val camera: CameraDevice? get() = device
        override val session: CameraCaptureSession? get() = captureSession
        override val characteristics: CameraCharacteristics get() = chars ?: manager.getCameraCharacteristics(cameraId)
        override val active: Boolean get() = this@Camera2Engine.active
        override val recordingBusy: Boolean get() = video.busy
        override val captureYuv: Boolean get() = liveStreams == null || liveStreams.yuv != null
        override val captureJpeg: Boolean get() = liveStreams == null || liveStreams.jpeg != null
        override val needsPrecapture: Boolean get() = requestControls().needsPrecapture
        override val flashName: String get() = controls.flash.name
        override val zoomRequested: Float get() = zoomRatio
        override val captureCallback: CameraCaptureSession.CaptureCallback get() = callback
        override fun orientation(c: CameraCharacteristics): Int = outputRotation(c)
        override fun stillRequest(camera: CameraDevice, c: CameraCharacteristics, tag: String, rotation: Int?) = this@Camera2Engine.stillRequest(camera, c, tag, rotation)
        override fun precaptureTrigger(callback: CameraCaptureSession.CaptureCallback) = withLiveSession("precapture_trigger") { camera, session, c ->
            session.capture(previewRequest(camera, c, aeTrigger = CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START), callback, handler)
        }
        override fun report(message: String, ok: Boolean) = this@Camera2Engine.report(message, ok)
        override fun fail(e: Exception) = this@Camera2Engine.fail(e)
    })
    /** The LIVE video recording; the benchmark RECORD stage uses [bench] instead. */
    private val video: Camera2LiveRecorder = Camera2LiveRecorder(context, handler, main, telemetry, sessionId, library, mediaIo, object : Camera2LiveRecorder.Host {
        override val camera: CameraDevice? get() = device
        override val active: Boolean get() = this@Camera2Engine.active
        override val benchmark: Boolean get() = spec != null
        override val settings: LiveVideo? get() = liveStreams?.video
        override val characteristics: CameraCharacteristics? get() = chars
        override val previewOutput: ConfiguredOutput<Surface>? get() =
            configuredOutputs.outputs.find { it.descriptor.kind == OutputKind.PREVIEW }
        override val session: CameraCaptureSession? get() = captureSession
        override val stillInFlight: Boolean get() = stills.inFlight
        override val snapshotDisabled: Boolean get() = liveStreams != null && liveStreams.jpeg == null
        override val requestedJpeg: LiveSize? get() = liveStreams?.jpeg
        override val captureCallback: CameraCaptureSession.CaptureCallback get() = callback
        override fun orientation(c: CameraCharacteristics): Int = outputRotation(c)
        override fun snapshotRequest(camera: CameraDevice, c: CameraCharacteristics, tag: String, rotation: Int) =
            this@Camera2Engine.snapshotRequest(camera, c, tag, rotation)
        override fun notice(text: String) { main.post { if (active) this@Camera2Engine.notice(text) } }
        override fun onSessionConfigured(session: CameraCaptureSession?) { captureSession = session }
        override fun startRepeating(camera: CameraDevice, session: CameraCaptureSession, c: CameraCharacteristics, outputs: StreamConfiguration<Surface>) {
            configuredOutputs = outputs
            telemetry.configureCallbackStreams(sessionId, outputs.metadata())
            startRelock()
            session.setRepeatingRequest(liveRecordRequest(camera, c), liveCallback, handler)
            relockFocus()
        }
        override fun rebuildPreview() { if (this@Camera2Engine.active) { device?.let { configure(it) } } }
        override fun orientationHint(c: CameraCharacteristics): Int = outputRotation(c)
        override fun chooseSize(sizes: Array<Size>, maxPixels: Long): Size = choose(sizes, maxPixels)
        override fun recordingState(recording: Boolean) = this@Camera2Engine.recordingState(recording)
        override fun status(message: String, ok: Boolean) = this@Camera2Engine.status(message, ok)
        override fun report(message: String, ok: Boolean) = this@Camera2Engine.report(message, ok)
        override fun fail(e: Exception) = this@Camera2Engine.fail(e)
    })
    override fun start() {
        telemetry.registerSession(sessionId, "Camera2", manager, cameraId)
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { handler.post { open() } }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                if (active) {
                    previewFrame()
                    configuredOutputs.outputs.find { it.target === previewSurface }?.descriptor?.let { output ->
                        telemetry.recorder.record(sessionId, "preview_presented", sensorNs = surface.timestamp,
                            values = mapOf("stream" to output.id))
                    }
                }
            }
        }
        if (view.isAvailable) handler.post { open() }
    }
    @SuppressLint("MissingPermission")
    private fun open() {
        if (!active || opening || device != null) return
        try {
            opening = true
            telemetry.event(sessionId, "open_call", mapOf("api" to "CameraManager.openCamera"))
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    telemetry.event(sessionId, "opened")
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
            yuv?.close(); jpeg?.close()
            if (previewRelay == null) { previewSurface?.release(); displaySurface?.release() }
            val chars = manager.getCameraCharacteristics(cameraId).also { this.chars = it }
            val map = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] ?: error("No stream configuration")
            liveStreams?.let { settings ->
                liveStreamSupport(chars).rejection(settings)?.let { error(it) }
                telemetry.event(sessionId, "live_streams_requested", settings.metadata())
                settings.fps?.let { fps ->
                    val durations = listOfNotNull(map.getOutputMinFrameDuration(SurfaceTexture::class.java, settings.preview.androidSize()),
                        settings.yuv?.let { map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, it.androidSize()) },
                        settings.jpeg?.let { map.getOutputMinFrameDuration(ImageFormat.JPEG, it.androidSize()) })
                    require(durations.all { it == 0L || it <= 1_000_000_000L / fps.min + 1 }) { "선택한 크기의 최소 프레임 시간이 FPS 하한을 초과합니다." }
                }
            }
            // With a profile spec the sizes are exact and unavailable ones fail the configure step: measuring a
            // smaller stream under the same profile id would corrupt every comparison made with that id.
            val size = spec?.preview?.also { require(it in map.getOutputSizes(SurfaceTexture::class.java)) { "preview $it unsupported" } }
                ?: liveStreams?.preview?.androidSize() ?: choose(map.getOutputSizes(SurfaceTexture::class.java), 1280L * 720)
            val yuvSize = spec?.yuv?.also { require(it in map.getOutputSizes(ImageFormat.YUV_420_888)) { "yuv $it unsupported" } }
                ?: if (liveStreams != null) liveStreams.yuv?.androidSize() else choose(map.getOutputSizes(ImageFormat.YUV_420_888), 640L * 480)
            val jpegSize = spec?.jpeg?.also { require(it in map.getOutputSizes(ImageFormat.JPEG)) { "jpeg $it unsupported" } }
                ?: if (liveStreams != null) liveStreams.jpeg?.androidSize() else choose(map.getOutputSizes(ImageFormat.JPEG), 1920L * 1080)
            val texture = view.surfaceTexture ?: error("Preview surface unavailable")
            texture.setDefaultBufferSize(size.width, size.height)
            // A recording session and its replacement preview session reuse the same display producer.
            // Reconnecting a second ImageWriter while the first still owns TextureView would fail.
            if (Build.VERSION.SDK_INT >= 33 && spec == null) {
                if (previewRelay == null) {
                    displaySurface = Surface(texture)
                    previewRelay = PreviewBufferRelay(displaySurface!!, size, telemetry, sessionId) { error ->
                        handler.post { if (active) fail(error) }
                    }
                } else require(previewSize == size) { "Preview relay size changed within a camera session" }
                previewSurface = previewRelay!!.output.target
            } else previewSurface = Surface(texture)
            previewSize = size
            main.post { if (active) view.fitPreview(size) }
            val previewOutput = OutputDescriptor("preview", OutputKind.PREVIEW, repeating = true, observable = previewRelay != null)
            val yuvOutput = OutputDescriptor("analysis_acquire_latest", OutputKind.YUV, repeating = true, stillCapture = spec == null)
            val jpegOutput = OutputDescriptor("still", OutputKind.JPEG, repeating = false, stillCapture = true)
            yuv = yuvSize?.let { reader(it, ImageFormat.YUV_420_888, yuvOutput) }
            jpeg = jpegSize?.let { reader(it, ImageFormat.JPEG, jpegOutput) }
            val outputs = StreamConfiguration(listOfNotNull(ConfiguredOutput(previewOutput, previewSurface!!),
                yuv?.let { ConfiguredOutput(yuvOutput, it.surface) }, jpeg?.let { ConfiguredOutput(jpegOutput, it.surface) }))
            // The effective values go into the event so conditions.effective in the run JSON reports what the
            // camera actually ran with, not what the profile asked for (3.1, fixed-focus cameras run AF OFF).
            val sizes = mapOf(
                "preview" to size.toString(), "analysis" to yuvSize?.toString(), "jpeg" to jpegSize?.toString(),
                "afMode" to afMode(chars), "fpsRange" to (spec?.fpsRange?.toString() ?: liveStreams?.fps?.toString())
            )
            if (spec != null) telemetry.sessions.computeIfPresent(sessionId) { _, old -> old + mapOf("negotiatedStreams" to sizes) }
            telemetry.event(sessionId, "configure_requested", sizes)
            val configurations = outputs.outputs.map { output -> OutputConfiguration(output.target).apply {
                if (Build.VERSION.SDK_INT >= 33 && output.descriptor.kind == OutputKind.PREVIEW && previewRelay != null) {
                    timestampBase = OutputConfiguration.TIMESTAMP_BASE_SENSOR
                    if (Build.VERSION.SDK_INT >= 34) setReadoutTimestampEnabled(false)
                }
            } }
            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    telemetry.event(sessionId, "session_configured", sizes)
                    if (!active) { session.close(); return }
                    captureSession = session
                    configuredOutputs = outputs
                    telemetry.configureCallbackStreams(sessionId, outputs.metadata())
                    try {
                        startRelock()
                        telemetry.event(sessionId, "repeating_submit", mapOf("zoomRequested" to zoomRatio))
                        session.setRepeatingRequest(previewRequest(camera, chars), liveCallback, handler)
                        relockFocus()
                        telemetry.event(sessionId, "configured", sizes)
                        if (spec == null) telemetry.sessions.computeIfPresent(sessionId) { _, old -> old + mapOf("negotiatedStreams" to sizes) }
                        if (spec == null) main.post { if (active) streamsConfigured(sizes) }
                        report("Camera2 · LIVE", true)
                    } catch (e: Exception) { fail(e, configuration = true) }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    telemetry.event(sessionId, "configure_failed", sizes)
                    report("Camera2 stream combination rejected; select another camera", false)
                    if (spec == null) main.post { if (active) streamsFailed("카메라가 요청한 출력 조합을 거부했습니다.") }
                }
            }
            if (spec == null) checkLiveSession(camera, configurations, handler, sessionCallback) { result ->
                telemetry.event(sessionId, "live_stream_preflight", mapOf("result" to result))
            }
            camera.createCaptureSessionByOutputConfigurations(configurations, sessionCallback, handler)
        } catch (e: Exception) { fail(e, configuration = true) }
    }
    /** [afTrigger] and [aeTrigger] go on a one-shot capture only; the repeating request always leaves them IDLE. */
    private fun previewRequest(camera: CameraDevice, chars: CameraCharacteristics, afTrigger: Int? = null, aeTrigger: Int? = null): CaptureRequest =
        camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, afMode(chars))
            (spec?.fpsRange ?: liveStreams?.fps?.let { android.util.Range(it.min, it.max) })?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            applyZoom(this, chars)
            if (spec == null) { applyLiveControls(requestControls()); applyTouch(touchFocus) }
            afTrigger?.let { set(CaptureRequest.CONTROL_AF_TRIGGER, it) }
            aeTrigger?.let { set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, it) }
            setTag("preview")
        }.let { buildRequest(it, configuredOutputs.repeating) }
    /** The LIVE recording request. Zoom and the controls change it in place: the targets stay preview + encoder. */
    private fun liveRecordRequest(camera: CameraDevice, c: CameraCharacteristics, afTrigger: Int? = null): CaptureRequest =
        camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            liveStreams?.video?.fps?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(it, it)) }
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            val modes = c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
            set(CaptureRequest.CONTROL_AF_MODE, if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in modes)
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CaptureRequest.CONTROL_AF_MODE_OFF)
            applyZoom(this, c)
            applyLiveControls(requestControls()); applyTouch(touchFocus)
            afTrigger?.let { set(CaptureRequest.CONTROL_AF_TRIGGER, it) }
            setTag("recording")
        }.let { buildRequest(it, configuredOutputs.repeating) }
    /**
     * The photo during a recording (#175): TEMPLATE_VIDEO_SNAPSHOT with the recording request's AF and FPS, aimed at
     * every output of the recording session (preview, encoder and the JPEG stream), as the CTS video snapshot does.
     */
    private fun snapshotRequest(camera: CameraDevice, c: CameraCharacteristics, tag: String, rotation: Int): CaptureRequest =
        camera.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT).apply {
            liveStreams?.video?.fps?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(it, it)) }
            set(CaptureRequest.JPEG_ORIENTATION, rotation)
            set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            val modes = c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
            set(CaptureRequest.CONTROL_AF_MODE, if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in modes)
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CaptureRequest.CONTROL_AF_MODE_OFF)
            applyZoom(this, c)
            applyLiveControls(requestControls()); applyTouch(touchFocus)
            setTag(tag)
        }.let { buildRequest(it, configuredOutputs.outputs) }
    /** Whichever LIVE request is repeating now: the recording one while the recorder runs, the preview one otherwise. */
    private fun repeatingRequest(camera: CameraDevice, c: CameraCharacteristics, afTrigger: Int? = null, aeTrigger: Int? = null): CaptureRequest =
        video.surface?.let { liveRecordRequest(camera, c, afTrigger) } ?: previewRequest(camera, c, afTrigger, aeTrigger)
    /**
     * Runs [submit] against the current session, or skips it when there is no session to change. A recording that
     * is still being configured or already stopping has none that may be touched; the next session reads the
     * current zoom and controls when it configures, so a skipped input is not lost. A session closed under us by
     * a stop that raced the input is logged, not reported as a camera failure.
     */
    /** Returns whether [submit] ran to the end, so a caller waiting on its result knows none will come. */
    private fun withLiveSession(kind: String, submit: (CameraDevice, CameraCaptureSession, CameraCharacteristics) -> Unit): Boolean {
        val camera = device; val session = captureSession; val c = chars
        if (camera == null || session == null || c == null || !active || spec != null) return false
        if (video.blocksRequests) { telemetry.event(sessionId, "request_deferred", mapOf("for" to kind)); return false }
        return try { submit(camera, session, c); true }
        catch (e: IllegalStateException) { telemetry.event(sessionId, "request_skipped", mapOf("for" to kind, "reason" to e.toString())); false }
        catch (e: CameraAccessException) { telemetry.event(sessionId, "request_skipped", mapOf("for" to kind, "reason" to e.toString())); false }
        catch (e: Exception) { fail(e); false }
    }
    private fun submitRepeating(kind: String, values: Map<String, Any?>): Boolean = withLiveSession(kind) { camera, session, c ->
        telemetry.event(sessionId, kind, values + mapOf("api" to "setRepeatingRequest", "recording" to (video.surface != null)))
        session.setRepeatingRequest(repeatingRequest(camera, c), liveCallback, handler)
    }
    /**
     * AF lock is the continuous AF mode's trigger transition (#169): START scans once and holds the lens
     * (FOCUSED_LOCKED or NOT_FOCUSED_LOCKED in the results) until CANCEL. The trigger goes on a single capture; the
     * repeating request keeps the same AF mode with the trigger IDLE, which leaves the lock in place.
     */
    private fun sendAfTrigger(start: Boolean) = withLiveSession("af_trigger") { camera, session, c ->
        telemetry.event(sessionId, "af_trigger", mapOf("trigger" to if (start) "START" else "CANCEL"))
        val trigger = if (start) CaptureRequest.CONTROL_AF_TRIGGER_START else CaptureRequest.CONTROL_AF_TRIGGER_CANCEL
        session.capture(repeatingRequest(camera, c, afTrigger = trigger), liveCallback, handler)
    }
    /** A new session (recording start or stop) may not keep the old AF state, so a held lock is taken again. */
    private fun relockFocus() { if (spec == null && controls.afLock) sendAfTrigger(true) }
    /** [controls] as the requests carry them: AE stays unlocked while [aeRelock] waits for a rebuilt session. */
    private fun requestControls(): LiveControls = if (aeRelock.waiting) controls.copy(aeLock = false) else controls
    /**
     * Called on every new LIVE session before its first request. With the lock on, the session starts unlocked and
     * [relockStep] locks it once AE has settled; a timeout locks it anyway so a scene AE never settles on still
     * ends up locked.
     */
    private fun startRelock() {
        if (spec != null) return
        touchFocus.dropFocus() // a tapped AF point is one-shot; a pressed AE point stays and is relocked on
        val generation = aeRelock.sessionRebuilt(controls.aeLock)
        if (!aeRelock.waiting) return
        telemetry.event(sessionId, "ae_relock_wait", mapOf("recording" to (video.surface != null)))
        handler.postDelayed({ if (aeRelock.timedOut(generation)) sendRelock("timeout", null) }, AeRelock.TIMEOUT_MS)
    }
    private fun sendRelock(reason: String, state: Int?) {
        if (!submitRepeating("ae_relock", mapOf("reason" to reason, "aeState" to state))) aeRelock.relockNotSent()
    }
    /** Called on the main thread, where the TextureView transform is read. */
    override fun meterAt(x: Float, y: Float, exposure: Boolean, feedback: (TouchPhase) -> Unit): Boolean {
        val c = chars ?: return false
        val (af, ae) = touchSupport(c)
        if (spec != null || !active || !(if (exposure) ae else af)) return false
        val (u, v) = view.naturalPoint(x, y) ?: return false
        handler.post {
            val size = previewSize ?: return@post
            if (exposure) touchFocus.exposeAt(c, u, v, zoomRatio, size, feedback) else touchFocus.focusAt(c, u, v, zoomRatio, size, feedback)
        }
        return true
    }
    private fun relockStep(result: TotalCaptureResult) {
        val state = result[CaptureResult.CONTROL_AE_STATE]
        val time = result[CaptureResult.SENSOR_EXPOSURE_TIME]; val iso = result[CaptureResult.SENSOR_SENSITIVITY]
        when (val step = aeRelock.onResult(state, if (time != null && iso != null) AeRelock.Exposure(time, iso) else null)) {
            AeRelock.Step.None -> Unit
            AeRelock.Step.Relock -> sendRelock("settled", state)
            is AeRelock.Step.Relocked -> {
                telemetry.event(sessionId, "ae_relocked", mapOf(
                    "beforeExposureNs" to step.before?.timeNs, "beforeIso" to step.before?.iso,
                    "exposureNs" to step.after.timeNs, "iso" to step.after.iso, "deltaEv" to step.deltaEv))
                LiveControlText.relockNotice(step.deltaEv)?.let { text -> main.post { if (active) notice(text) } }
            }
        }
    }
    /**
     * Coalesced like [setZoom]: dragging the EV dial across 0.1 EV steps calls this once per step, and every call
     * that lands before the queued one runs only replaces [requestedControls]. An AF lock toggled on and off within
     * one turn therefore sends no trigger, which matches the final state.
     */
    override fun setControls(next: LiveControls, restore: Boolean) {
        requestedControls = next
        if (restore) restoreQueued.set(true)
        if (!controlsQueued.compareAndSet(false, true)) return
        handler.post {
            controlsQueued.set(false)
            val old = controls
            val now = requestedControls
            controls = now
            // A restored lock meets a session that has just started metering: relock it like a rebuilt one.
            if (old.aeLock != now.aeLock) { if (restoreQueued.getAndSet(false) && now.aeLock) startRelock() else aeRelock.lockChanged(now.aeLock) }
            restoreQueued.set(false)
            if (old.afLock && !now.afLock) touchFocus.dropFocus()
            if (old.aeLock && !now.aeLock) touchFocus.dropExposure()
            submitRepeating("controls_set", mapOf("evIndex" to now.evIndex, "aeLock" to now.aeLock, "afLock" to now.afLock, "flash" to now.flash.name))
            if (old.afLock != now.afLock) sendAfTrigger(now.afLock)
        }
    }
    /** API 30+ uses CONTROL_ZOOM_RATIO (ultra-wide below 1x possible). Older devices crop the active array, so only >= 1x. */
    private fun applyZoom(builder: CaptureRequest.Builder, chars: CameraCharacteristics) {
        val ratio = zoomRatio
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val range = chars[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, if (range != null) range.clamp(ratio) else 1f)
            return
        }
        val active = chars[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE] ?: return
        val max = chars[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM] ?: 1f
        val r = ratio.coerceIn(1f, max)
        val w = (active.width() / r).toInt(); val h = (active.height() / r).toInt()
        val left = active.left + (active.width() - w) / 2; val top = active.top + (active.height() - h) / 2
        builder.set(CaptureRequest.SCALER_CROP_REGION, android.graphics.Rect(left, top, left + w, top + h))
    }
    /**
     * Also works while recording (#174): the recording request is rebuilt with the new ratio and the same preview
     * and encoder targets. The ratio is stored at once and one submission is queued; inputs that arrive before it
     * runs only move the stored ratio, so a fast drag sends one request per camera-thread turn, not one per tap.
     */
    override fun setZoom(ratio: Float) {
        zoomRatio = ratio
        if (!zoomQueued.compareAndSet(false, true)) return
        handler.post {
            zoomQueued.set(false)
            submitRepeating("zoom_set", mapOf("zoomRequested" to zoomRatio))
        }
    }
    private fun afMode(chars: CameraCharacteristics): Int {
        val modes = chars[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
        return if (modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE else CaptureRequest.CONTROL_AF_MODE_OFF
    }
    private fun reader(size: Size, format: Int, output: OutputDescriptor): ImageReader =
        ImageReader.newInstance(size.width, size.height, format, 3).also { reader ->
            val target = reader.surface
            reader.setOnImageAvailableListener({ source ->
                if (configuredOutputs.outputs.none { it.descriptor.id == output.id && it.target === target }) return@setOnImageAvailableListener
                try {
                    // Drain in order while a still is pending: acquireLatestImage can discard its YUV frame.
                    val next = if (stills.pairing) source.acquireNextImage() else source.acquireLatestImage()
                    next?.use { image ->
                        if (active) telemetry.image(sessionId, image.timestamp, image.width, image.height, image.format, output.id)
                        if (active && !previewSeen && format == ImageFormat.YUV_420_888) {
                            previewSeen = true
                            main.post { if (active) previewReady() }
                        }
                        stills.onImage(image, format, output.id)
                    }
                } catch (e: Exception) { stills.onImageFailed(e) }
            }, handler)
        }
    override fun capture() = stills.capture(null, null)
    override fun capturePhoto(requestId: String, done: (Result<PhotoResult>) -> Unit) = stills.capture(requestId, done)

    /** The still request: JPEG only for a benchmark still, YUV + JPEG with the output [rotation] for a LIVE pair. */
    private fun stillRequest(camera: CameraDevice, c: CameraCharacteristics, tag: String, rotation: Int?): CaptureRequest =
        camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            liveStreams?.fps?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(it.min, it.max)) }
            if (rotation != null) {
                set(CaptureRequest.JPEG_ORIENTATION, rotation)
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            }
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, afMode(c))
            applyZoom(this, c)
            // Same AE mode, EV, lock and torch as the preview, so the still is exposed as the preview showed it.
            if (spec == null) { applyLiveControls(requestControls()); applyTouch(touchFocus) }
            setTag(tag)
        }.let { buildRequest(it, configuredOutputs.still) }

    private fun outputRotation(c: CameraCharacteristics): Int {
        val degrees = when (view.display?.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        val sensor = c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0
        return (sensor + if (c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT) degrees else -degrees + 360) % 360
    }

    override fun startRecording(audio: Boolean, started: () -> Unit, done: ((Result<android.net.Uri>) -> Unit)?) = video.start(audio, started, done)

    // ---- Benchmark RECORD stage (docs/PLAN-Recording-v0.1.md 6) ----
    //
    // The four calls below are what BenchmarkRunner.Driver needs; the state machine itself lives in
    // [BenchmarkRecorder], which runs on this engine's camera thread through its Host.

    fun prepareBenchmarkRecording(iteration: Int) = bench.prepare(iteration)

    fun startBenchmarkRecording() = bench.start()

    fun stopBenchmarkRecording() = bench.stop()

    fun abortBenchmarkRecording() = bench.abort()

    /**
     * The recording request. [recording] decides the targets: before the recorder has started only the preview
     * is fed, afterwards the recorder surface joins and the request carries the cycle's tag so the extractor can
     * tell recording frames from the preview frames that came before them.
     */
    private fun recordRequest(camera: CameraDevice, c: CameraCharacteristics, recorderSurface: Surface, recording: Boolean, iteration: Int): CaptureRequest =
        camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface!!)
            if (recording) addTarget(recorderSurface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, afMode(c))
            spec?.fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            applyZoom(this, c)
            setTag(if (recording) RecordSpec.tag(iteration) else RecordSpec.prepareTag(iteration))
        }.build()

    override fun stopRecording() = video.stop()

    override val snapshot: SnapshotStatus get() = video.snapshotStatus
    override fun captureSnapshot(done: (Result<PhotoResult>) -> Unit) = video.captureSnapshot(done)

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
        stills.close()
        video.finish()
        bench.release()
        captureSession?.close(); captureSession = null
        yuv?.close(); yuv = null
        jpeg?.close(); jpeg = null
        if (Build.VERSION.SDK_INT >= 33) previewRelay?.close()
        previewRelay = null
        previewSurface?.release(); previewSurface = null
        displaySurface?.release(); displaySurface = null
        telemetry.event(sessionId, "closed")
        closeDone?.let { main.post(it) }
        thread.quitSafely()
        mediaIo.shutdown()
    }
    private fun fail(e: Exception, configuration: Boolean = false) {
        telemetry.event(sessionId, "camera_error", mapOf("message" to e.toString())); report("Camera2: ${e.message}", false)
        if (spec == null && (configuration || captureSession == null)) main.post { if (active) streamsFailed(e.message ?: e.toString()) }
    }
    private fun report(message: String, ok: Boolean) { main.post { if (active) status(message, ok) } }
}
