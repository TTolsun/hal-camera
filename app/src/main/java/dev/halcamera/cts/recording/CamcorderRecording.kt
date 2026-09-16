package dev.halcamera.cts.recording

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.util.Range
import android.view.Surface
import dev.halcamera.cts.Camera2Ops
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One MediaRecorder recording the way RecordingTest does it: prepareRecording, a TEMPLATE_RECORD session on
 * the preview and recording surfaces, the first capture start, MediaRecorder.start, [body], then stopRecording
 * (close the session, wait for onClosed, stop the recorder). Returns what MediaExtractor reads back from the
 * file together with the number of completed results; the caller deletes the file when it is done with it.
 *
 * [body] runs between start and stop with the live session and the repeating request, so a case can sleep for
 * its duration or capture a still into the same session. Every failure propagates; the session and the
 * recording surface are released on the way out.
 */
class CamcorderRecording(
    private val ops: Camera2Ops,
    private val camera: CameraDevice,
    private val recorder: MediaRecorder,
    private val camcorder: CamcorderProfile,
    private val file: File
) {
    val fpsRange: Range<Int> get() = Range(camcorder.videoFrameRate, camcorder.videoFrameRate)

    fun record(previewSurface: Surface, extraOutputs: List<Surface> = emptyList(), body: (session: CameraCaptureSession, repeating: CaptureRequest) -> Unit): BasicRecordingRules.Recording {
        file.delete()
        recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setProfile(camcorder)
        recorder.setOutputFile(file.absolutePath)
        recorder.prepare()
        val recordingSurface = recorder.surface ?: error("Recording surface must be non-null!")
        check(previewSurface.isValid && recordingSurface.isValid) { "Both preview and recording surfaces should be valid" }

        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            addTarget(recordingSurface)
            addTarget(previewSurface)
        }.build()
        val frames = AtomicLong(0)
        var session: Camera2Ops.Session? = null
        try {
            session = ops.configure(camera, listOf(previewSurface, recordingSurface) + extraOutputs, request)
            val firstStart = CountDownLatch(1)
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(s: CameraCaptureSession, r: CaptureRequest, timestamp: Long, frameNumber: Long) { firstStart.countDown() }
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) { frames.incrementAndGet() }
            }
            session.session.setRepeatingRequest(request, callback, ops.handler)
            // Wait for the first capture start before starting mediaRecorder.
            check(firstStart.await(Camera2Ops.CAPTURE_RESULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Timeout waiting for the first capture start" }
            recorder.start()
            body(session.session, request)

            // stopRecording: stop streaming and wait for the session to close, then stop the recorder.
            check(session.close()) { "Timeout waiting for the session to close" }
            session = null
            recorder.stop()
            recorder.reset()
        } finally {
            // On an error path the session is still open: close it so the next recording starts clean.
            session?.close()
            recordingSurface.release()
        }
        return RecordingReader.read(file, frames.get())
    }
}
