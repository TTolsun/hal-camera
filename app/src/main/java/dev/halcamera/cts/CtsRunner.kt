package dev.halcamera.cts

import android.hardware.camera2.CameraManager
import android.view.Surface
import java.io.File

/**
 * The contract between [CtsCaseActivity] and one transcribed CTS case. A runner is created for one run,
 * [run] blocks on the caller's worker thread until [Listener.onFinished] has been delivered, and [cancel]
 * may be called from any thread; a cancelled run still reports the steps it completed with
 * [CaseReport.cancelled] set.
 */
interface CtsRunner {
    fun run()
    fun cancel()

    interface Listener {
        fun onCameraStarted(cameraId: String, index: Int, total: Int)
        /** A unit of work on [cameraId] is about to start; [index] and [total] count the units planned for it. */
        fun onProgress(cameraId: String, stage: String, index: Int, total: Int)
        fun onStep(cameraId: String, step: StepResult)
        fun onFinished(report: CaseReport)
    }

    /** Camera2SurfaceViewTestCase.updatePreviewSurface: the screen resizes its SurfaceView buffer on request. */
    interface PreviewHost {
        /** Resize the preview buffer to [size] and return its Surface once the change is reported, or null on timeout. */
        fun acquirePreview(size: Dim, timeoutMs: Long): Surface?
    }
}

/** What the screen hands every runner: the camera service, the preview surface, a scratch directory and the window size. */
class CaseEnvironment(
    val manager: CameraManager,
    val previewHost: CtsRunner.PreviewHost,
    val outputDir: File,
    val windowWidth: Int,
    val windowHeight: Int,
    val listener: CtsRunner.Listener
)
