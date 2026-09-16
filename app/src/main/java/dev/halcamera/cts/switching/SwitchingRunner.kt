package dev.halcamera.cts.switching

import android.hardware.camera2.CameraCharacteristics
import android.media.MediaRecorder
import android.util.Log
import dev.halcamera.cts.Camera2Ops
import dev.halcamera.cts.CameraCaseResult
import dev.halcamera.cts.CameraCaseRunner
import dev.halcamera.cts.CameraFacts
import dev.halcamera.cts.CaseEnvironment
import dev.halcamera.cts.Dim
import dev.halcamera.cts.StepResult
import dev.halcamera.cts.Verdict
import dev.halcamera.cts.recording.CamcorderProfiles
import dev.halcamera.cts.recording.CamcorderRecording
import java.io.File

/**
 * Performs the Switching case: every eligible camera is visited in turn for [rounds] rounds — each visit the
 * shared open → preview → first frame → close pass — and then each camera records once for
 * [SwitchingRules.RECORDING_DURATION_MS] at its largest CamcorderProfile. Cameras without colour output or a
 * preview size are reported as SKIP and left out of the rotation.
 */
class SwitchingRunner(env: CaseEnvironment, private val rounds: Int = SwitchingRules.DEFAULT_ROUNDS) :
    CameraCaseRunner(env, SwitchingRules.SOURCE, "cts-switching") {

    private class Target(val id: String, val chars: CameraCharacteristics, val preview: Dim)

    override fun runCameras(ids: List<String>): List<CameraCaseResult> {
        val steps = LinkedHashMap<String, ArrayList<StepResult>>()
        ids.forEach { steps[it] = ArrayList() }
        val targets = ArrayList<Target>()
        ids.forEachIndexed { index, id ->
            listener.onCameraStarted(id, index, ids.size)
            val chars = try { characteristics(id) } catch (e: Exception) {
                steps[id]!! += step(id, "camera", Verdict.FAIL, listOf("characteristics unreadable: ${e.message}"))
                return@forEachIndexed
            }
            if (!CameraFacts.hasColorOutput(chars)) {
                steps[id]!! += step(id, "camera", Verdict.SKIP, listOf("Camera $id does not support color outputs, skipping"))
                return@forEachIndexed
            }
            val preview = CameraFacts.orderedPreviewSizes(chars, env.windowWidth, env.windowHeight).firstOrNull()
            if (preview == null) {
                steps[id]!! += step(id, "camera", Verdict.SKIP, listOf("Camera $id has no SurfaceHolder preview size within the preview bound"))
                return@forEachIndexed
            }
            targets += Target(id, chars, preview)
        }

        val byId = targets.associateBy { it.id }
        val visits = SwitchingRules.order(targets.map { it.id }, rounds)
        for ((index, visit) in visits.withIndex()) {
            if (cancelled.get()) break
            listener.onProgress(visit.cameraId, "round ${visit.round} 전환", index, visits.size)
            val pass = openCycle(visit.cameraId, byId.getValue(visit.cameraId).preview)
            val (verdict, details) = SwitchingRules.judgeVisit(pass.cycle)
            steps[visit.cameraId]!! += step(visit.cameraId, SwitchingRules.visitStepId(visit.round), verdict, details)
        }
        for ((index, target) in targets.withIndex()) {
            if (cancelled.get()) break
            listener.onProgress(target.id, "녹화", index, targets.size)
            steps[target.id]!! += record(target)
        }
        return ids.map { CameraCaseResult(it, steps.getValue(it)) }
    }

    /** One short recording after the rotation, at the largest CamcorderProfile the camera has. */
    private fun record(target: Target): StepResult {
        val numeric = target.id.toIntOrNull()
        val profiles = numeric?.let { CamcorderProfiles.read(it) } ?: emptyMap()
        val profile = SwitchingRules.recordingProfile(profiles)
        SwitchingRules.recordingSkipReason(target.id, numeric != null, profile)?.let {
            return step(target.id, SwitchingRules.RECORD_STEP_ID, Verdict.SKIP, listOf(it))
        }
        // The preview follows the video size, as testBasicRecording's getPreviewSizesForVideo does, so the HAL is not asked for a preview larger than the video.
        val preview = CameraFacts.orderedPreviewSizes(target.chars, env.windowWidth, env.windowHeight)
            .firstOrNull { it.width <= profile!!.size.width && it.height <= profile.size.height } ?: target.preview
        val file = File(env.outputDir, "test_video.mp4")
        var camera: Camera2Ops.OpenedCamera? = null
        var recorder: MediaRecorder? = null
        try {
            camera = ops.open(target.id)
            recorder = MediaRecorder()
            val surface = env.previewHost.acquirePreview(preview, Camera2Ops.WAIT_FOR_SURFACE_CHANGE_TIMEOUT_MS)
                ?: error("wait for surface change to $preview timed out")
            val recording = CamcorderRecording(ops, camera.device, recorder, CamcorderProfiles.get(numeric!!, profile!!.quality), file)
                .record(surface) { _, _ -> sleepUnlessCancelled(SwitchingRules.RECORDING_DURATION_MS) }
            val failures = SwitchingRules.validateRecording(target.id, profile, recording)
            val details = listOf(SwitchingRules.recordingSummary(profile, recording)) + failures
            return step(target.id, SwitchingRules.RECORD_STEP_ID, if (failures.isEmpty()) Verdict.PASS else Verdict.FAIL, details)
        } catch (e: Exception) {
            Log.w(TAG, "camera ${target.id} recording after switching failed", e)
            return step(target.id, SwitchingRules.RECORD_STEP_ID, Verdict.FAIL, listOf(describe(e)))
        } finally {
            file.delete()
            recorder?.release()
            camera?.close()
        }
    }

    companion object {
        private const val TAG = "SwitchingRunner"
    }
}
