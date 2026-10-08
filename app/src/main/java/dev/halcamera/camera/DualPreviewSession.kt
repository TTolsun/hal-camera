package dev.halcamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import dev.halcamera.telemetry.Telemetry
import android.hardware.camera2.CaptureFailure

/** Reads the rear logical multi-cameras and their physical lenses (#171). Characteristics only; nothing is opened. */
fun readLogicalMultiCameras(manager: CameraManager): List<LogicalMultiCamera> {
    val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
    return ids.mapNotNull { id ->
        val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return@mapNotNull null
        val caps = c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES] ?: IntArray(0)
        val logical = Build.VERSION.SDK_INT >= 28 &&
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps
        val physical = if (Build.VERSION.SDK_INT >= 28) c.physicalCameraIds.map { pid -> physicalLens(manager, pid) } else emptyList()
        LogicalMultiCamera(id, c[CameraCharacteristics.LENS_FACING], logical,
            if (Build.VERSION.SDK_INT >= 28) c[CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE] else null,
            physical)
    }
}

private fun physicalLens(manager: CameraManager, id: String): PhysicalLens {
    val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
        ?: return PhysicalLens(id, LensRole.UNKNOWN, null, emptyList())
    val focal = c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.minOrNull()
    val sensor = c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]
    val equivalent = LensRoles.equivalentFocalMm(focal, sensor?.width, sensor?.height)
    val sizes = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]?.getOutputSizes(SurfaceTexture::class.java)
        ?.map { LiveSize(it.width, it.height) }.orEmpty()
    return PhysicalLens(id, LensRoles.roleFor(equivalent), equivalent, sizes)
}

/**
 * One logical camera streaming two of its physical cameras into two SurfaceTextures (#171).
 *
 * Kept apart from the LIVE engines on purpose: it opens the logical id only, routes each output with
 * OutputConfiguration.setPhysicalCameraId, and never touches Benchmark profiles. Changing the pair or leaving the
 * screen closes the whole device, so no buffer from the previous pair can land in the next one's view.
 *
 * Callbacks arrive on the main thread. Only [start] needs API 28; [close] is safe on any device.
 */
interface DualCameraSession {
    fun start()
    fun startRecording()
    fun close(done: () -> Unit)
}

