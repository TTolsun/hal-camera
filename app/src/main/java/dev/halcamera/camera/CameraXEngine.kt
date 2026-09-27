package dev.halcamera.camera

import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import dev.halcamera.telemetry.Telemetry
import java.util.concurrent.ExecutorService

@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class CameraXEngine(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val view: PreviewView,
    private val cameraId: String,
    private val session: String,
    private val telemetry: Telemetry,
    private val executor: ExecutorService,
    private val status: (String, Boolean) -> Unit
) : CameraEngine, TouchMetering {
    @Volatile private var active = true
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var takingPhoto = false
    private val main = ContextCompat.getMainExecutor(context)
    override fun start() {
        telemetry.registerSession(session, "CameraX", context.getSystemService(CameraManager::class.java), cameraId)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!active) return@addListener
            try {
                provider = future.get()
                val builder = Preview.Builder()
                Camera2Interop.Extender(builder).setSessionCaptureCallback(telemetry.callback(session) { active })
                preview = builder.build().also { it.setSurfaceProvider(view.surfaceProvider) }
                analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also { useCase ->
                    useCase.setAnalyzer(executor) { image ->
                        try { if (active) telemetry.image(session, image.imageInfo.timestamp, image.width, image.height, image.format, "analysis_keep_latest") }
                        finally { image.close() }
                    }
                }
                capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                val selector = CameraSelector.Builder().addCameraFilter { infos ->
                    infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
                }.build()
                camera = provider!!.bindToLifecycle(owner, selector, preview, analysis, capture)
                camera!!.cameraInfo.cameraState.observe(owner) { state ->
                    if (!active) return@observe
                    state.error?.let { status("CameraX error ${it.code}", false); telemetry.event(session, "camera_error", mapOf("code" to it.code)) }
                    if (state.type == CameraState.Type.OPEN) status("CameraX · LIVE", true)
                }
                val sizes = mapOf("preview" to preview?.resolutionInfo?.resolution?.toString(),
                    "analysis" to analysis?.resolutionInfo?.resolution?.toString(), "jpeg" to capture?.resolutionInfo?.resolution?.toString())
                telemetry.sessions.computeIfPresent(session) { _, old -> old + mapOf("negotiatedStreams" to sizes) }
                telemetry.event(session, "bound", sizes)
            } catch (e: Exception) { status("CameraX: ${e.message}", false); telemetry.event(session, "camera_error", mapOf("message" to e.toString())) }
        }, main)
    }
    override fun capture() {
        val useCase = capture ?: return
        if (!active || takingPhoto) return
        takingPhoto = true
        telemetry.event(session, "capture_submit", mapOf("api" to "ImageCapture.takePicture", "correlation" to "one app capture in flight; CameraX owns internal tags"))
        useCase.takePicture(main, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    if (active) {
                        telemetry.image(session, image.imageInfo.timestamp, image.width, image.height, image.format, "still")
                        status("CameraX · capture received", true)
                    }
                } finally { image.close(); takingPhoto = false }
            }
            override fun onError(exception: ImageCaptureException) {
                takingPhoto = false
                if (active) { telemetry.event(session, "capture_error", mapOf("message" to exception.message)); status("Capture: ${exception.message}", false) }
            }
        })
    }
    override fun setZoom(ratio: Float) {
        val cam = camera ?: return
        if (!active) return
        val state = cam.cameraInfo.zoomState.value
        val clamped = if (state != null) ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio) else ratio
        telemetry.event(session, "zoom_set", mapOf("zoomRequested" to clamped, "api" to "CameraControl.setZoomRatio"))
        cam.cameraControl.setZoomRatio(clamped)
    }
    /**
     * CameraX maps the touch through PreviewView itself (rotation, mirroring, fill-crop, zoom). A tap focuses and is
     * cancelled after the same hold as Camera2; a long press meters exposure and stays. CameraX has no AE lock, so
     * the exposure point is not locked. A newer touch cancels the older future, which then reports nothing.
     */
    override fun meterAt(x: Float, y: Float, exposure: Boolean, feedback: (TouchPhase) -> Unit): Boolean {
        val cam = camera ?: return false
        val builder = FocusMeteringAction.Builder(view.meteringPointFactory.createPoint(x, y),
            if (exposure) FocusMeteringAction.FLAG_AE else FocusMeteringAction.FLAG_AF)
        val action = (if (exposure) builder.disableAutoCancel() else builder.setAutoCancelDuration(TouchMeter.HOLD_MS, java.util.concurrent.TimeUnit.MILLISECONDS)).build()
        if (!active || !cam.cameraInfo.isFocusMeteringSupported(action)) return false
        telemetry.event(session, "touch_meter", mapOf("kind" to if (exposure) "AE" else "AF", "x" to x, "y" to y, "api" to "CameraControl.startFocusAndMetering"))
        feedback(TouchPhase.SCANNING)
        val future = cam.cameraControl.startFocusAndMetering(action)
        future.addListener({
            val focused = runCatching { future.get().isFocusSuccessful }.getOrNull() ?: return@addListener
            val phase = if (exposure) TouchPhase.METERED else if (focused) TouchPhase.FOCUSED else TouchPhase.FAILED
            telemetry.event(session, "touch_meter_result", mapOf("phase" to phase.name))
            if (!active) return@addListener
            feedback(phase)
            if (!exposure) view.postDelayed({ if (active) feedback(TouchPhase.DONE) }, TouchMeter.HOLD_MS)
        }, main)
        return true
    }
    override fun close(done: () -> Unit) {
        active = false
        analysis?.clearAnalyzer()
        val info = camera?.cameraInfo
        val bound = listOfNotNull(preview, analysis, capture).toTypedArray()
        if (info == null || provider == null) { done(); return }
        var finished = false
        lateinit var observer: Observer<CameraState>
        fun finish() {
            if (finished) return
            finished = true
            info.cameraState.removeObserver(observer)
            telemetry.event(session, "closed")
            done()
        }
        observer = Observer { if (it.type == CameraState.Type.CLOSED) finish() }
        // Observe forever so releasing the camera also completes while the Activity is stopped.
        info.cameraState.observeForever(observer)
        provider!!.unbind(*bound)
        if (info.cameraState.value?.type == CameraState.Type.CLOSED) finish()
    }
}
