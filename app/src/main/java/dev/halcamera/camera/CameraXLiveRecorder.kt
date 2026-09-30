package dev.halcamera.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Range
import android.widget.Toast
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import dev.halcamera.telemetry.Telemetry
import java.io.File
import java.util.concurrent.Executor

/**
 * The LIVE video recording of CameraXEngine, the counterpart of Camera2LiveRecorder: a temp MP4, then the same
 * MediaLibrary save to DCIM/HALCamera.
 *
 * Like Camera2 it records from a session of preview and encoder only. Starting swaps the analysis and still use
 * cases for a VideoCapture, which rebuilds the session; the finished recording swaps them back. Binding all four
 * at once would leave the stream combination to CameraX's stream sharing, which Camera2 never uses.
 *
 * The request matches Camera2's where Recorder lets it: at most FHD (the largest size up to 1920×1080), 30 fps and
 * 10 Mbps. Codec and audio format come from the device's encoder profiles; Camera2 fixes H.264 and 44.1 kHz AAC.
 *
 * Main thread only.
 */
internal class CameraXLiveRecorder(
    private val context: Context,
    private val mainExecutor: Executor,
    private val telemetry: Telemetry,
    private val sessionId: String,
    private val library: MediaLibrary,
    private val mediaIo: Executor,
    private val host: Host,
) {
    interface Host {
        val active: Boolean
        val settings: LiveVideo?
        val cameraInfo: androidx.camera.core.CameraInfo?
        val stillInFlight: Boolean
        /** Surface.ROTATION_* of the display now; the recording keeps it as its orientation hint. */
        val displayRotation: Int
        /** Swaps the analysis and still use cases for [video]; throws when the camera rejects it. */
        fun bindRecording(video: VideoCapture<Recorder>)
        /** The recording has ended: put the analysis and still use cases back. */
        fun unbindRecording(video: VideoCapture<Recorder>)
        fun recordingState(recording: Boolean)
        /**
         * The first frame reached the file. CameraX reconfigures the repeating request when the video surface goes
         * live, after [bindRecording] returned and after Start, and a FocusMeteringAction sent before then is lost
         * (AF lock read AF Idle for a whole recording on the S25+), so the held metering goes out again here.
         */
        fun streamingStarted()
        /** Why this recording carries no photo use case (#175), or null when it does. */
        val snapshotUnavailable: String?
        /** A short message that leaves the recording state alone. */
        fun notice(text: String)
        fun status(message: String, ok: Boolean)
        fun report(message: String, ok: Boolean)
    }

    private var video: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var done: ((Result<Uri>) -> Unit)? = null
    private var streaming = false
    private var afterClose: (() -> Unit)? = null
    var busy = false
        private set
    /** The Start event has arrived and no stop was asked for yet: the recording a photo may be taken from. */
    var live = false
        private set
    var stopping = false
        private set

    fun start(audio: Boolean, started: () -> Unit, done: ((Result<Uri>) -> Unit)?) {
        if (!host.active || host.stillInFlight || busy) {
            done?.invoke(Result.failure(IllegalStateException("Camera is not ready to record"))); return
        }
        busy = true
        this.done = done
        host.report("녹화를 준비하고 있습니다…", false)
        var file: File? = null
        try {
            val settings = host.settings
            val quality = settings?.let { requested ->
                require(requested.codec == "Auto") { "CameraX chooses the recording codec automatically" }
                val info = requireNotNull(host.cameraInfo)
                QualitySelector.getSupportedQualities(info).firstOrNull {
                    QualitySelector.getResolution(info, it) == requested.size.androidSize()
                } ?: error("CameraX does not support recording size ${requested.size}")
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(if (quality == null) QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)) else QualitySelector.from(quality))
                .setTargetVideoEncodingBitRate(settings?.bitrate ?: 10_000_000)
                .build()
            val fps = settings?.fps ?: 30
            val useCase = VideoCapture.Builder(recorder).setTargetFrameRate(Range(fps, fps)).setTargetRotation(host.displayRotation).build()
            video = useCase
            host.bindRecording(useCase)
            if (settings != null) require(useCase.resolutionInfo?.resolution == settings.size.androidSize()) {
                "CameraX could not configure recording size ${settings.size}"
            }
            val output = File.createTempFile("hal_recording_", ".mp4", context.cacheDir).also { file = it }
            val pending = recorder.prepareRecording(context, FileOutputOptions.Builder(output).build())
            val withAudio = if (!audio) pending else {
                // MainActivity asks for the permission before recording; a missing grant is a failure, not silence.
                check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "RECORD_AUDIO not granted" }
                pending.withAudioEnabled()
            }
            recording = withAudio.start(mainExecutor) { event -> onEvent(event, output, audio, started) }
        } catch (e: Exception) {
            file?.delete()
            telemetry.event(sessionId, "camera_error", mapOf("message" to e.toString()))
            end()
            this.done?.invoke(Result.failure(e)); this.done = null
            host.report("녹화 준비 실패: ${e.message} · 다시 시도할 수 있습니다", true)
        }
    }

    fun stop() {
        if (!busy) return
        stopping = true
        host.report("녹화를 저장하고 있습니다…", false)
        recording?.stop()
    }

    /**
     * The camera is closing. Stopping still finalizes the file, and the Finalize event saves it as usual; [after]
     * runs once that save is queued, so the engine can shut down the executor it runs on.
     */
    fun close(after: () -> Unit) {
        val active = recording ?: return after()
        afterClose = after
        active.stop()
    }

    private fun onEvent(event: VideoRecordEvent, file: File, audio: Boolean, started: () -> Unit) {
        when (event) {
            is VideoRecordEvent.Start -> {
                telemetry.event(sessionId, "recording_started", mapOf("size" to video?.resolutionInfo?.resolution?.toString(), "audio" to audio,
                    "api" to "Recorder.prepareRecording"))
                live = true
                if (host.active) { host.recordingState(true); started() }
                host.report(if (audio) "REC · 영상과 소리를 녹화하고 있습니다" else "REC · 영상을 녹화하고 있습니다", false)
                host.snapshotUnavailable?.let { host.notice(it) }
            }
            is VideoRecordEvent.Status -> if (!streaming) { streaming = true; if (host.active) host.streamingStarted() }
            is VideoRecordEvent.Finalize -> {
                // SOURCE_INACTIVE is a stop caused by the camera closing; Recorder still writes a playable file then.
                val playable = event.error == VideoRecordEvent.Finalize.ERROR_NONE || event.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE
                telemetry.event(sessionId, "recording_finalized", mapOf("error" to event.error, "cause" to event.cause?.toString()))
                val callback = done; done = null
                end()
                if (playable && file.length() > 0) save(file, callback)
                else {
                    file.delete()
                    callback?.invoke(Result.failure(event.cause as? Exception
                        ?: IllegalStateException("Recording did not produce a playable video; record for longer before stopping")))
                    Toast.makeText(context.applicationContext, "녹화가 너무 짧거나 실패하여 동영상을 저장하지 못했습니다", Toast.LENGTH_LONG).show()
                }
                afterClose?.let { afterClose = null; it() }
            }
            else -> Unit
        }
    }

    /** Back to the preview session. Runs on every way out of a recording, so [busy] never outlives it. */
    private fun end() {
        val useCase = video
        video = null; recording = null; busy = false; streaming = false; live = false; stopping = false
        host.recordingState(false)
        if (useCase != null && host.active) {
            try { host.unbindRecording(useCase) } catch (e: Exception) { telemetry.event(sessionId, "camera_error", mapOf("message" to e.toString())) }
        }
    }

    private fun save(file: File, done: ((Result<Uri>) -> Unit)?) {
        mediaIo.execute {
            try {
                val uri = library.saveVideo(file)
                telemetry.event(sessionId, "video_saved", mapOf("uri" to uri.toString()))
                mainExecutor.execute {
                    done?.invoke(Result.success(uri))
                    if (host.active) host.status("갤러리에 동영상을 저장했습니다", true)
                    else Toast.makeText(context.applicationContext, "갤러리에 동영상을 저장했습니다", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                mainExecutor.execute {
                    done?.invoke(Result.failure(e))
                    Toast.makeText(context.applicationContext, "동영상 저장 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally { file.delete() }
        }
    }
}
