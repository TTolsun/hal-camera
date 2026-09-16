package dev.halcamera.cts.sizes

import dev.halcamera.cts.CameraCaseResult
import dev.halcamera.cts.CameraCaseRunner
import dev.halcamera.cts.CameraFacts
import dev.halcamera.cts.CaseEnvironment
import dev.halcamera.cts.StepResult
import dev.halcamera.cts.Verdict

/** Performs the AllSizeOnOff case: the shared open → preview → first frame → close pass once per SurfaceHolder size, per camera. */
class AllSizeOnOffRunner(env: CaseEnvironment) : CameraCaseRunner(env, AllSizeOnOffRules.SOURCE, "cts-all-size") {

    override fun runCamera(cameraId: String): CameraCaseResult {
        val steps = ArrayList<StepResult>()
        val chars = try { characteristics(cameraId) } catch (e: Exception) {
            steps += step(cameraId, "camera", Verdict.FAIL, listOf("characteristics unreadable: ${e.message}"))
            return CameraCaseResult(cameraId, steps)
        }
        val sizes = AllSizeOnOffRules.plan(CameraFacts.previewSizes(chars))
        AllSizeOnOffRules.cameraSkipReason(cameraId, CameraFacts.hasColorOutput(chars), sizes)?.let {
            steps += step(cameraId, "camera", Verdict.SKIP, listOf(it))
            return CameraCaseResult(cameraId, steps)
        }
        for ((index, size) in sizes.withIndex()) {
            if (cancelled.get()) break
            listener.onProgress(cameraId, size.toString(), index, sizes.size)
            val (verdict, details) = AllSizeOnOffRules.judge(openCycle(cameraId, size).cycle)
            steps += step(cameraId, AllSizeOnOffRules.stepId(size), verdict, details)
        }
        return CameraCaseResult(cameraId, steps)
    }
}
