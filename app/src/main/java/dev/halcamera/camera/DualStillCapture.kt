package dev.halcamera.camera

import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import androidx.annotation.RequiresApi
import dev.halcamera.telemetry.Telemetry
import java.util.concurrent.Executor

/** A bounded two-YUV still session; both physical targets belong to the same capture request. */
@RequiresApi(28)
internal class DualStillCapture(
    private val camera: CameraDevice,
    private val manager: CameraManager,
    private val plan: DualPreviewPlan,
    private val handler: Handler,
    private val controls: DualMainControls,
    private val telemetry: Telemetry?,
    private val sessionId: String,
    private val displayDegrees: Int,
    private val callback: CameraCaptureSession.CaptureCallback?,
    private val done: (Result<List<Pair<String, ByteArray>>>) -> Unit,
) {
    private var session: CameraCaptureSession? = null
    private var readers = emptyList<ImageReader>()
    private val pair = DualStillPair<YuvFrame>()
    private var finished = false
    private var closing = false
    private var configuring = false
    private var outcome: Result<List<Pair<String, ByteArray>>>? = null
    private val ids = listOf(plan.first.id, plan.second.id)
    private val timeout = Runnable { finish(Result.failure(IllegalStateException("두 센서 사진 수신 시간 초과: ${pair.diagnostic()}"))) }

    fun start() {
        handler.postDelayed(timeout, 10_000)
        try {
            val sizes = ids.map { id -> manager.getCameraCharacteristics(id)[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
                ?.getOutputSizes(ImageFormat.YUV_420_888)?.map { LiveSize(it.width, it.height) }.orEmpty() }
            val size = DualPreviewPlanner.commonSizes(sizes[0], sizes[1]).firstOrNull()
                ?: error("두 센서의 공통 YUV 사진 크기가 없습니다.")
            ids.forEachIndexed { index, _ ->
                val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
                // Retain each reader immediately so a later allocation/listener failure closes earlier ones.
                readers = readers + reader
                reader.apply {
                    setOnImageAvailableListener({ reader ->
                        if (closing) return@setOnImageAvailableListener
                        try {
                            reader.acquireNextImage()?.use { image ->
                                telemetry?.image(sessionId, image.timestamp, image.width, image.height, image.format,
                                    if (index == 0) "photo_main" else "photo_sub")
                                val crop = image.cropRect
                                val bytes = YuvPacking.nv21(image.planes.map { YuvPacking.Plane(it.buffer, it.rowStride, it.pixelStride) },
                                    crop.left, crop.top, crop.width(), crop.height())
                                pair.image(index, image.timestamp, YuvFrame(bytes, crop.width(), crop.height()))
                            }
                            completePair()
                        } catch (e: Exception) { finish(Result.failure(e)) }
                    }, handler)
                }
            }
            val outputs = readers.mapIndexed { index, reader -> OutputConfiguration(reader.surface).apply { setPhysicalCameraId(ids[index]) } }
            configuring = true
            camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, Executor { handler.post(it) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        configuring = false
                        session = s
                        if (closing) { s.close(); return }
                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE, ids.toSet()).apply {
                                readers.forEach { addTarget(it.surface) }
                                setTag("dual_photo")
                                controls.apply(camera, this, false)
                            }.build()
                            s.capture(request, object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureStarted(s: CameraCaptureSession, r: CaptureRequest, t: Long, f: Long) {
                                    callback?.onCaptureStarted(s, r, t, f)
                                }
                                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                                    callback?.onCaptureCompleted(s, r, result)
                                    if (closing) return
                                    val timestamps = ids.map { result.physicalCameraResults[it]?.get(CaptureResult.SENSOR_TIMESTAMP) }
                                    if (timestamps.any { it == null }) { finish(Result.failure(IllegalStateException("두 센서 촬영 결과가 누락되었습니다."))); return }
                                    telemetry?.recorder?.record(sessionId, "dual_physical_result", result.frameNumber,
                                        values = mapOf("timestamps" to ids.zip(timestamps).toMap()))
                                    pair.result(timestamps[0]!!, timestamps[1]!!, result[CaptureResult.SENSOR_TIMESTAMP])
                                    completePair()
                                }
                                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                                    callback?.onCaptureFailed(s, r, f)
                                    finish(Result.failure(IllegalStateException("두 센서 촬영 실패: ${f.reason}")))
                                }
                            }, handler)
                        } catch (e: Exception) { finish(Result.failure(e)) }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        configuring = false
                        session = s
                        if (closing) { s.close(); return }
                        finish(Result.failure(IllegalStateException("두 센서 동시 사진 출력 조합을 지원하지 않습니다.")))
                    }
                    override fun onClosed(s: CameraCaptureSession) { release() }
                }))
        } catch (e: Exception) { configuring = false; finish(Result.failure(e)) }
    }

    private fun completePair() {
        val frames = pair.ready() ?: return
        if (closing) return
        finish(runCatching {
            listOf(frames.first, frames.second).mapIndexed { index, frame ->
                val sensor = manager.getCameraCharacteristics(ids[index])[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90
                ids[index] to encodeYuvStill(frame, (sensor - displayDegrees + 360) % 360)
            }
        })
    }

    fun cancel() = finish(Result.failure(IllegalStateException("촬영이 취소되었습니다.")))
    private fun finish(result: Result<List<Pair<String, ByteArray>>>) {
        if (closing) return
        closing = true; outcome = result
        handler.removeCallbacks(timeout)
        val current = session
        if (current != null) current.close() else if (!configuring) release()
    }
    private fun release() {
        if (finished) return
        finished = true
        readers.forEach { it.close() }; readers = emptyList()
        done(outcome ?: Result.failure(IllegalStateException("촬영 세션이 종료되었습니다.")))
    }
}
