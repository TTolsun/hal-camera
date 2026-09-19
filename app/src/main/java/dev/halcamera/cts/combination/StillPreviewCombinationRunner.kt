package dev.halcamera.cts.combination

import android.hardware.camera2.CameraDevice
import android.os.SystemClock
import android.util.Log
import dev.halcamera.cts.Camera2Ops
import dev.halcamera.cts.CameraCaseResult
import dev.halcamera.cts.CameraCaseRunner
import dev.halcamera.cts.CameraFacts
import dev.halcamera.cts.CaseEnvironment
import dev.halcamera.cts.JpegReader
import dev.halcamera.cts.StepResult
import dev.halcamera.cts.Verdict
import dev.halcamera.cts.combination.StillPreviewCombinationRules.Capture
import dev.halcamera.cts.combination.StillPreviewCombinationRules.Combo

/**
 * Performs the StillPreviewCombination case: the camera is opened once and, for every planned combination, a
 * session with the SurfaceView preview and a JPEG reader is configured, the preview streams to its first
 * frame, one TEMPLATE_STILL_CAPTURE request goes to the reader, and [StillPreviewCombinationRules] judges the
 * image. A device fault ends the camera's remaining combinations with a FAIL row, as the device is gone.
 */
class StillPreviewCombinationRunner(env: CaseEnvironment) : CameraCaseRunner(env, StillPreviewCombinationRules.SOURCE, "cts-still-preview") {

    override fun runCamera(cameraId: String): CameraCaseResult {
        val steps = ArrayList<StepResult>()
        val chars = try { characteristics(cameraId) } catch (e: Exception) {
            steps += step(cameraId, "camera", Verdict.FAIL, listOf("characteristics unreadable: ${e.message}"))
            return CameraCaseResult(cameraId, steps)
        }
        val stills = CameraFacts.orderedStillSizes(chars)
        val previews = CameraFacts.orderedPreviewSizes(chars, env.windowWidth, env.windowHeight)
        StillPreviewCombinationRules.cameraSkipReason(cameraId, CameraFacts.hasColorOutput(chars), stills, previews)?.let {
            steps += step(cameraId, "camera", Verdict.SKIP, listOf(it))
            return CameraCaseResult(cameraId, steps)
        }
        val combos = StillPreviewCombinationRules.plan(stills, previews)
        var camera: Camera2Ops.OpenedCamera? = null
        try {
            camera = ops.open(cameraId)
            for ((index, combo) in combos.withIndex()) {
                if (cancelled.get()) break
                listener.onProgress(cameraId, "still ${combo.still} · preview ${combo.preview}", index, combos.size)
                val capture = captureCombo(camera, combo)
                val (verdict, details) = StillPreviewCombinationRules.judge(combo, capture)
                steps += step(cameraId, StillPreviewCombinationRules.stepId(combo), verdict, details)
                camera.error?.let {
                    steps += step(cameraId, "camera", Verdict.FAIL, listOf(it, "${combos.size - index - 1} combinations not attempted"))
                    break
                }
            }
        } catch (e: Exception) {
            steps += step(cameraId, "camera", Verdict.FAIL, listOf(describe(e)))
        } finally {
            camera?.close()
        }
        return CameraCaseResult(cameraId, steps)
    }

    /** prepareStillCaptureAndStartPreview, one still into the reader, then the session and reader are closed. */
    private fun captureCombo(camera: Camera2Ops.OpenedCamera, combo: Combo): Capture {
        var capture = Capture()
        var reader: JpegReader? = null
        var session: Camera2Ops.Session? = null
        try {
            val previewSurface = env.previewHost.acquirePreview(combo.preview, Camera2Ops.WAIT_FOR_SURFACE_CHANGE_TIMEOUT_MS)
                ?: error("wait for surface change to ${combo.preview} timed out")
            reader = JpegReader(combo.still.width, combo.still.height, ops.handler)
            val t0 = SystemClock.elapsedRealtimeNanos()
            session = ops.configure(camera.device, listOf(previewSurface, reader.surface))
            val t1 = SystemClock.elapsedRealtimeNanos()
            capture = capture.copy(configureMs = ms(t0, t1))
            val previewRequest = camera.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(previewSurface) }.build()
            val first = ops.startRepeatingAndAwaitFirstFrame(session.session, previewRequest)
            capture = capture.copy(firstPreviewMs = ms(t1, first.completedAtNs))
            val stillRequest = camera.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(reader.surface) }.build()
            val still = ops.captureStill(session.session, stillRequest, reader)
            val image = still.image
            try {
                capture = capture.copy(
                    resultReceived = still.resultReceived,
                    captureMs = still.imageAtNs?.let { ms(still.requestedAtNs, it) },
                    image = image?.let { JpegReader.describe(it) }
                )
            } finally {
                image?.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "camera ${camera.id} ${combo.still} with preview ${combo.preview} failed", e)
            capture = capture.copy(error = describe(e))
        } finally {
            session?.close()
            reader?.close()
        }
        return capture
    }

    companion object {
        private const val TAG = "StillPreviewCombinationRunner"
    }
}
