package dev.halcamera.camera

import android.os.Handler
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import dev.halcamera.telemetry.Telemetry
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The photo taken while a CameraX LIVE recording runs (#175), the counterpart of [Camera2VideoSnapshot]. The
 * recording session binds ImageCapture next to VideoCapture; CameraX then takes the JPEG through the video
 * snapshot path of the same session. When the camera refuses that combination the engine binds the recording alone
 * and [Host.imageCapture] stays null, so nothing here runs.
 *
 * One snapshot at a time, JPEG only (the analysis stream is unbound while recording, so there is no YUV half). A
 * failure only reports through [Host.notice]; the recording goes on. Main thread.
 */
internal class CameraXVideoSnapshot(
    private val main: Handler,
    private val mainExecutor: Executor,
    private val telemetry: Telemetry,
    private val sessionId: String,
    private val library: MediaLibrary,
    private val mediaIo: Executor,
    private val host: Host,
) {
    interface Host {
        /** The ImageCapture bound with the recording, or null when the recording runs without a photo use case. */
        val imageCapture: ImageCapture?
        val active: Boolean
        val flashName: String
        val zoomRequested: Float
        val snapshotStream: String
        fun updateRotation()
        fun notice(text: String)
    }

    private val claimed = AtomicBoolean(false)
    val inFlight: Boolean get() = claimed.get()

    fun capture(requestId: String?, done: (Result<PhotoResult>) -> Unit) {
        val useCase = host.imageCapture
        if (useCase == null || !host.active) { done(Result.failure(IllegalStateException("녹화 중 사진을 찍을 수 있는 상태가 아닙니다"))); return }
        if (!claimed.compareAndSet(false, true)) { done(Result.failure(IllegalStateException("앞의 사진을 저장하는 중입니다"))); return }
        val name = library.name()
        val answered = AtomicBoolean(false)
        fun finish(result: Result<PhotoResult>, notice: String) {
            if (!answered.compareAndSet(false, true)) return
            claimed.set(false)
            main.post { done(result); host.notice(notice) }
            result.exceptionOrNull()?.let { telemetry.event(sessionId, "video_snapshot_failed", mapOf("message" to it.message)) }
        }
        host.updateRotation()
        telemetry.event(sessionId, "video_snapshot_submit", mapOf("api" to "ImageCapture.takePicture", "flash" to host.flashName,
            "zoomRequested" to host.zoomRequested, "size" to useCase.resolutionInfo?.resolution?.toString()))
        try {
            useCase.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    // A photo that arrives after the timeout was already answered as failed; saving it now would
                    // contradict that answer, as Camera2 drops the same late image.
                    if (answered.get()) { image.close(); return }
                    val timestamp = image.imageInfo.timestamp
                    val bytes = try {
                        if (host.active) telemetry.image(sessionId, timestamp, image.width, image.height, image.format, host.snapshotStream)
                        val buffer = image.planes[0].buffer
                        ByteArray(buffer.remaining()).also { buffer.get(it) }
                    } catch (e: Exception) { finish(Result.failure(e), "녹화 중 사진 실패: ${e.message}"); return } finally { image.close() }
                    try {
                        mediaIo.execute {
                            val saved = runCatching { library.savePhotos(name, null, bytes) }
                            saved.onSuccess { telemetry.event(sessionId, "media_saved", mapOf("sensorTimestamp" to timestamp,
                                "source" to "video_snapshot", "uris" to it.map { uri -> uri.toString() })) }
                            finish(saved.map { PhotoResult(requestId, name, timestamp, it) },
                                saved.fold({ "녹화 중 JPEG 사진을 저장했습니다" }, { "녹화 중 사진 저장 실패: ${it.message}" }))
                        }
                    } catch (e: RejectedExecutionException) { finish(Result.failure(e), "녹화 중 사진 실패: 카메라가 닫혀 저장하지 못했습니다") }
                }
                override fun onError(exception: ImageCaptureException) =
                    finish(Result.failure(exception), "녹화 중 사진 실패: ${exception.message}")
            })
        } catch (e: Exception) { finish(Result.failure(e), "녹화 중 사진 실패: ${e.message}"); return }
        main.postDelayed({ finish(Result.failure(IllegalStateException("5초 안에 사진이 오지 않았습니다")), "녹화 중 사진 실패: 5초 안에 오지 않았습니다") }, TIMEOUT_MS)
    }

    private companion object { const val TIMEOUT_MS = 5000L }
}
