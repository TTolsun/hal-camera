package dev.halcamera.cts.onoff

import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureResult
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
                val index = (i - 1) * 2 + kind.ordinal
                listener.onProgress(cameraId, "${kind.name.lowercase()} $preview", index, total)
                val cycle = if (kind == Kind.STANDARD) standardCycle(cameraId, preview, realtime) else fastCycle(cameraId, preview, realtime)
                cycles += cycle
                val (verdict, details) = FastOnOffRules.judge(cycle)
                steps += step(cameraId, FastOnOffRules.stepId(kind, i), verdict, details)
            }
        }
        val (verdict, details) = FastOnOffRules.compare(cycles)
        steps += step(cameraId, "compare", verdict, details)
        return CameraCaseResult(cameraId, steps)
    }

    private fun standardCycle(cameraId: String, preview: Dim, realtime: Boolean): Cycle =
        openAndFirstFrame(cameraId, preview, realtime, Cycle(Kind.STANDARD))

    /** open → close at once → the standard cycle. The immediate pair is recorded in the FAST fields. */
    private fun fastCycle(cameraId: String, preview: Dim, realtime: Boolean): Cycle {
        var cycle = Cycle(Kind.FAST)
        try {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val camera = ops.open(cameraId)
            val t1 = SystemClock.elapsedRealtimeNanos()
            val closedInTime = camera.close()
            val t2 = SystemClock.elapsedRealtimeNanos()
            cycle = cycle.copy(fastOpenMs = ms(t0, t1), fastCloseMs = ms(t1, t2), fastClosedInTime = closedInTime)
            camera.error?.let { return cycle.copy(error = it) }
        } catch (e: Exception) {
            Log.w(TAG, "camera $cameraId fast open/close failed", e)
            return cycle.copy(error = describe(e))
        }
        return openAndFirstFrame(cameraId, preview, realtime, cycle)
    }

    /** open → preview session → first frame → close, filling [base]'s standard fields. */
    private fun openAndFirstFrame(cameraId: String, preview: Dim, realtime: Boolean, base: Cycle): Cycle {
        var cycle = base
        var camera: Camera2Ops.OpenedCamera? = null
        var session: Camera2Ops.Session? = null
        try {
            val t0 = SystemClock.elapsedRealtimeNanos()
            camera = ops.open(cameraId)
            val opened = SystemClock.elapsedRealtimeNanos()
            cycle = cycle.copy(openMs = ms(t0, opened))
            val surface = env.previewHost.acquirePreview(preview, Camera2Ops.WAIT_FOR_SURFACE_CHANGE_TIMEOUT_MS)
                ?: error("wait for surface change to $preview timed out")
            val t1 = SystemClock.elapsedRealtimeNanos()
            session = ops.configure(camera.device, listOf(surface))
            val t2 = SystemClock.elapsedRealtimeNanos()
            cycle = cycle.copy(configureMs = ms(t1, t2))
            val request = camera.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }.build()
            val frame = ops.startRepeatingAndAwaitFirstFrame(session.session, request)
            cycle = cycle.copy(
                firstFrameMs = ms(t2, frame.completedAtNs),
                frame = FastOnOffRules.FrameMeta(
                    sensorTimestampNs = frame.result[CaptureResult.SENSOR_TIMESTAMP],
                    frameNumber = frame.result.frameNumber,
                    realtimeSource = realtime,
                    openedAtNs = opened,
                    completedAtNs = frame.completedAtNs
                )
            )
            // A finally block cannot change a value already returned, so the fault is folded in before it runs.
            camera.error?.let { cycle = cycle.copy(error = it) }
        } catch (e: Exception) {
            Log.w(TAG, "camera $cameraId ${base.kind} cycle failed", e)
            cycle = cycle.copy(error = describe(e))
        } finally {
            session?.close()
            if (camera != null) {
                val t3 = SystemClock.elapsedRealtimeNanos()
                val inTime = camera.close()
                cycle = cycle.copy(closeMs = ms(t3, SystemClock.elapsedRealtimeNanos()), closedInTime = inTime)
            }
        }
        return cycle
    }

    private fun ms(fromNs: Long, toNs: Long): Double = (toNs - fromNs) / 1e6

    companion object {
        private const val TAG = "FastOnOffRunner"
    }
}
