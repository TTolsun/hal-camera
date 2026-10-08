package dev.halcamera.camera

import android.annotation.SuppressLint
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
class DualPreviewSession(
    private val manager: CameraManager,
    private val plan: DualPreviewPlan,
    private val textures: Pair<SurfaceTexture, SurfaceTexture>,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(message: String)
        /** The size every output streams at; called once the repeating request is running. */
        fun onStreaming(size: LiveSize)
        /** Per physical id, the SENSOR_TIMESTAMP of its result, or null when that physical result was missing. */
        fun onResult(physical: Map<String, Long?>)
        fun onFailed(message: String)
    }

    private val thread = HandlerThread("CD.DualPreview").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }
    private val openRetry = CameraOpenRetry()
    @Volatile private var active = true
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surfaces: List<Surface> = emptyList()
    private var sizeIndex = 0
    private var onClosed: (() -> Unit)? = null

    @RequiresApi(28)
    fun start() = handler.post { open() }

    /** [done] runs on the main thread after the device has closed. */
    fun close(done: () -> Unit) = handler.post {
        active = false
        onClosed = done
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        val camera = device
        if (camera == null) finish() else camera.close()
    }

    @RequiresApi(28)
    @SuppressLint("MissingPermission")
    private fun open() {
        if (!active) return
        status("카메라 ${plan.logicalId} 여는 중")
        try {
            manager.openCamera(plan.logicalId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    if (!active) camera.close() else configure(camera)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    retryOrFail(CameraOpenRetry.Cause.DISCONNECTED, "카메라 연결이 끊겼습니다.")
                    camera.close()
                }
                override fun onError(camera: CameraDevice, error: Int) {
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
            val delay = if (active && session == null) openRetry.next(CameraOpenRetry.Cause.fromAccessReason(e.reason)) else null
            if (delay != null) handler.postDelayed({ open() }, delay) else fail("카메라를 열지 못했습니다. ${e.message.orEmpty()}")
        } catch (e: Exception) {
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
            ?: return fail("이 물리 카메라 조합을 받아주는 크기가 없습니다. 시도: ${plan.sizes.joinToString()}")
        surfaces.forEach { it.release() }
        textures.first.setDefaultBufferSize(size.width, size.height)
        textures.second.setDefaultBufferSize(size.width, size.height)
        surfaces = listOf(Surface(textures.first), Surface(textures.second))
        val outputs = listOf(
            OutputConfiguration(surfaces[0]).apply { setPhysicalCameraId(plan.first.id) },
            OutputConfiguration(surfaces[1]).apply { setPhysicalCameraId(plan.second.id) })
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (!active) { s.close(); return }
                session = s
                try {
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        surfaces.forEach(::addTarget)
                    }.build()
                    s.setRepeatingRequest(request, captureCallback, handler)
                    main.post { if (active) listener.onStreaming(size) }
                    status("${plan.first.id} + ${plan.second.id} · $size")
                } catch (e: Exception) {
                    fail("프리뷰 요청을 시작하지 못했습니다. ${e.message.orEmpty()}")
                }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { nextSize(camera, "$size 구성 거부") }
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
        status(reason)
        sizeIndex++
        configure(camera)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        @RequiresApi(28)
        @Suppress("DEPRECATION")
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val physical = result.physicalCameraResults
            val timestamps = listOf(plan.first.id, plan.second.id).associateWith { id ->
                physical[id]?.get(CaptureResult.SENSOR_TIMESTAMP)
            }
            main.post { if (active) listener.onResult(timestamps) }
        }
    }

    private fun status(message: String) = main.post { if (active) listener.onStatus(message) }

    /** A failure stops the stream and frees the camera; the screen keeps the message until it is left or retried. */
    private fun fail(message: String) {
        main.post { if (active) listener.onFailed(message) }
        runCatching { session?.close() }
        session = null
        device?.close()
    }

    private fun finish() {
        surfaces.forEach { it.release() }
        surfaces = emptyList()
        thread.quitSafely()
        val done = onClosed.also { onClosed = null }
        if (done != null) main.post(done)
    }
}
