package dev.halcamera.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Handler
import android.view.Surface
import dev.halcamera.telemetry.Telemetry
import java.io.File

/**
 * The benchmark RECORD stage of one Camera2 session (docs/PLAN-Recording-v0.1.md 6). Split out of
 * [Camera2Engine] so the four measured recorder steps and their state machine live apart from the LIVE
 * screen's gallery recorder, which picks its own size, records audio and saves the file: none of which a
 * measurement may do (docs/PLAN-Recording-v0.1.md 4 and 6).
 *
 * The calls report nothing directly: every step becomes a telemetry event, and BenchmarkActivity turns those
 * events into runner signals, exactly as the open, configure and capture steps already do. That keeps the
 * measured moments on the engine's own thread, right beside the call they time. All entry points post to
 * [handler], the engine's camera thread, so this class shares the engine's threading model.
 */
class BenchmarkRecorder(
    private val context: Context,
    private val handler: Handler,
    private val telemetry: Telemetry,
    private val sessionId: String,
    /** The RECORD stage's profile conditions; null for a profile without one, which fails every prepare. */
    private val spec: RecordSpec?,
    private val host: Host
) {
    /** The engine internals one recording cycle needs. Read on the camera thread only. */
    interface Host {
        val camera: CameraDevice?
        val cameraActive: Boolean
        val previewSurface: Surface?
        /** The engine's current capture session; a successful prepare replaces it with the recording session. */
        var session: CameraCaptureSession?
        val characteristics: CameraCharacteristics?
        /** The engine's one telemetry callback. Shared because its FrameTracker carries per-session frame state. */
        val captureCallback: CameraCaptureSession.CaptureCallback
        fun orientationHint(chars: CameraCharacteristics): Int
        /**
         * The recording request: preview only while [recording] is false (3.1 measures MediaRecorder.start()
         * to the first capture of a recording request, so none may exist before start returns), preview plus
         * recorder surface with the cycle's tag afterwards.
         */
        fun recordRequest(camera: CameraDevice, chars: CameraCharacteristics, recorderSurface: Surface, recording: Boolean, iteration: Int): CaptureRequest
    }

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var iteration = -1
    private var started = false
    @Volatile var recording = false
        private set

    /**
     * Reconfigure the session with the recorder stream and prepare the recorder, without starting it. Emits
     * `record_configured` when the session is ready, `record_configure_failed` when the camera refuses the
     * combination (which ends the whole stage), or `record_failed` for anything else.
     */
    @Suppress("DEPRECATION")
    fun prepare(iteration: Int) {
        handler.post {
            val values = mapOf("iteration" to iteration)
            telemetry.event(sessionId, "record_prepare_call", values)
            val camera = host.camera
            val recordSpec = spec
            if (camera == null || !host.cameraActive || recordSpec == null) {
                telemetry.event(sessionId, "record_configure_failed",
                    values + mapOf("reason" to if (recordSpec == null) "no_record_spec" else "camera_not_ready"))
                return@post
            }
            release()
            this.iteration = iteration
            try {
                val c = host.characteristics ?: error("Camera characteristics unavailable")
                val encoder = videoEncoder(recordSpec.codec) ?: error("unsupported codec ${recordSpec.codec}")
                val file = File.createTempFile("hal_bench_", ".mp4", context.cacheDir).also { this.file = it }
                val recorder = (if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder())
                    .also { this.recorder = it }
                recorder.apply {
                    // No audio track: no metric in METRICS.md chapter 3 reads it, and a denied microphone
                    // permission would fail a run that has nothing to do with audio.
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(file.absolutePath)
                    setVideoEncoder(encoder)
                    setVideoSize(recordSpec.size.width, recordSpec.size.height)
                    setVideoFrameRate(recordSpec.fps)
                    setVideoEncodingBitRate(recordSpec.bitrate)
                    setOrientationHint(host.orientationHint(c))
                    setOnErrorListener { _, what, extra ->
                        handler.post {
                            telemetry.event(sessionId, "record_failed",
                                values + mapOf("reason" to "recorder_error", "what" to what, "extra" to extra))
                        }
                    }
                    prepare()
                }
                recording = true
                val streams = mapOf("record" to recordSpec.size.toString(), "codec" to recordSpec.codec,
                    "bitrate" to recordSpec.bitrate, "fps" to recordSpec.fps)
                camera.createCaptureSession(listOf(host.previewSurface!!, recorder.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!host.cameraActive) { session.close(); return }
                        host.session = session
                        try {
                            // Preview only until MediaRecorder.start() has returned: 3.1 measures from that call
                            // to the first capture of a recording request, so no recording request may exist yet.
                            session.setRepeatingRequest(host.recordRequest(camera, c, recorder.surface, false, iteration), host.captureCallback, handler)
                            telemetry.event(sessionId, "record_configured", values + streams)
                        } catch (e: Exception) {
                            telemetry.event(sessionId, "record_failed", values + mapOf("reason" to e.toString()))
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        telemetry.event(sessionId, "record_configure_failed", values + streams + mapOf("reason" to "rejected"))
                    }
                }, handler)
            } catch (e: Exception) {
                release()
                telemetry.event(sessionId, "record_failed", values + mapOf("reason" to e.toString()))
            }
        }
    }

    /**
     * Start the recorder and only then submit the recording requests. `record_start_call` is recorded in the
     * instruction before MediaRecorder.start(), which is the start point METRICS.md 3.1 asks for; the end point
     * is the first `capture_started` carrying this cycle's tag.
     */
    fun start() {
        handler.post {
            val camera = host.camera
            val session = host.session
            val recorder = this.recorder
            val values = mapOf("iteration" to iteration)
            if (camera == null || session == null || recorder == null || !host.cameraActive) {
                telemetry.event(sessionId, "record_failed", values + mapOf("reason" to "not_prepared"))
                return@post
            }
            try {
                val c = host.characteristics ?: error("Camera characteristics unavailable")
                telemetry.event(sessionId, "record_start_call", values)
                recorder.start()
                started = true
                telemetry.event(sessionId, "record_started", values)
                session.setRepeatingRequest(host.recordRequest(camera, c, recorder.surface, true, iteration), host.captureCallback, handler)
            } catch (e: Exception) {
                telemetry.event(sessionId, "record_failed", values + mapOf("reason" to e.toString()))
            }
        }
    }

    /**
     * Stop the recording requests, then the recorder. The order is the one AOSP `RecordingTest.stopRecording`
     * uses and it is part of the measured condition: `record_stop_call` to `record_stopped` is 3.6, and nothing
     * but MediaRecorder.stop() happens in between.
     */
    fun stop() {
        handler.post {
            val recorder = this.recorder
            val values = mapOf("iteration" to iteration)
            if (recorder == null || !started) {
                telemetry.event(sessionId, "record_failed", values + mapOf("reason" to "not_started"))
                release()
                return@post
            }
            runCatching { host.session?.stopRepeating() }
            telemetry.event(sessionId, "record_stop_call", values)
            val stopped = runCatching { recorder.stop() }
            if (stopped.isSuccess) telemetry.event(sessionId, "record_stopped", values)
            else telemetry.event(sessionId, "record_failed",
                values + mapOf("reason" to "stop_failed", "message" to stopped.exceptionOrNull().toString()))
            release()
        }
    }

    /** Release whatever a failed cycle still holds. Reports nothing: the runner already knows why it failed. */
    fun abort() {
        handler.post { runCatching { host.session?.stopRepeating() }; release() }
    }

    private fun videoEncoder(codec: String): Int? = when (codec.lowercase(java.util.Locale.US)) {
        "h264" -> MediaRecorder.VideoEncoder.H264
        "h265", "hevc" -> MediaRecorder.VideoEncoder.HEVC
        else -> null
    }

    /** Idempotent: every failure path and the camera close call it, and the measurement keeps no video file. */
    fun release() {
        val recorder = this.recorder
        this.recorder = null
        started = false
        recording = false
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        file?.delete()
        file = null
    }
}
