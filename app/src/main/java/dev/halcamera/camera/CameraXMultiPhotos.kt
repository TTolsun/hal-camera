package dev.halcamera.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import dev.halcamera.telemetry.Telemetry
import java.util.UUID
import java.util.concurrent.Executors

/** Native JPEG bytes retain their EXIF; grouping/publication use the same contract as Camera2 Multi. */
internal class CameraXMultiPhotos(
    context: Context, private val plan: ConcurrentPlan, private val main: Handler,
    private val telemetry: Telemetry, private val sessionId: String,
    private val listener: MultiSession.Listener,
) {
    val captures = plan.streams.associate { it.camera.id to pipImageCapture(it.photo) }
    private val store = ConcurrentPhotoStore(context)
    private val sources = plan.streams.associate { it.camera.id to context.getSystemService(CameraManager::class.java)
        .getCameraCharacteristics(it.camera.id)[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE] }
    private val io = Executors.newSingleThreadExecutor()
    private var group: ConcurrentCapture<ConcurrentPhoto>? = null
    private var saving = false
    private var closing = false
    val busy get() = group != null || saving

    fun capture(degrees: Int) {
        if (busy || closing) return
        val next = ConcurrentCapture<ConcurrentPhoto>("HAL_concurrent_${UUID.randomUUID()}", SystemClock.elapsedRealtimeNanos(), captures.keys.toList())
        group = next
        captures.forEach { (id, capture) ->
            capture.targetRotation = when (degrees) { 90 -> Surface.ROTATION_90; 180 -> Surface.ROTATION_180; 270 -> Surface.ROTATION_270; else -> Surface.ROTATION_0 }
            try { capture.takePicture({ main.post(it) }, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        if (group !== next) return
                        telemetry.image("${sessionId}_$id",image.imageInfo.timestamp,image.width,image.height,image.format,"photo")
                        val buffer = image.planes[0].buffer
                        settle(next,id,Result.success(ConcurrentPhoto(ByteArray(buffer.remaining()).also { buffer.get(it) }, image.imageInfo.timestamp, null)))
                    } catch (e: Exception) { settle(next,id,Result.failure(e)) }
                    finally { image.close() }
                }
                override fun onError(exception: ImageCaptureException) = settle(next,id,Result.failure(exception))
            }) } catch (e: Exception) { settle(next,id,Result.failure(e)) }
        }
        main.postDelayed({ if (group === next) { next.cancel("Photo timed out"); save(next) } },10_000)
    }

    private fun settle(next: ConcurrentCapture<ConcurrentPhoto>, id: String, value: Result<ConcurrentPhoto>) {
        if (group !== next) return
        next.settle(id,value)
        if (next.complete) save(next)
    }

    private fun save(next: ConcurrentCapture<ConcurrentPhoto>) {
        group = null; saving = true
        io.execute {
            var photo: PhotoResult? = null
            val result = runCatching { store.save(next,plan,sources) { photo = it } }
            main.post {
                saving = false
                listener.onPhoto(result.getOrElse { ConcurrentMediaResult("Could not save photos: ${it.message}",true) })
                listener.onPhotoResult(photo?.let { Result.success(it) } ?: Result.failure(IllegalStateException(result.exceptionOrNull()?.message ?: "No photo saved")))
            }
        }
    }

    fun close(done: () -> Unit) {
        closing = true
        group?.let { it.cancel("Camera closed"); save(it) }
        io.execute { main.post(done) }; io.shutdown()
    }
}
