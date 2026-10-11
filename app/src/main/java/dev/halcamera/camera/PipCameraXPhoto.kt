package dev.halcamera.camera

import android.content.Context
import android.os.Handler
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import dev.halcamera.telemetry.Telemetry
import java.util.concurrent.Executor

internal fun pipExactResolution(size: LiveSize) = ResolutionSelector.Builder().setResolutionFilter { sizes, _ ->
    sizes.filter { it.width == size.width && it.height == size.height }
}.build()

internal fun pipImageCapture(size: LiveSize) = ImageCapture.Builder()
    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
    .setTargetRotation(Surface.ROTATION_0).setResolutionSelector(pipExactResolution(size)).build()

/** The secondary camera saves the same native JPEG or app YUV conversion as ordinary CameraX Live. */
internal class PipCameraXPhoto(context: Context, private val handler: Handler,
    private val telemetry: Telemetry, private val sessionId: String, plan: PipPhotoPlan,
    size: LiveSize, private val io: Executor, private val displayRotation: () -> Int) {
    @Volatile private var closed = false
    val descriptor = OutputDescriptor("pip_still",plan.kind,plan.kind == OutputKind.YUV,
        stillCapture = plan.kind == OutputKind.JPEG)
    private val jpeg = if (plan.kind == OutputKind.JPEG) pipImageCapture(size) else null
    private val analysis = if (plan.kind == OutputKind.YUV) ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setTargetRotation(Surface.ROTATION_0).setResolutionSelector(pipExactResolution(size)).build() else null
    val useCase: UseCase get() = checkNotNull(jpeg ?: analysis)
    val actualSize get() = (jpeg?.resolutionInfo ?: analysis?.resolutionInfo)?.resolution?.let { LiveSize(it.width,it.height) }
    private val stills = CameraXStillCapture(context,handler,{ handler.post(it) },telemetry,sessionId,MediaLibrary(context),io,
        object : CameraXStillCapture.Host {
            override val imageCapture get() = jpeg
            override val analysisEnabled get() = analysis != null
            override val active get() = !closed
            override val recordingBusy get() = false
            override val flashName get() = "off"
            override val zoomRequested get() = 1f
            override val stillStream get() = descriptor.id
            override fun updateRotation() {
                val rotation = displayRotation()
                jpeg?.targetRotation = rotation; analysis?.targetRotation = rotation
            }
            override fun report(message: String, ok: Boolean) = Unit
        })
    init { analysis?.setAnalyzer(io) { image ->
        try {
            if (!closed) {
                telemetry.image(sessionId,image.imageInfo.timestamp,image.width,image.height,image.format,descriptor.id)
                stills.onFrame(image)
            }
        } finally { image.close() }
    } }
    fun capture(requestId: String?, done: (Result<PhotoResult>) -> Unit) = stills.capture(requestId,done)
    fun close() { closed = true; stills.close(); analysis?.clearAnalyzer() }
}
