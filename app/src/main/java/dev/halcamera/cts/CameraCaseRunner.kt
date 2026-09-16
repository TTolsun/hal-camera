package dev.halcamera.cts

import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The shape every case runner shares: walk the public camera ids, give each one a [CameraCaseResult], deliver
 * every step as it is decided, and end with one [CaseReport]. A failure that escapes [runCamera] becomes a FAIL
 * step on that camera; a failure outside it becomes a FAIL row of its own, so the report is never empty.
 *
 * [cancel] is honoured between cameras and wherever a subclass checks [cancelled] or waits with
 * [sleepUnlessCancelled]; the camera in progress is closed through the subclass's own finally blocks.
 */
abstract class CameraCaseRunner(protected val env: CaseEnvironment, private val source: String, threadName: String) : CtsRunner {
    protected val cancelled = AtomicBoolean(false)
    private val cancelLatch = CountDownLatch(1)
    protected val ops = Camera2Ops(env.manager, threadName)
    protected val listener: CtsRunner.Listener get() = env.listener

    override fun cancel() { cancelled.set(true); cancelLatch.countDown() }

    override fun run() {
        val cameras = ArrayList<CameraCaseResult>()
        try {
            cameras += runCameras(env.manager.cameraIdList.toList())
        } catch (e: Exception) {
            Log.w(source, "run aborted", e)
            cameras += CameraCaseResult("-", listOf(StepResult("run", Verdict.FAIL, listOf(describe(e)))))
        } finally {
            ops.quit()
        }
        listener.onFinished(CaseReport(source, cameras, cancelled.get()))
    }

    /** One result per camera in [ids] order. Cases that interleave cameras override this. */
    protected open fun runCameras(ids: List<String>): List<CameraCaseResult> {
        val out = ArrayList<CameraCaseResult>()
        ids.forEachIndexed { index, id ->
            if (cancelled.get()) return@forEachIndexed
            listener.onCameraStarted(id, index, ids.size)
            out += runCamera(id)
        }
        return out
    }

    protected abstract fun runCamera(cameraId: String): CameraCaseResult

    /** Records the step with the listener as it is decided, so the screen fills in while the run continues. */
    protected fun step(cameraId: String, id: String, verdict: Verdict, details: List<String>): StepResult =
        StepResult(id, verdict, details).also { listener.onStep(cameraId, it) }

    /** Waits [ms] unless the run is cancelled first; returns true when the full wait elapsed. */
    protected fun sleepUnlessCancelled(ms: Long): Boolean = !cancelLatch.await(ms, TimeUnit.MILLISECONDS)

    protected fun characteristics(cameraId: String): CameraCharacteristics = env.manager.getCameraCharacteristics(cameraId)

    protected fun describe(e: Exception): String = "${e.javaClass.simpleName}: ${e.message}"
}
