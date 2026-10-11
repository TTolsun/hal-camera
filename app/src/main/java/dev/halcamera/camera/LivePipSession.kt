package dev.halcamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.net.Uri
import android.os.Handler
import android.view.Surface
import androidx.annotation.RequiresApi
import dev.halcamera.telemetry.Telemetry
import java.util.concurrent.Executor

/** Owns only the added service device. The existing Live device stays with Camera2Engine. */
@RequiresApi(30)
internal class LivePipSession(
    private val context: Context,
    private val handler: Handler,
    private val main: Handler,
    private val parentId: String,
    val source: PipSource,
    texture: SurfaceTexture,
    output: LiveSize,
    preview: LiveSize,
    position: PipRect,
    private val configureMain: (List<Surface>) -> Unit,
    private val mainOutputs: List<Surface>,
    private val inputFrame: (Int, Long) -> Unit,
    private val photoFrame: (Long) -> Unit,
    private val rawPhoto: ((String?, (Result<PhotoResult>) -> Unit) -> Unit)? = null,
    private val ready: () -> Unit,
    private val failed: (String) -> Unit,
    private val recordingChanged: (Boolean) -> Unit,
    private val notice: (String) -> Unit,
    private val videoMode: Boolean = false,
    sourcePreview: LiveSize? = null,
    private val photoPlan: PipPhotoPlan? = null,
    sourcePhoto: LiveSize? = null,
    streams: PipStreamSettings? = null,
    private val telemetry: Telemetry? = null,
    private val parentSession: String? = null,
    private val mediaIo: Executor? = null,
    private val displayRotation: () -> Int = { Surface.ROTATION_0 },
    private val physicalCapture: ((ConfiguredOutput<Surface>, Int, CameraCaptureSession.CaptureCallback) -> Unit)? = null,
    private val physicalCallback: CameraCaptureSession.CaptureCallback? = null,
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    val sourceSize = sourcePreview ?: readPipPreviewSizes(manager,source.id,streams?.preview).firstOrNull() ?: error("No PIP preview stream")
    val sourcePhotoSize = photoPlan?.let { sourcePhoto ?:
        readPipPhotoSizes(manager,source,it,streams?.photo).firstOrNull() ?: error("No PIP photo stream") }
    private val serviceTelemetry = if (source.physical) null else PipServiceTelemetry(checkNotNull(telemetry),
        checkNotNull(parentSession), manager, source.id, "Camera2 PIP") { !closing }
    val callbackSession get() = serviceTelemetry?.sessionId
    private val photo = photoPlan?.let { plan -> PipPhotoReader(handler,main,checkNotNull(telemetry),
        serviceTelemetry?.sessionId ?: checkNotNull(parentSession), MediaLibrary(context), checkNotNull(mediaIo),
        plan.descriptor(source),checkNotNull(sourcePhotoSize)) {
            sourceOrientation()
        } }
    val physicalPhotoOutputs get() = if (source.physical) listOfNotNull(photo?.output) else emptyList()
    private val compositor = DeviceCompositor(context,texture,output,listOf(preview,sourceSize),parentId,
        if (source.physical) listOf(source.id) else emptyList(),
        onReady = { main.post { if (!closing) { readyState = true; ready() } } },
        onError = { main.post { if (!closing) failed(it.message ?: "PIP unavailable") } },
        serviceIds = if (source.physical) emptyList() else listOf(source.id), initialRects = listOf(position),
        inputFrame = { index, timestamp -> if (!closing) {
            if (index == 1 && !source.physical) serviceTelemetry?.preview(timestamp) else inputFrame(index, timestamp)
        } }, prepareRecording = videoMode)
    private var extra: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var opening = false
    @Volatile private var closing = false
    @Volatile private var readyState = false
    private var mediaClosed = false
    private val media = pipMedia(context, main, { compositor }, { !closing && readyState }, photoFrame, recordingChanged, notice,
        if (rawPhoto == null) null else ::originalPhotos)
    val busy get() = closing || !readyState || media.busy
    private val closeCallbacks = mutableListOf<() -> Unit>()
    private var finished = false
    private var disposing = false

    private fun sourceOrientation(): Int {
        val c = manager.getCameraCharacteristics(source.id)
        val degrees = when (displayRotation()) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        return pipPhotoOrientation(c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0,
            c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT,degrees)
    }

    private fun originalPhotos(requestId: String?, done: (Result<PhotoResult>) -> Unit) { handler.post {
        val secondary = photo
        if (secondary == null) checkNotNull(rawPhoto)(requestId,done)
        else capturePipPhotoGroup(requestId,listOf(
            { complete ->
                secondary.begin(requestId,complete)
                try {
                    if (source.physical) checkNotNull(physicalCapture)(secondary.output,secondary.captureRotation,
                        secondary.callback(checkNotNull(physicalCallback)))
                    else captureSecondary(secondary)
                } catch (e: Exception) { secondary.abort(e) }
            },
            { complete -> checkNotNull(rawPhoto)(requestId,complete) }
        ),MediaLibrary(context),checkNotNull(mediaIo),main,done,mainIndex = 1)
    } }

    private fun captureSecondary(target: PipPhotoReader) {
        val camera = checkNotNull(extra)
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(target.reader.surface)
            set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_OFF)
            val characteristics = manager.getCameraCharacteristics(source.id)
            val af = characteristics[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
            set(CaptureRequest.CONTROL_AF_MODE,if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in af)
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE else CaptureRequest.CONTROL_AF_MODE_OFF)
            set(CaptureRequest.JPEG_ORIENTATION,target.captureRotation)
            set(CaptureRequest.JPEG_QUALITY,95.toByte())
            setTag("pip-still-${android.os.SystemClock.elapsedRealtimeNanos()}")
        }.build()
        val observed = checkNotNull(serviceTelemetry)
        checkNotNull(session).capture(observed.track(request,listOf(target.descriptor.id)),target.callback(observed.callback),handler)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        compositor.start { inputs -> handler.post {
            if (closing) return@post
            if (source.physical) configureMain(inputs)
            else {
                val secondaryOutputs = StreamConfiguration(listOf(ConfiguredOutput(
                    OutputDescriptor("preview",OutputKind.PREVIEW,true,eventKind = "pip_preview_available"),inputs[1])) + listOfNotNull(photo?.output))
                serviceTelemetry?.configure(secondaryOutputs, composedRecording = videoMode)
                try {
                    val callback=object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) = Unit
                        override fun onConfigureFailed(session: CameraCaptureSession) = Unit
                    }
                    val configs=listOf(parentId,source.id).mapIndexed { index,id ->
                        id to SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                            (if (index == 0) listOf(inputs[index]) + mainOutputs else secondaryOutputs.targets).map(::OutputConfiguration),{ handler.post(it) },callback)
                    }.toMap()
                    if (!manager.isConcurrentSessionConfigurationSupported(configs)) {
                        fail("PIP combination unavailable"); return@post
                    }
                } catch (_: UnsupportedOperationException) { /* Query unavailable: attempt the real session. */ }
                catch (e: Exception) { fail(e.message ?: "PIP combination unavailable"); return@post }
                opening = true
                try { manager.openCamera(source.id,object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        opening = false; extra = camera
                        if (closing) { camera.close(); return }
                        configureMain(listOf(inputs[0]))
                        @Suppress("DEPRECATION")
                        try { camera.createCaptureSession(secondaryOutputs.targets,object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(value: CameraCaptureSession) {
                                if (closing) { value.close(); return }
                                session = value
                                try { value.setRepeatingRequest(camera.createCaptureRequest(if (videoMode) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
                                    .apply {
                                        addTarget(inputs[1])
                                        if (!LiveModePolicy.forVideo(videoMode).allowsStabilization) {
                                            val keys = manager.getCameraCharacteristics(source.id).availableCaptureRequestKeys
                                            if (CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE in keys) set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                                            if (CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE in keys) set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                                        }
                                    }.build().let { checkNotNull(serviceTelemetry).track(it,listOf("preview")) },serviceTelemetry?.callback,handler) }
                                catch (e: Exception) { fail(e.message ?: "PIP unavailable") }
                            }
                            override fun onConfigureFailed(value: CameraCaptureSession) { value.close(); fail("PIP combination unavailable") }
                        },handler) } catch (e: Exception) { fail(e.message ?: "PIP unavailable") }
                    }
                    override fun onDisconnected(camera: CameraDevice) { opening = false; extra = camera; fail("PIP camera disconnected"); camera.close() }
                    override fun onError(camera: CameraDevice,error: Int) { opening = false; extra = camera; fail("PIP camera unavailable"); camera.close() }
                    override fun onClosed(camera: CameraDevice) { extra = null; finishClose() }
                },handler) } catch (e: Exception) { opening = false; fail(e.message ?: "PIP unavailable") }
            }
        } }
        handler.postDelayed({ if (!closing && !readyState) fail("PIP camera timed out") },15_000)
    }
    private fun fail(message: String) { main.post { if (!closing) failed(message) } }
    fun move(rect: PipRect) = compositor.move(0,rect)

    fun capture(requestId: String?, done: (Result<PhotoResult>) -> Unit) = media.capture(requestId, done)
    fun startVideo(audio: Boolean, started: () -> Unit, done: ((Result<Uri>) -> Unit)?) = media.start(audio, started, done)
    fun stopVideo() = media.stop()
    val snapshotStatus get() = media.snapshotStatus
    fun snapshot(done: (Result<PhotoResult>) -> Unit) = media.snapshot(done)

    /** The main camera no longer targets our surfaces before this is called. */
    fun close(done: () -> Unit) {
        if (finished) { done(); return }
        closeCallbacks += done
        if (closing) return
        closing = true
        media.close { handler.post { mediaClosed = true; finishClose() } }
        handler.post {
            photo?.close()
            serviceTelemetry?.close()
            session?.close(); session = null; extra?.close(); finishClose()
        }
    }
    private fun finishClose() {
        if (!closing || !mediaClosed || opening || extra != null || disposing) return
        disposing = true
        compositor.close { handler.post {
            finished = true
            closeCallbacks.toList().also { closeCallbacks.clear() }.forEach { it() }
        } }
    }
}
