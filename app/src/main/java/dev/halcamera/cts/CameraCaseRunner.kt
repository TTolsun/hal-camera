package dev.halcamera.cts

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.os.SystemClock
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
            cameras += CameraCaseResult("-", listOf(step("-", "run", Verdict.FAIL, listOf(describe(e)))))
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

    /** The body for one camera. A case that overrides [runCameras] instead leaves this alone. */
    protected open fun runCamera(cameraId: String): CameraCaseResult =
        throw UnsupportedOperationException("override runCamera or runCameras")

    /** Records the step with the listener as it is decided, so the screen fills in while the run continues. */
    protected fun step(cameraId: String, id: String, verdict: Verdict, details: List<String>): StepResult =
        StepResult(id, verdict, details).also { listener.onStep(cameraId, it) }

    /** Waits [ms] unless the run is cancelled first; returns true when the full wait elapsed. */
    protected fun sleepUnlessCancelled(ms: Long): Boolean = !cancelLatch.await(ms, TimeUnit.MILLISECONDS)

    protected fun characteristics(cameraId: String): CameraCharacteristics = env.manager.getCameraCharacteristics(cameraId)

    /** What [openCycle] measured: the timings, the app clock at onOpened, and the first result when one arrived. */
    class Pass(val cycle: OpenCycle, val openedAtNs: Long?, val frame: Camera2Ops.FirstFrame?)

    /**
     * The pass every on/off case performs: open, a preview session on the SurfaceView at [preview], the first
     * completed result of a TEMPLATE_PREVIEW repeating request, then close. Every failure lands in
     * [OpenCycle.error]; the camera is always closed before this returns.
     */
    protected fun openCycle(cameraId: String, preview: Dim): Pass {
        var cycle = OpenCycle()
        var openedAt: Long? = null
        var frame: Camera2Ops.FirstFrame? = null
        var camera: Camera2Ops.OpenedCamera? = null
        var session: Camera2Ops.Session? = null
        try {
            val t0 = SystemClock.elapsedRealtimeNanos()
            camera = ops.open(cameraId)
            val opened = SystemClock.elapsedRealtimeNanos()
            openedAt = opened
            cycle = cycle.copy(openMs = ms(t0, opened))
            val surface = env.previewHost.acquirePreview(preview, Camera2Ops.WAIT_FOR_SURFACE_CHANGE_TIMEOUT_MS)
                ?: error("wait for surface change to $preview timed out")
            val t1 = SystemClock.elapsedRealtimeNanos()
            session = ops.configure(camera.device, listOf(surface))
            val t2 = SystemClock.elapsedRealtimeNanos()
            cycle = cycle.copy(configureMs = ms(t1, t2))
            val request = camera.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }.build()
            val first = ops.startRepeatingAndAwaitFirstFrame(session.session, request)
            frame = first
            cycle = cycle.copy(firstFrameMs = ms(t2, first.completedAtNs))
            // A finally block cannot change a value already returned, so a device fault is folded in before it runs.
            camera.error?.let { cycle = cycle.copy(error = it) }
        } catch (e: Exception) {
            Log.w(source, "camera $cameraId open cycle at $preview failed", e)
            cycle = cycle.copy(error = describe(e))
        } finally {
            session?.close()
            if (camera != null) {
                val t3 = SystemClock.elapsedRealtimeNanos()
                val inTime = camera.close()
                cycle = cycle.copy(closeMs = ms(t3, SystemClock.elapsedRealtimeNanos()), closedInTime = inTime)
            }
        }
        return Pass(cycle, openedAt, frame)
    }

    protected fun ms(fromNs: Long, toNs: Long): Double = (toNs - fromNs) / 1e6

    protected fun describe(e: Exception): String = "${e.javaClass.simpleName}: ${e.message}"
}
