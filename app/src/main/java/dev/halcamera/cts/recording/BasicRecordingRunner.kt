package dev.halcamera.cts.recording

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.SurfaceHolder
import dev.halcamera.cts.Camera2Ops
import dev.halcamera.cts.CameraCaseResult
import dev.halcamera.cts.CameraCaseRunner
import dev.halcamera.cts.CaseEnvironment
import dev.halcamera.cts.Dim
import dev.halcamera.cts.StepResult
import dev.halcamera.cts.Verdict
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Performs CTS `RecordingTest#testBasicRecording` on the device: for every camera and every CamcorderProfile
 * quality CTS lists, open the camera, record RECORDING_DURATION_MS through MediaRecorder with a preview at
 * the video size, and let [BasicRecordingRules] judge the file. Blocking; call from a worker thread.
 *
 * One deliberate difference from CTS: an assertion there aborts the whole test method, so a failing profile
 * hides the profiles after it. Here every profile gets its own verdict; a camera FAILs if any profile does.
 *
 * Camera and MediaRecorder timeouts are the CameraTestUtils values (3 s each). The preview surface comes from
 * [CtsRunner.PreviewHost], which the activity implements with a SurfaceView the way Camera2SurfaceViewCtsActivity does.
 */
class BasicRecordingRunner(env: CaseEnvironment) : CameraCaseRunner(env, BasicRecordingRules.SOURCE, "cts-recording") {

    override fun runCamera(cameraId: String): CameraCaseResult {
        val steps = ArrayList<StepResult>()
        val chars = try { characteristics(cameraId) } catch (e: Exception) {
            steps += step(cameraId, "camera", Verdict.FAIL, listOf("characteristics unreadable: ${e.message}"))
            return CameraCaseResult(cameraId, steps)
        }
        val numericId = cameraId.toIntOrNull()
        if (numericId == null) {
            steps += step(cameraId, "camera", Verdict.SKIP, listOf("CamcorderProfile needs a numeric camera id"))
            return CameraCaseResult(cameraId, steps)
        }
        val profiles = readProfiles(numericId)
        val info = cameraInfo(cameraId, chars, profiles.keys)
        BasicRecordingRules.cameraSkipReason(info)?.let {
            steps += step(cameraId, "camera", Verdict.SKIP, listOf(it))
            return CameraCaseResult(cameraId, steps)
        }
        val plans = BasicRecordingRules.plan(info, profiles)

        var camera: Camera2Ops.OpenedCamera? = null
        var recorder: MediaRecorder? = null
        try {
            camera = ops.open(cameraId)
            recorder = MediaRecorder()
            val toRecord = plans.count { it.verdict == null }
            var recorded = 0
            for (plan in plans) {
                if (cancelled.get()) break
                val id = BasicRecordingRules.qualityName(plan.quality)
                if (plan.verdict != null) { steps += step(cameraId, id, plan.verdict, plan.details); continue }
                val profile = plan.profile!!
                listener.onProgress(cameraId, "$id 녹화", recorded++, toRecord)
                val result = try {
                    recordAndValidate(camera.device, recorder, info, profile, plan.preview!!)
                } catch (e: Exception) {
                    Log.w(TAG, "camera $cameraId $id failed", e)
                    runCatching { recorder.reset() }
                    Verdict.FAIL to listOf(describe(e))
                }
                steps += step(cameraId, id, result.first, result.second)
            }
            BasicRecordingRules.frameRateFloorFailure(profiles)?.let { steps += step(cameraId, "frame_rate_floor", Verdict.FAIL, listOf(it)) }
        } catch (e: Exception) {
            steps += step(cameraId, "camera", Verdict.FAIL, listOf(describe(e)))
        } finally {
            recorder?.release()
            camera?.close()
        }
        return CameraCaseResult(cameraId, steps)
    }

    // ---- what CTS reads before recording ----

    @Suppress("DEPRECATION")
    private fun readProfiles(cameraId: Int): Map<Int, BasicRecordingRules.Profile> {
        val out = LinkedHashMap<Int, BasicRecordingRules.Profile>()
        (BasicRecordingRules.PROFILE_ORDER + listOf(BasicRecordingRules.QUALITY_QHD, BasicRecordingRules.QUALITY_2K)).forEach { q ->
            if (runCatching { CamcorderProfile.hasProfile(cameraId, q) }.getOrDefault(false)) {
                val p = CamcorderProfile.get(cameraId, q)
                out[q] = BasicRecordingRules.Profile(q, Dim(p.videoFrameWidth, p.videoFrameHeight), p.videoFrameRate)
            }
        }
        return out
    }

