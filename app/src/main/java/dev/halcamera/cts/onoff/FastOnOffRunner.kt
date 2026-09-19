package dev.halcamera.cts.onoff

import android.hardware.camera2.CaptureResult
import android.os.SystemClock
import android.util.Log
import dev.halcamera.cts.CameraCaseResult
import dev.halcamera.cts.CameraCaseRunner
import dev.halcamera.cts.CameraFacts
import dev.halcamera.cts.CaseEnvironment
import dev.halcamera.cts.Dim
import dev.halcamera.cts.StepResult
import dev.halcamera.cts.Verdict
import dev.halcamera.cts.onoff.FastOnOffRules.Cycle
import dev.halcamera.cts.onoff.FastOnOffRules.Kind

/**
 * Performs the FastOnOff case: per camera, [FastOnOffRules.ITERATIONS] standard open/close cycles interleaved
 * with as many fast ones, each measured and judged by [FastOnOffRules]. The preview is the largest SurfaceHolder
 * size within the CTS preview bound, the same first size testBasicRecording would use.
 */
class FastOnOffRunner(env: CaseEnvironment) : CameraCaseRunner(env, FastOnOffRules.SOURCE, "cts-fast-on-off") {

    override fun runCamera(cameraId: String): CameraCaseResult {
        val steps = ArrayList<StepResult>()
        val chars = try { characteristics(cameraId) } catch (e: Exception) {
            steps += step(cameraId, "camera", Verdict.FAIL, listOf("characteristics unreadable: ${e.message}"))
            return CameraCaseResult(cameraId, steps)
        }
        if (!CameraFacts.hasColorOutput(chars)) {
            steps += step(cameraId, "camera", Verdict.SKIP, listOf("Camera $cameraId does not support color outputs, skipping"))
            return CameraCaseResult(cameraId, steps)
        }
        val preview = CameraFacts.orderedPreviewSizes(chars, env.windowWidth, env.windowHeight).firstOrNull()
        if (preview == null) {
            steps += step(cameraId, "camera", Verdict.SKIP, listOf("Camera $cameraId has no SurfaceHolder preview size within the preview bound"))
            return CameraCaseResult(cameraId, steps)
        }
        val realtime = CameraFacts.realtimeTimestamps(chars)
        val cycles = ArrayList<Cycle>()
        val total = FastOnOffRules.ITERATIONS * 2
        for (i in 1..FastOnOffRules.ITERATIONS) {
            for (kind in Kind.values()) {
                if (cancelled.get()) return CameraCaseResult(cameraId, steps)
                listener.onProgress(cameraId, "${kind.name.lowercase()} $preview", (i - 1) * 2 + kind.ordinal, total)
                val cycle = if (kind == Kind.STANDARD) withFirstFrame(cameraId, preview, realtime, Cycle(Kind.STANDARD)) else fastCycle(cameraId, preview, realtime)
                cycles += cycle
                val (verdict, details) = FastOnOffRules.judge(cycle)
                steps += step(cameraId, FastOnOffRules.stepId(kind, i), verdict, details)
            }
        }
        val (verdict, details) = FastOnOffRules.compare(cycles)
        steps += step(cameraId, "compare", verdict, details)
        return CameraCaseResult(cameraId, steps)
    }

    /** open → close at once, recorded in the FAST fields, then the standard pass as the reopen. */
    private fun fastCycle(cameraId: String, preview: Dim, realtime: Boolean): Cycle {
        var cycle = Cycle(Kind.FAST)
        try {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val camera = ops.open(cameraId)
            val t1 = SystemClock.elapsedRealtimeNanos()
            val closedInTime = camera.close()
            val t2 = SystemClock.elapsedRealtimeNanos()
            cycle = cycle.copy(fastOpenMs = ms(t0, t1), fastCloseMs = ms(t1, t2), fastClosedInTime = closedInTime)
            camera.error?.let { return cycle.copy(fastError = it) }
        } catch (e: Exception) {
            Log.w(TAG, "camera $cameraId fast open/close failed", e)
            return cycle.copy(fastError = describe(e))
        }
        return withFirstFrame(cameraId, preview, realtime, cycle)
    }

    /** The shared open pass, plus the first result's metadata for the rules. */
    private fun withFirstFrame(cameraId: String, preview: Dim, realtime: Boolean, base: Cycle): Cycle {
        val pass = openCycle(cameraId, preview)
        val frame = pass.frame?.let {
            FastOnOffRules.FrameMeta(
                sensorTimestampNs = it.result[CaptureResult.SENSOR_TIMESTAMP],
                frameNumber = it.result.frameNumber,
                realtimeSource = realtime,
                openedAtNs = pass.openedAtNs ?: 0L,
                completedAtNs = it.completedAtNs
            )
        }
        return base.copy(pass = pass.cycle, frame = frame)
    }

    companion object {
        private const val TAG = "FastOnOffRunner"
    }
}
