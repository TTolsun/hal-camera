package dev.halcamera.cts.recording

import android.hardware.camera2.CameraDevice
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import dev.halcamera.cts.Camera2Ops
import dev.halcamera.cts.CameraCaseResult
import dev.halcamera.cts.CameraCaseRunner
import dev.halcamera.cts.CameraFacts
import dev.halcamera.cts.CaseEnvironment
import dev.halcamera.cts.Dim
import dev.halcamera.cts.StepResult
import dev.halcamera.cts.Verdict
import java.io.File

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
        val profiles = CamcorderProfiles.read(numericId)
        val info = CameraFacts.recordingInfo(cameraId, chars, profiles.keys, env.windowWidth, env.windowHeight)
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

    // ---- one profile: prepareRecording, updatePreviewSurfaceWithVideo, startRecording, stopRecording, validateRecording ----

    private fun recordAndValidate(
        camera: CameraDevice, recorder: MediaRecorder, info: BasicRecordingRules.CameraInfo,
        profile: BasicRecordingRules.Profile, preview: Dim
    ): Pair<Verdict, List<String>> {
        val file = File(env.outputDir, "test_video.mp4")
        val camcorder = CamcorderProfiles.get(info.cameraId.toInt(), profile.quality)
        val previewSurface = env.previewHost.acquirePreview(preview, Camera2Ops.WAIT_FOR_SURFACE_CHANGE_TIMEOUT_MS)
            ?: error("wait for surface change to $preview timed out")
        val recording = CamcorderRecording(ops, camera, recorder, camcorder, file).record(previewSurface) {
            SystemClock.sleep(BasicRecordingRules.RECORDING_DURATION_MS)
        }
        val failures = BasicRecordingRules.validate(info.cameraId, info.isLegacy, profile, recording)
        file.delete()
        val summary = BasicRecordingRules.summary(profile, recording)
        return if (failures.isEmpty()) Verdict.PASS to listOf(summary) else Verdict.FAIL to (listOf(summary) + failures)
    }

    companion object {
        private const val TAG = "BasicRecordingRunner"
    }
}
