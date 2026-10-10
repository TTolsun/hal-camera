package dev.halcamera.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.net.Uri
import android.os.Handler
import android.view.Surface
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer

/** CameraX owns both devices; the existing Live compositor owns their shared preview and saved pixels. */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
internal class CameraXPipSession(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val provider: ProcessCameraProvider,
    private val main: Handler,
    private val parentId: String,
    private val source: PipSource,
    private val selectors: List<CameraSelector>,
    private val texture: SurfaceTexture,
    private val output: LiveSize,
    private val position: PipRect,
    private val captureCallback: CameraCaptureSession.CaptureCallback,
    private val analysis: androidx.camera.core.ImageAnalysis?,
    private val bound: (Camera, List<Preview>) -> Unit,
    private val inputFrame: (Int, Long) -> Unit,
    private val photoFrame: (Long) -> Unit,
    private val ready: () -> Unit,
    private val failed: (String) -> Unit,
    private val recordingChanged: (Boolean) -> Unit,
    private val notice: (String) -> Unit,
) {
    private val executor = java.util.concurrent.Executor { main.post(it) }
    private val requests = arrayOfNulls<SurfaceRequest>(2)
    private var cameras = emptyList<Camera>()
    private var compositor: DeviceCompositor? = null
    private var provided = 0
    private var closing = false
    private var disposed = false
    private var finished = false
    private var readyState = false
    private var failureSent = false
    private var capturing = false
    private var recording = false
    private var pending = false
    private val closeCallbacks = mutableListOf<() -> Unit>()
    private val observers = mutableListOf<Pair<Camera, Observer<CameraState>>>()
    private var videoDone: ((Result<Uri>) -> Unit)? = null
    private val timeout = Runnable { if (!readyState) fail("PIP camera timed out") }
    val busy get() = closing || !readyState || capturing || recording || pending

    fun start() {
        try {
            val previews = selectors.mapIndexed { index, _ ->
                val builder = Preview.Builder().setTargetRotation(Surface.ROTATION_0)
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionFilter { sizes, _ ->
                        sizes.filter { it.width.toLong() * it.height <= 1280L * 720 }
                    }.build())
                if (index == 0) Camera2Interop.Extender(builder).setSessionCaptureCallback(captureCallback)
                builder.build().also { preview -> preview.setSurfaceProvider(executor) { request ->
                    if (closing) request.willNotProvideSurface()
                    else if (requests[index] != null) {
                        request.willNotProvideSurface(); fail("PIP stream changed")
                    } else {
                        requests[index] = request
                        request.addRequestCancellationListener(executor) { if (!closing) fail("PIP stream unavailable") }
                        if (requests.all { it != null }) startCompositor()
                    }
                } }
            }
            cameras = provider.bindToLifecycle(selectors.mapIndexed { index, selector ->
                ConcurrentCamera.SingleCameraConfig(selector,
                    UseCaseGroup.Builder().addUseCase(previews[index]).apply {
                        if (index == 0) analysis?.let { addUseCase(it) }
                    }.build(), owner)
            }).cameras
            cameras.forEach { camera ->
                val observer = Observer<CameraState> { state ->
                    if (closing) finishClose()
                    else if (state.error != null) fail("PIP camera unavailable")
                }
                observers += camera to observer
                camera.cameraInfo.cameraState.observeForever(observer)
            }
            bound(cameras.first { Camera2CameraInfo.from(it.cameraInfo).cameraId == parentId }, previews)
            main.postDelayed(timeout, 15_000)
        } catch (e: Exception) { fail(e.message ?: "PIP combination unavailable") }
    }

    private fun startCompositor() {
        val sizes = requests.map { checkNotNull(it).resolution.let { size -> LiveSize(size.width,size.height) } }
        // Live is portrait-locked, with no ViewPort crop. CameraX Preview uses the camera's SurfaceTexture
        // transform (sensor rotation and front mirroring), just like Camera2's compositor inputs.
        val compose = DeviceCompositor(context,texture,output,sizes,parentId,emptyList(),
            onReady = { main.post { if (!closing && !failureSent) {
                readyState = true; main.removeCallbacks(timeout); ready()
            } } },
            onError = { main.post { fail(it.message ?: "PIP unavailable") } },
            serviceIds = listOf(source.id), initialRects = listOf(position), inputFrame = inputFrame)
        compositor = compose
        compose.start { surfaces -> main.post {
            if (closing) { finishClose(); return@post }
            requests.forEachIndexed { index, request ->
                provided++
                checkNotNull(request).provideSurface(surfaces[index], executor) {
                    provided--
                    if (closing) finishClose() else fail("PIP stream ended")
                }
            }
        } }
    }

    private fun fail(message: String) {
        if (closing || failureSent) return
        failureSent = true; main.removeCallbacks(timeout); failed(message)
    }

    fun move(rect: PipRect) { compositor?.move(0,rect) }

    fun capture(requestId: String?, done: (Result<PhotoResult>) -> Unit) {
        if (busy) { done(Result.failure(IllegalStateException("Camera busy"))); return }
        capturing = true
        checkNotNull(compositor).capture { photo ->
            val result = photo.mapCatching {
                photoFrame(it.imageTimestampNs)
                val library = MediaLibrary(context)
                val name = "${library.name()}_PIP.jpg"
                val uri = library.create(name,false)
                try { library.write(uri) { stream -> stream.write(it.bytes) }; library.publish(uri) }
                catch (e: Exception) { library.resolver.delete(uri,null,null); throw e }
                PhotoResult(requestId,name,0,listOf(uri),listOf(PhotoArtifact(name,"image/jpeg",uri,it.bytes.size.toLong())))
            }
            main.post { capturing = false; done(result) }
        }
    }

    fun startVideo(audio: Boolean, started: () -> Unit, done: ((Result<Uri>) -> Unit)?) {
        if (busy) { done?.invoke(Result.failure(IllegalStateException("Camera busy"))); return }
        pending = true; videoDone = done
        checkNotNull(compositor).startVideo(MediaLibrary(context).name(),audio) { result -> main.post {
            pending = false; recording = result.isSuccess
            if (closing) return@post
            recordingChanged(recording)
            if (result.isSuccess) started() else {
                videoDone?.invoke(Result.failure(result.exceptionOrNull()!!)); videoDone = null
                notice("Video not saved")
            }
        } }
    }

    fun stopVideo() {
        if (!recording || pending) return
        pending = true
        checkNotNull(compositor).stopVideo(true) { result -> main.post { finishVideo(result) } }
    }

    private fun finishVideo(result: Result<String>) {
        pending = false; recording = false
        recordingChanged(false)
        videoDone?.invoke(result.map(Uri::parse)); videoDone = null
        notice(if (result.isSuccess) "Saved video" else "Video not saved")
        if (closing) finishClose()
    }

    fun close(done: () -> Unit) {
        if (finished) { done(); return }
        closeCallbacks += done
        if (closing) return
        closing = true; main.removeCallbacks(timeout)
        requests.filterNotNull().forEach { it.willNotProvideSurface() }
        if (recording || pending) {
            if (!pending || !recording) {
                pending = true
                compositor?.stopVideo(true) { result -> main.post { finishVideo(result) } }
            }
        }
        provider.unbindAll()
        finishClose()
    }

    /** SurfaceRequest completion AND both CameraState.CLOSED events precede disposing inputs/reopening. */
    private fun finishClose() {
        if (!closing || disposed || provided != 0 || pending || recording ||
            cameras.any { it.cameraInfo.cameraState.value?.type != CameraState.Type.CLOSED }) return
        disposed = true
        observers.forEach { (camera, observer) -> camera.cameraInfo.cameraState.removeObserver(observer) }
        observers.clear()
        val finish = {
            finished = true
            closeCallbacks.toList().also { closeCallbacks.clear() }.forEach { it() }
        }
        compositor?.close { main.post(finish) } ?: finish()
    }
}