class DualPreviewSession(
    private val manager: CameraManager,
    private val plan: DualPreviewPlan,
    private val textures: Pair<SurfaceTexture, SurfaceTexture>,
    private val listener: Listener,
    private val videoContext: Context? = null,
    private val telemetry: Telemetry? = null,
    private val sessionId: String = "dual"
) : DualCameraSession {
    interface Listener {
        fun onStatus(message: String)
        /** The size every output streams at; called once the repeating request is running. */
        fun onStreaming(size: LiveSize)
        /** Per physical id, the SENSOR_TIMESTAMP of its result, or null when that physical result was missing. */
        fun onResult(physical: Map<String, Long?>)
        fun onFailed(message: String)
        fun onRecording() {}
        fun onVideoSaved(result: Result<Int>) {}
    }

    private val thread = HandlerThread("CD.DualPreview").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }
    private val openRetry = CameraOpenRetry()
    @Volatile private var active = true
    private var device: CameraDevice? = null
    private var opening = false
    private var finished = false
    private var session: CameraCaptureSession? = null
    private var surfaces: List<Surface> = emptyList()
    private var sizeIndex = 0
    private var lastConfigurationFailure = ""
    private var onClosed: (() -> Unit)? = null
    private var video: DualVideoRecording? = null
    private var recording = false
    private val timelineCallback = telemetry?.callback(sessionId, alive = { active })

    override fun startRecording() { handler.post {
        if (!active || session == null || recording) return@post
        try {
            checkNotNull(video).start()
            val request = checkNotNull(device).createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                surfaces.forEach(::addTarget)
                video?.surfaces?.forEach(::addTarget)
            }.build()
            checkNotNull(session).setRepeatingRequest(request, captureCallback, handler)
            recording = true
            main.post { if (active) listener.onRecording() }
        } catch (e: Exception) { fail("Dual video 시작 실패: ${e.message.orEmpty()}") }
    } }

    @RequiresApi(28)
    override fun start() { handler.post { open() } }

    /** [done] runs on the main thread after the device has closed. */
    override fun close(done: () -> Unit) { handler.post {
        active = false
        onClosed = done
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        val camera = device
        if (camera != null) camera.close() else if (!opening) finish()
    } }

    @RequiresApi(28)
    @SuppressLint("MissingPermission")
    private fun open() {
        if (!active) return
        status("카메라 ${plan.logicalId} 여는 중")
        try {
            opening = true
            manager.openCamera(plan.logicalId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opening = false
                    device = camera
                    if (!active) camera.close() else configure(camera)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    opening = false
                    device = camera
                    retryOrFail(CameraOpenRetry.Cause.DISCONNECTED, "카메라 연결이 끊겼습니다.")
                    camera.close()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    opening = false
                    device = camera
                    retryOrFail(CameraOpenRetry.Cause.fromStateError(error), "Camera2 오류 $error")
                    camera.close()
                }
                override fun onClosed(camera: CameraDevice) {
                    device = null
                    val delay = pendingRetryMs.also { pendingRetryMs = null }
                    if (active && delay != null) handler.postDelayed({ open() }, delay)
                    else if (!active) finish()
                }
            }, handler)
        } catch (e: CameraAccessException) {
            opening = false
            val delay = if (active && session == null) openRetry.next(CameraOpenRetry.Cause.fromAccessReason(e.reason)) else null
            if (delay != null) handler.postDelayed({ open() }, delay) else fail("카메라를 열지 못했습니다. ${e.message.orEmpty()}")
        } catch (e: Exception) {
            opening = false
            fail("카메라를 열지 못했습니다. ${e.message.orEmpty()}")
        }
    }

    private var pendingRetryMs: Long? = null

    private fun retryOrFail(cause: CameraOpenRetry.Cause, message: String) {
        if (!active) return
        pendingRetryMs = if (session == null) openRetry.next(cause) else null
        if (pendingRetryMs == null) fail(message)
    }

    /** Tries the candidate sizes in order; the HAL's answer, not the stream map, decides which one runs. */
    @RequiresApi(28)
    private fun configure(camera: CameraDevice) {
        if (!active) return
        val size = plan.sizes.getOrNull(sizeIndex)
            ?: return fail("출력 조합 미지원 · $lastConfigurationFailure · 시도: ${plan.sizes.joinToString()}")
        surfaces.forEach { it.release() }
        video?.discard()
        video = null
        if (videoContext != null) {
            val supported = runCatching { listOf(plan.first.id, plan.second.id).all { id ->
                manager.getCameraCharacteristics(id)[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
                    ?.getOutputSizes(MediaRecorder::class.java)?.any { it.width == size.width && it.height == size.height } == true
            } }.getOrElse { return fail("MediaRecorder 크기 조회 실패: ${it.message.orEmpty()}") }
            if (!supported) return nextSize(camera, "$size MediaRecorder 출력 미지원")
            try {
                video = DualVideoRecording(videoContext, listOf(plan.first.id, plan.second.id), size) {
                    handler.post { if (active) fail("Dual video encoder 오류") }
                }
            } catch (e: Exception) { return nextSize(camera, "$size encoder 구성 실패: ${e.message.orEmpty()}") }
        }
        textures.first.setDefaultBufferSize(size.width, size.height)
        textures.second.setDefaultBufferSize(size.width, size.height)
        surfaces = listOf(Surface(textures.first), Surface(textures.second))
        val outputs = listOf(plan.first.id, plan.second.id).mapIndexed { index, id ->
            OutputConfiguration(surfaces[index]).apply {
                setPhysicalCameraId(id)
                video?.let { enableSurfaceSharing(); addSurface(it.surfaces[index]) }
            }
        }
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (!active) { s.close(); return }
                session = s
                try {
                    val characteristics = manager.getCameraCharacteristics(plan.logicalId)
                    telemetry?.sessions?.put(sessionId, mapOf("engine" to "Camera2", "cameraId" to plan.logicalId,
                        "physicalIds" to listOf(plan.first.id, plan.second.id),
                        "timestampSource" to characteristics[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE]))
                    val request = camera.createCaptureRequest(if (video != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW).apply {
                        surfaces.forEach(::addTarget)
                    }.build()
                    s.setRepeatingRequest(request, captureCallback, handler)
                    main.post { if (active) listener.onStreaming(size) }
                    status("${plan.first.id} + ${plan.second.id} · $size")
                } catch (e: Exception) {
                    fail("프리뷰 요청을 시작하지 못했습니다. ${e.message.orEmpty()}")
                }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { s.close(); nextSize(camera, "$size 구성 거부") }
        }
        val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback)
        if (Build.VERSION.SDK_INT >= 29) {
            val supported = try { camera.isSessionConfigurationSupported(config) } catch (_: Exception) { null }
            if (supported == false) return nextSize(camera, "$size 미지원 (isSessionConfigurationSupported)")
        }
        try {
            camera.createCaptureSession(config)
        } catch (e: Exception) {
            nextSize(camera, "$size 구성 실패: ${e.message.orEmpty()}")
        }
    }

    @RequiresApi(28)
    private fun nextSize(camera: CameraDevice, reason: String) {
        lastConfigurationFailure = reason
        status(reason)
        sizeIndex++
        configure(camera)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(s: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            timelineCallback?.onCaptureStarted(s, request, timestamp, frameNumber)
        }
        override fun onCaptureProgressed(s: CameraCaptureSession, request: CaptureRequest, result: CaptureResult) {
            timelineCallback?.onCaptureProgressed(s, request, result)
        }
        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            timelineCallback?.onCaptureFailed(s, request, failure)
        }
        override fun onCaptureBufferLost(s: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
            timelineCallback?.onCaptureBufferLost(s, request, target, frameNumber)
        }
        @RequiresApi(28)
        @Suppress("DEPRECATION")
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            timelineCallback?.onCaptureCompleted(s, request, result)
            val physical = result.physicalCameraResults
            val timestamps = listOf(plan.first.id, plan.second.id).associateWith { id ->
                physical[id]?.get(CaptureResult.SENSOR_TIMESTAMP)
            }
            telemetry?.event(sessionId, "dual_physical_result", timestamps.mapValues { it.value?.toString() })
            main.post { if (active) listener.onResult(timestamps) }
        }
    }

    private fun status(message: String) = main.post { if (active) listener.onStatus(message) }

    /** A failure stops the stream and frees the camera; the screen keeps the message until it is left or retried. */
    private fun fail(message: String) {
        video?.invalidate()
        main.post { if (active) listener.onFailed(message) }
        runCatching { session?.close() }
        session = null
        device?.close()
    }

    private fun finish() {
        if (finished) return
        finished = true
        val saved = video?.finish()
        video = null
        if (saved != null) main.post { listener.onVideoSaved(saved) }
        surfaces.forEach { it.release() }
        surfaces = emptyList()
        thread.quitSafely()
        val done = onClosed.also { onClosed = null }
        if (done != null) main.post(done)
    }
}