    private fun cameraInfo(cameraId: String, chars: CameraCharacteristics, qualities: Set<Int>): BasicRecordingRules.CameraInfo {
        val map = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        val level = chars[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]
        val caps = chars[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.toSet() ?: emptySet()
        val previewBound = BasicRecordingRules.previewSizeBound(env.windowWidth, env.windowHeight)
        val previewSizes = map?.getOutputSizes(SurfaceHolder::class.java).orEmpty().map(::dim)
        val videoSizes = map?.getOutputSizes(MediaRecorder::class.java).orEmpty().map(::dim)
        val privateDurations = HashMap<Dim, Long>()
        map?.getOutputSizes(ImageFormat.PRIVATE).orEmpty().forEach { s ->
            runCatching { map?.getOutputMinFrameDuration(ImageFormat.PRIVATE, s) }.getOrNull()?.let { privateDurations[dim(s)] = it }
        }
        return BasicRecordingRules.CameraInfo(
            cameraId = cameraId,
            hasColorOutput = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE in caps,
            isExternal = level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL,
            isLegacy = level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
            orderedPreviewSizes = BasicRecordingRules.boundedDescending(previewSizes, previewBound),
            supportedVideoSizes = BasicRecordingRules.boundedDescending(videoSizes, BasicRecordingRules.videoSizeBound(qualities)),
            fpsRanges = chars[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES].orEmpty().map { it.lower to it.upper },
            privateMinFrameDurationNs = privateDurations
        )
    }

    // ---- one profile: prepareRecording, updatePreviewSurfaceWithVideo, startRecording, stopRecording, validateRecording ----

    private fun recordAndValidate(
        camera: CameraDevice, recorder: MediaRecorder, info: BasicRecordingRules.CameraInfo,
        profile: BasicRecordingRules.Profile, preview: Dim
    ): Pair<Verdict, List<String>> {
        val file = File(env.outputDir, "test_video.mp4")
        file.delete()
        @Suppress("DEPRECATION")
        val camcorder = CamcorderProfile.get(info.cameraId.toInt(), profile.quality)
        recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setProfile(camcorder)
        recorder.setOutputFile(file.absolutePath)
        recorder.prepare()
        val recordingSurface = recorder.surface ?: error("Recording surface must be non-null!")
        val previewSurface = env.previewHost.acquirePreview(preview, Camera2Ops.WAIT_FOR_SURFACE_CHANGE_TIMEOUT_MS)
            ?: error("wait for surface change to $preview timed out")
        check(previewSurface.isValid && recordingSurface.isValid) { "Both preview and recording surfaces should be valid" }

        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(profile.frameRate, profile.frameRate))
            addTarget(recordingSurface)
            addTarget(previewSurface)
        }.build()
        val frames = AtomicLong(0)
        var session: Camera2Ops.Session? = null
        try {
            session = ops.configure(camera, listOf(previewSurface, recordingSurface), request)
            val firstStart = CountDownLatch(1)
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(s: CameraCaptureSession, r: CaptureRequest, timestamp: Long, frameNumber: Long) { firstStart.countDown() }
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) { frames.incrementAndGet() }
            }
            session.session.setRepeatingRequest(request, callback, ops.handler)
            // Wait for the first capture start before starting mediaRecorder.
            check(firstStart.await(Camera2Ops.CAPTURE_RESULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Timeout waiting for the first capture start" }
            recorder.start()
            SystemClock.sleep(BasicRecordingRules.RECORDING_DURATION_MS)

            // stopRecording: stop streaming and wait for the session to close, then stop the recorder.
            check(session.close()) { "Timeout waiting for the session to close" }
            session = null
            recorder.stop()
            recorder.reset()
        } finally {
            // On an error path the session is still open: close it so the next profile starts clean.
            session?.close()
            recordingSurface.release()
        }

        val recording = RecordingReader.read(file, frames.get())
        val failures = BasicRecordingRules.validate(info.cameraId, info.isLegacy, profile, recording)
        file.delete()
        val summary = BasicRecordingRules.summary(profile, recording)
        return if (failures.isEmpty()) Verdict.PASS to listOf(summary) else Verdict.FAIL to (listOf(summary) + failures)
    }

    private fun dim(size: Size) = Dim(size.width, size.height)

    companion object {
        private const val TAG = "BasicRecordingRunner"
    }
}
