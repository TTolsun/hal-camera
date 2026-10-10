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
    private val ready: () -> Unit,
    private val failed: (String) -> Unit,
    private val recordingChanged: (Boolean) -> Unit,
    private val notice: (String) -> Unit,
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val sourceSize = manager.getCameraCharacteristics(source.id)
        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(SurfaceTexture::class.java)
        .filter { it.width.toLong()*it.height <= 1280L*720 }.maxByOrNull { it.width.toLong()*it.height }
        ?.let { LiveSize(it.width,it.height) } ?: error("No PIP preview stream")
    private val compositor = DeviceCompositor(context,texture,output,listOf(preview,sourceSize),parentId,
        if (source.physical) listOf(source.id) else emptyList(),
        onReady = { main.post { if (!closing) { readyState = true; ready() } } },
        onError = { main.post { if (!closing) failed(it.message ?: "PIP unavailable") } },
        serviceIds = if (source.physical) emptyList() else listOf(source.id), initialRects = listOf(position),
        inputFrame = { index, timestamp -> if (!closing) inputFrame(index, timestamp) })
    private var extra: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var opening = false
    @Volatile private var closing = false
    @Volatile private var readyState = false
    private var mediaClosed = false
    private val media = pipMedia(context, main, { compositor }, { !closing && readyState }, photoFrame, recordingChanged, notice)
    val busy get() = closing || !readyState || media.busy
    private val closeCallbacks = mutableListOf<() -> Unit>()
    private var finished = false
    private var disposing = false

    @SuppressLint("MissingPermission")
    fun start() {
        compositor.start { inputs -> handler.post {
            if (closing) return@post
            if (source.physical) configureMain(inputs)
            else {
                try {
                    val callback=object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) = Unit
                        override fun onConfigureFailed(session: CameraCaptureSession) = Unit
                    }
                    val configs=listOf(parentId,source.id).mapIndexed { index,id ->
                        id to SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                            (listOf(inputs[index]) + if (index == 0) mainOutputs else emptyList()).map(::OutputConfiguration),{ handler.post(it) },callback)
                    }.toMap()
                    if (!manager.isConcurrentSessionConfigurationSupported(configs)) {
                        fail("PIP combination unavailable"); return@post
                    }
                } catch (e: Exception) { fail(e.message ?: "PIP combination unavailable"); return@post }
                opening = true
                try { manager.openCamera(source.id,object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        opening = false; extra = camera
                        if (closing) { camera.close(); return }
                        configureMain(listOf(inputs[0]))
                        @Suppress("DEPRECATION")
                        try { camera.createCaptureSession(listOf(inputs[1]),object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(value: CameraCaptureSession) {
                                if (closing) { value.close(); return }
                                session = value
                                try { value.setRepeatingRequest(camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    .apply { addTarget(inputs[1]) }.build(),null,handler) }
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

    /** The main camera no longer targets our surfaces before this is called. */
    fun close(done: () -> Unit) {
        if (finished) { done(); return }
        closeCallbacks += done
        if (closing) return
        closing = true
        media.close { handler.post { mediaClosed = true; finishClose() } }
        session?.close(); session = null; extra?.close(); finishClose()
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
