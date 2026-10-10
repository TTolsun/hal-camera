package dev.halcamera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.OutputConfiguration
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.Build
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.widget.Toast
import dev.halcamera.telemetry.Telemetry
import java.io.File
import java.util.concurrent.Executor

/**
 * The LIVE video recording of Camera2Engine, kept out of the engine: the MediaRecorder, its temp file, the
 * recording session and the save to the gallery. The benchmark RECORD stage has its own [BenchmarkRecorder],
 * because a measurement may not pick its own size, record audio or save to the gallery.
 *
 * Mode entry builds the preview/encoder/snapshot session; Start adds the encoder target without rebuilding it.
 * Stopping closes it, and the
 * session's close saves the file and asks the engine to rebuild the preview session. Camera thread only, except
 * [busy], which the main thread reads for the CLI's BUSY answer.
 */
internal class Camera2LiveRecorder(
    private val context: Context,
    private val handler: Handler,
    private val main: Handler,
    private val telemetry: Telemetry,
    private val sessionId: String,
    private val library: MediaLibrary,
    private val mediaIo: Executor,
    private val host: Host,
    private val createSession: (CameraDevice, List<OutputConfiguration>, CameraCaptureSession.StateCallback, Handler) -> Unit =
        { camera, outputs, callback, handler -> camera.createCaptureSessionByOutputConfigurations(outputs, callback, handler) },
) {
    interface Host : Camera2VideoSnapshot.Host {
        /** A benchmark engine never records LIVE video. */
        val benchmark: Boolean
        val settings: LiveVideo?
        val stabilization: LiveStabilization get() = LiveStabilization.AUTO
        val previewOutput: ConfiguredOutput<Surface>?
        val stillInFlight: Boolean
        /** The Live stream settings turned the JPEG output off, so the recording session carries no photo stream. */
        val snapshotDisabled: Boolean
        /** The JPEG size the Live stream settings ask for, or null for the default. */
        val requestedJpeg: LiveSize?
        val snapshotFromYuv: Boolean get() = false
        fun onSessionConfigured(session: CameraCaptureSession?)
        /** Starts repeating with the same output configuration used to create this session. */
        fun startRepeating(camera: CameraDevice, session: CameraCaptureSession, c: CameraCharacteristics, outputs: StreamConfiguration<Surface>)
        /** The recording session closed: rebuild the preview session if the camera is still in use. */
        fun rebuildPreview()
        fun orientationHint(c: CameraCharacteristics): Int
        fun chooseSize(sizes: Array<Size>, maxPixels: Long): Size
        fun recordingState(recording: Boolean)
        fun status(message: String, ok: Boolean)
        fun report(message: String, ok: Boolean)
        fun fail(e: Exception)
        fun preparedStreams(values: Map<String, Any?>) = Unit
    }

    private var inputSurface: Surface? = null
    private var launch: ((Boolean, () -> Unit) -> Unit)? = null
    private var retired: (() -> Unit)? = null
    val prepared: Boolean get() = state.prepared
    val recording: Boolean get() = state.recording
    private var recorder: MediaRecorder? = null
    private var relay: RecordingBufferRelay? = null
    private var file: File? = null
    @Volatile private var state = LiveRecorderState.EMPTY
    private var done: ((Result<Uri>) -> Unit)? = null
    private var failure: Exception? = null
    val busy: Boolean get() = state.busy
    val stopRequested: Boolean get() = state.stopping
    /** The photo taken during the recording (#175); its JPEG stream lives and dies with each recording session. */
    private val snapshots = Camera2VideoSnapshot(handler, main, telemetry, sessionId, library, mediaIo, host)
    @Volatile private var snapshotUnsupported: String? = null
    val snapshotStatus: SnapshotStatus get() = SnapshotStatus.of(recording && surface != null, stopRequested, snapshotUnsupported, snapshots.inFlight)
    fun captureSnapshot(done: (Result<PhotoResult>) -> Unit) {
        if (!snapshotStatus.canCapture) done(Result.failure(IllegalStateException(snapshotStatus.reason ?: "Snapshot unavailable")))
        else snapshots.capture(null, done)
    }
    /** The encoder surface while a recording session is up; repeating requests then use the record template. */
    var surface: Surface? = null
        private set

    /** A recording that is still being configured or already stopping has no session a request may touch. */
    val blocksRequests: Boolean get() = busy && (surface == null || stopRequested)

    fun start(audio: Boolean, started: () -> Unit, done: ((Result<Uri>) -> Unit)?) {
        handler.post {
            if (!host.active || state != LiveRecorderState.PREPARED || launch == null) {
                host.report("Video stream is not ready", false)
                main.post { done?.invoke(Result.failure(IllegalStateException("Video stream is not ready"))) }
                return@post
            }
            state = LiveRecorderState.STARTING
            this.done = done
            try { checkNotNull(launch).invoke(audio, started) }
            catch (e: Exception) { failure = e; stopOnCameraThread() }
        }
    }

    /** Configure all surfaces on mode entry; no encoder buffers are requested until Start. */
    @Suppress("DEPRECATION")
    fun prepare(ready: () -> Unit) {
        handler.post {
            val camera = host.camera
            if (camera == null || !host.active || host.benchmark || host.stillInFlight || state != LiveRecorderState.EMPTY || host.session == null) {
                main.post { done?.invoke(Result.failure(IllegalStateException("Camera is not ready to record"))) }
                return@post
            }
            state = LiveRecorderState.PREPARING
            failure = null
            host.report("Preparing video streams…", false)
            try {
                val c = host.characteristics ?: error("Camera characteristics unavailable")
                val sizes = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(MediaRecorder::class.java)
                val settings = host.settings
                val size = settings?.size?.androidSize() ?: requireNotNull(defaultLiveVideo(sizes.map { LiveSize(it.width, it.height) })) {
                    "No landscape recording size available"
                }.size.androidSize()
                if (settings != null) require(settings in liveStreamSupport(c).videos) { "Unsupported recording size, FPS or codec" }
                telemetry.event(sessionId, "live_recording_requested", mapOf("size" to size.toString(), "fps" to (settings?.fps ?: 30), "codec" to (settings?.codec ?: "H264")))
                val file = File.createTempFile("hal_recording_", ".mp4", context.cacheDir).also { this.file = it }
                val recorder = (if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()).also { this.recorder = it }
                inputSurface = android.media.MediaCodec.createPersistentInputSurface()
                configureRecorder(recorder, file, c, size, false)
                // API 33 lets the PRIVATE stream retain sensor timestamps for exact frame correlation.
                // Older versions keep the direct recorder path rather than guessing a clock offset for matching.
                // Keep EIS on a direct encoder Surface: relayed PRIVATE buffers were rejected
                // by the device encoder in the EIS + YUV + audio hardware check.
                val observeRecording = Build.VERSION.SDK_INT >= 33 && host.stabilization.video !in listOf(1, 2)
                val recordingOutput = OutputDescriptor("recording", OutputKind.RECORDING, true,
                    observable = observeRecording)
                val recordingSurface = if (observeRecording) {
                    val monotonicBefore = System.nanoTime()
                    val realtime = SystemClock.elapsedRealtimeNanos()
                    val monotonicAfter = System.nanoTime()
                    val timebase = RecordingTimebase(
                        c[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE] == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
                        realtime - (monotonicBefore + (monotonicAfter - monotonicBefore) / 2))
                    RecordingBufferRelay(checkNotNull(inputSurface), size, recordingOutput, telemetry, sessionId, timebase) { error ->
                        encoderFailed(recorder, error)
                    }.also { relay = it }.surface
                } else checkNotNull(inputSurface)
                val recordingConfigured = ConfiguredOutput(recordingOutput, recordingSurface)
                // The recording session with a JPEG output for the photo button (#175), or without one. A camera that
                // refuses the extra output at configure time gets the plain session instead of losing the recording.
                fun build(snapshot: ConfiguredOutput<Surface>?): Pair<List<OutputConfiguration>, CameraCaptureSession.StateCallback> {
                    val outputs = StreamConfiguration(listOfNotNull(host.previewOutput!!, recordingConfigured, snapshot))
                    val configurations = outputs.outputs.map { output -> OutputConfiguration(output.target).apply {
                        if (Build.VERSION.SDK_INT >= 33 && output.descriptor.observable && output.descriptor.kind != OutputKind.JPEG) {
                            timestampBase = OutputConfiguration.TIMESTAMP_BASE_SENSOR
                            if (Build.VERSION.SDK_INT >= 34) setReadoutTimestampEnabled(false)
                        }
                    } }
                    val retry = SnapshotSessionRetry()
                    val sessionCallback = object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            telemetry.event(sessionId, "video_session_configured")
                            if (!host.active || stopRequested) { session.close(); return }
                            host.onSessionConfigured(session)
                            try {
                                surface = recordingSurface
                                host.startRepeating(camera, session, c, outputs)
                                launch = { audio, started ->
                                    // Reuse the persistent encoder surface even when microphone permission
                                    // or the requested audio setting changes after entering Video.
                                    recorder.reset()
                                    configureRecorder(recorder, file, c, size, audio)
                                    recorder.start()
                                    state = LiveRecorderState.RECORDING
                                    host.startRepeating(camera, session, c, outputs)
                                    telemetry.event(sessionId, "recording_started", mapOf("size" to size.toString(), "audio" to audio,
                                        "fps" to (settings?.fps ?: 30), "codec" to (settings?.codec ?: "H264"),
                                        "snapshotSize" to if (snapshot != null) snapshots.size?.toString() else null, "snapshotUnavailable" to snapshotUnsupported))
                                    main.post { if (host.active) { host.recordingState(true); started() } }
                                    host.report(if (audio) "REC · Rolling. Sound and all." else "REC · Rolling. Silent cinema.", false)
                                    snapshotUnsupported?.let { host.notice(it) }
                                }
                                val preview = (telemetry.sessions[sessionId]?.get("negotiatedStreams") as? Map<*, *>)?.get("preview")
                                val sizes = mapOf("preview" to preview, "recording" to size.toString(),
                                    "recordingFormat" to (settings?.codec ?: "H264"),
                                    "snapshot" to if (snapshot != null) snapshots.size?.toString() else null,
                                    "snapshotSource" to if (host.snapshotFromYuv) "YUV" else "CAMERA")
                                telemetry.sessions.computeIfPresent(sessionId) { _, old -> old + mapOf("negotiatedStreams" to sizes) }
                                main.post { if (host.active) host.preparedStreams(sizes) }
                                state = LiveRecorderState.PREPARED
                                telemetry.event(sessionId, "video_prepared", mapOf("size" to size.toString()))
                                main.post { if (host.active) ready() }
                                host.report("Camera2 · VIDEO ready", true)
                            } catch (e: Exception) { failure = e; host.fail(e); session.close() }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            if (retry.onRejected(snapshot != null, host.active, stopRequested) {
                                snapshotUnsupported = VideoSnapshotPlan.REFUSED_REASON
                                telemetry.event(sessionId, "video_snapshot_unavailable", mapOf("reason" to "configure_failed", "size" to snapshots.size?.toString()))
                                snapshots.release("Session configuration failed")
                                try {
                                    val (plain, plainCallback) = build(null)
                                    createSession(camera, plain, plainCallback, handler)
                                } catch (e: Exception) { failure = e; finish(); host.report("Video start failed: ${e.message} · Please try again.", host.session != null) }
                            }) return
                            failure = IllegalStateException("Recording stream configuration rejected")
                            host.report("This recording stream configuration is not supported.", false)
                            session.close()
                        }
                        override fun onClosed(session: CameraCaptureSession) {
                            if (retry.replaced) return
                            val rebuild = recording || stopRequested
                            if (host.session === session) host.onSessionConfigured(null)
                            surface = null
                            finish()
                            val callback = retired; retired = null
                            if (callback != null) callback() else if (rebuild) host.rebuildPreview()
                        }
                    }
                    return configurations to sessionCallback
                }
                var plan: Pair<List<OutputConfiguration>, CameraCaptureSession.StateCallback>? = null
                var chosen: ConfiguredOutput<Surface>? = null
                snapshotUnsupported = null
                val jpegSizes = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(if (host.snapshotFromYuv) ImageFormat.YUV_420_888 else ImageFormat.JPEG)
                    .orEmpty().map { LiveSize(it.width, it.height) }.distinct()
                when {
                    host.snapshotDisabled -> snapshotUnsupported = VideoSnapshotPlan.OFF_REASON
                    jpegSizes.isEmpty() -> snapshotUnsupported = VideoSnapshotPlan.NO_SIZE_REASON
                    else -> {
                        for (candidate in host.requestedJpeg?.let { listOf(it) }
                            ?: VideoSnapshotPlan.candidates(null, jpegSizes, LiveSize(size.width, size.height))) {
                            val output = snapshots.open(candidate.androidSize(), host.snapshotFromYuv)
                            val attempt = build(output)
                            val supported = runCatching { checkLiveSession(camera, attempt.first, handler, attempt.second) { result ->
                                telemetry.event(sessionId, "live_recording_preflight", mapOf("result" to result, "snapshotSize" to candidate.toString()))
                            } }.isSuccess
                            if (supported) { chosen = output; plan = attempt; break }
                        }
                        if (chosen == null) { snapshots.release("Checking stream combinations"); snapshotUnsupported = VideoSnapshotPlan.REFUSED_REASON }
                    }
                }
                if (plan == null) {
                    plan = build(null)
                    checkLiveSession(camera, plan.first, handler, plan.second) { result ->
                        telemetry.event(sessionId, "live_recording_preflight", mapOf("result" to result))
                    }
                }
                createSession(camera, plan.first, plan.second, handler)
            } catch (e: Exception) {
                failure = e
                finish()
                host.report("Video preparation failed: ${e.message} · Change Live Streams to retry.", false)
            }
        }
    }

    fun discardForPip(done: () -> Unit) {
        retired = done
        host.session?.close() ?: run { finish(); retired = null; done() }
    }

    private fun configureRecorder(recorder: MediaRecorder, file: File, c: CameraCharacteristics, size: Size, audio: Boolean) {
        val settings = host.settings
        recorder.apply {
            if (audio) setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(file.absolutePath)
            setVideoEncoder(if (settings?.codec == "HEVC") MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
            if (audio) setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(size.width, size.height)
            setVideoFrameRate(settings?.fps ?: 30)
            setVideoEncodingBitRate(settings?.bitrate ?: 10_000_000)
            if (audio) {
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
            }
            setOrientationHint(host.orientationHint(c))
            setOnErrorListener { _, what, extra -> encoderFailed(recorder, IllegalStateException("Recorder error $what/$extra")) }
            setInputSurface(checkNotNull(inputSurface))
            prepare()
        }
    }

    internal fun encoderFailed(source: MediaRecorder, error: Exception) { handler.post {
        if (recorder !== source || state == LiveRecorderState.EMPTY || stopRequested) return@post
        telemetry.event(sessionId, "recording_error", mapOf("message" to error.toString()))
        failure = error
        stopOnCameraThread()
    } }

    fun stop() { handler.post { stopOnCameraThread() } }

    private fun stopOnCameraThread() {
        if (state == LiveRecorderState.EMPTY || stopRequested) return
        state = state.stop()
        host.report(if (recording) "Saving video… Wrapping the reel." else "Releasing video streams…", false)
        host.session?.close() ?: run { finish(); host.rebuildPreview() }
    }

    /** Stops and releases the recorder, then saves the file or reports why there is none. Also runs on camera close. */
    fun finish() {
        if (recorder == null && file == null && !busy) return
        val recorder = recorder
        this.recorder = null
        val relay = relay; this.relay = null
        if (Build.VERSION.SDK_INT >= 33) relay?.stopAccepting()
        val file = file; this.file = null
        val done = done; this.done = null
        val failure = failure; this.failure = null
        val wasStarted = recording
        launch = null
        snapshots.release("Recording ended"); snapshotUnsupported = null
        val stopped = wasStarted && recorder != null && runCatching { recorder.stop() }.isSuccess
        runCatching { recorder?.reset() }; runCatching { recorder?.release() }
        if (Build.VERSION.SDK_INT >= 33) relay?.closeAfterEncoder()
        inputSurface?.release(); inputSurface = null
        surface = null
        state = LiveRecorderState.EMPTY
        main.post { host.recordingState(false) }
        if (stopped && file != null) {
            mediaIo.execute {
                try {
                    val uri = library.saveVideo(file)
                    telemetry.event(sessionId, "video_saved", mapOf("uri" to uri.toString()))
                    main.post {
                        done?.invoke(if (failure == null) Result.success(uri) else Result.failure(failure))
                        // Same place as the photo notice: a toast at the bottom covered the photo/video mode buttons.
                        // Only a camera already closed has no notice line left, so that case keeps the toast.
                        if (host.active) host.status("Video saved. That's a wrap.", true)
                        else Toast.makeText(context.applicationContext, "Video saved. That's a wrap.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    main.post {
                        done?.invoke(Result.failure(e))
                        saveFailure("Video save failed: ${e.message}")
                    }
                } finally { file.delete() }
            }
        } else {
            file?.delete()
            main.post { done?.invoke(Result.failure(failure ?: IllegalStateException("Recording did not produce a playable video; record for longer before stopping"))) }
            if (wasStarted || failure != null) main.post { saveFailure(failure?.let { "Video failed: ${it.message}" } ?: "Video not saved: recording was too short or failed.") }
        }
    }

    private fun saveFailure(message: String) {
        if (host.active) host.notice("$message\nSave diagnostics with Save Events · ZIP.")
        else Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
    }
}
