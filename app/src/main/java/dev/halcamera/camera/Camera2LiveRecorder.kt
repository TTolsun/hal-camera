package dev.halcamera.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
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
 * Starting builds a new capture session with the preview and the encoder surfaces; stopping closes it, and the
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
) {
    interface Host {
        val camera: CameraDevice?
        val active: Boolean
        /** A benchmark engine never records LIVE video. */
        val benchmark: Boolean
        val characteristics: CameraCharacteristics?
        val previewSurface: Surface?
        val session: CameraCaptureSession?
        val stillInFlight: Boolean
        fun onSessionConfigured(session: CameraCaptureSession?)
        /** Starts the repeating recording request on the new session; [surface] is already [Camera2LiveRecorder.surface]. */
        fun startRepeating(camera: CameraDevice, session: CameraCaptureSession, c: CameraCharacteristics, surface: Surface)
        /** The recording session closed: rebuild the preview session if the camera is still in use. */
        fun rebuildPreview()
        fun orientationHint(c: CameraCharacteristics): Int
        fun chooseSize(sizes: Array<Size>, maxPixels: Long): Size
        fun recordingState(recording: Boolean)
        fun status(message: String, ok: Boolean)
        fun report(message: String, ok: Boolean)
        fun fail(e: Exception)
    }

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var started = false
    private var done: ((Result<Uri>) -> Unit)? = null
    private var failure: Exception? = null
    @Volatile var busy = false
        private set
    var stopRequested = false
        private set
    /** The encoder surface while a recording session is up; repeating requests then use the record template. */
    var surface: Surface? = null
        private set

    /** A recording that is still being configured or already stopping has no session a request may touch. */
    val blocksRequests: Boolean get() = busy && (surface == null || stopRequested)

    @Suppress("DEPRECATION")
    fun start(audio: Boolean, started: () -> Unit, done: ((Result<Uri>) -> Unit)?) {
        handler.post {
            val camera = host.camera
            if (camera == null || !host.active || host.benchmark || host.stillInFlight || busy || host.session == null) {
                main.post { done?.invoke(Result.failure(IllegalStateException("Camera is not ready to record"))) }
                return@post
            }
            busy = true
            this.done = done; stopRequested = false; failure = null
            host.report("녹화를 준비하고 있습니다…", false)
            try {
                val c = host.characteristics ?: error("Camera characteristics unavailable")
                val sizes = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(MediaRecorder::class.java)
                val size = host.chooseSize(sizes.filter { it.width >= it.height }.toTypedArray(), 1920L * 1080)
                val file = File.createTempFile("hal_recording_", ".mp4", context.cacheDir).also { this.file = it }
                val recorder = (if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()).also { this.recorder = it }
                recorder.apply {
                    if (audio) setAudioSource(MediaRecorder.AudioSource.MIC)
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(file.absolutePath)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    if (audio) setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setVideoSize(size.width, size.height)
                    setVideoFrameRate(30)
                    setVideoEncodingBitRate(10_000_000)
                    if (audio) {
                        setAudioEncodingBitRate(128_000)
                        setAudioSamplingRate(44_100)
                    }
                    setOrientationHint(host.orientationHint(c))
                    setOnErrorListener { _, what, extra -> handler.post {
                        telemetry.event(sessionId, "recording_error", mapOf("what" to what, "extra" to extra))
                        failure = IllegalStateException("Recorder error $what/$extra")
                        stop()
                    } }
                    prepare()
                }
                camera.createCaptureSession(listOf(host.previewSurface!!, recorder.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!host.active || stopRequested) { session.close(); return }
                        host.onSessionConfigured(session)
                        try {
                            surface = recorder.surface
                            host.startRepeating(camera, session, c, recorder.surface)
                            recorder.start(); this@Camera2LiveRecorder.started = true
                            telemetry.event(sessionId, "recording_started", mapOf("size" to size.toString(), "audio" to audio))
                            main.post { if (host.active) { host.recordingState(true); started() } }
                            host.report(if (audio) "REC · 영상과 소리를 녹화하고 있습니다" else "REC · 영상을 녹화하고 있습니다", false)
                        } catch (e: Exception) { failure = e; host.fail(e); session.close() }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        failure = IllegalStateException("Recording stream configuration rejected")
                        host.report("녹화 스트림 구성을 지원하지 않습니다", false)
                        session.close()
                    }
                    override fun onClosed(session: CameraCaptureSession) {
                        if (host.session === session) host.onSessionConfigured(null)
                        surface = null
                        finish()
                        host.rebuildPreview()
                    }
                }, handler)
            } catch (e: Exception) {
                failure = e
                finish()
                host.report("녹화 준비 실패: ${e.message} · 다시 시도할 수 있습니다", host.session != null)
            }
        }
    }

    fun stop() {
        handler.post {
            if (!busy) return@post
            stopRequested = true
            host.report("녹화를 저장하고 있습니다…", false)
            host.session?.close()
        }
    }

    /** Stops and releases the recorder, then saves the file or reports why there is none. Also runs on camera close. */
    fun finish() {
        if (recorder == null && file == null && !busy) return
        val recorder = recorder
        this.recorder = null
        val file = file; this.file = null
        val done = done; this.done = null
        val failure = failure; this.failure = null
        val wasStarted = started
        val stopped = wasStarted && recorder != null && runCatching { recorder.stop() }.isSuccess
        runCatching { recorder?.reset() }; runCatching { recorder?.release() }
        surface = null
        started = false; busy = false; stopRequested = false
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
                        if (host.active) host.status("갤러리에 동영상을 저장했습니다", true)
                        else Toast.makeText(context.applicationContext, "갤러리에 동영상을 저장했습니다", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    main.post {
                        done?.invoke(Result.failure(e))
                        Toast.makeText(context.applicationContext, "동영상 저장 실패: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } finally { file.delete() }
            }
        } else {
            file?.delete()
            main.post { done?.invoke(Result.failure(failure ?: IllegalStateException("Recording did not produce a playable video; record for longer before stopping"))) }
            if (wasStarted) main.post { Toast.makeText(context.applicationContext, "녹화가 너무 짧거나 실패하여 동영상을 저장하지 못했습니다", Toast.LENGTH_LONG).show() }
        }
    }
}
