package dev.halcamera.camera

import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import dev.halcamera.telemetry.Telemetry
import java.util.concurrent.Executor

/** One real sensor output. Buffers are matched to its capture result, never to the composed preview. */
internal class PipPhotoReader(private val handler: Handler, private val main: Handler,
    private val telemetry: Telemetry, private val sessionId: String, private val library: MediaLibrary,
    private val io: Executor, val descriptor: OutputDescriptor, size: LiveSize,
    private val rotation: () -> Int) {
    private val format = if (descriptor.kind == OutputKind.JPEG) ImageFormat.JPEG else ImageFormat.YUV_420_888
    val reader = ImageReader.newInstance(size.width, size.height, format, 2)
    val output get() = ConfiguredOutput(descriptor, reader.surface)
    private class Pending(val name: String, val requestId: String?, val rotation: Int,
        val done: (Result<PhotoResult>) -> Unit) {
        val pair = StillPair<YuvFrame, ByteArray>(2)
        var metadata: Map<String, Any?>? = null
    }
    private var pending: Pending? = null
    private var closed = false
    val captureRotation get() = pending?.rotation ?: rotation()

    init {
        reader.setOnImageAvailableListener({ source ->
            try { source.acquireNextImage()?.use { image ->
                if (closed) return@use
                telemetry.image(sessionId, image.timestamp, image.width, image.height, image.format, descriptor.id)
                val photo = pending ?: return@use
                if (format == ImageFormat.JPEG) {
                    val buffer = image.planes[0].buffer
                    photo.pair.jpeg(image.timestamp, ByteArray(buffer.remaining()).also { buffer.get(it) })
                } else {
                    val crop = image.cropRect
                    val bytes = YuvPacking.nv21(image.planes.map { YuvPacking.Plane(it.buffer, it.rowStride, it.pixelStride) },
                        crop.left, crop.top, crop.width(), crop.height())
                    photo.pair.yuv(image.timestamp, YuvFrame(bytes, crop.width(), crop.height()))
                }
                save(photo)
            } } catch (e: Exception) { pending?.let { fail(it, e) } }
        }, handler)
    }

    /** Arm before the main request is submitted; its callback snapshots this generation. */
    fun begin(requestId: String?, done: (Result<PhotoResult>) -> Unit) {
        check(!closed && pending == null) { "PIP photo busy" }
        val photo = Pending(BracketFiles.named(library.name(), requestId) + "_PIP_SOURCE", requestId, rotation(), done)
        pending = photo
        handler.postDelayed({ if (pending === photo) fail(photo, IllegalStateException("PIP photo timed out")) }, 5000)
    }

    fun callback(base: CameraCaptureSession.CaptureCallback, accepts: (CaptureRequest) -> Boolean = { true }): CameraCaptureSession.CaptureCallback {
        val photo = pending
        return object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) =
                base.onCaptureStarted(session, request, timestamp, frameNumber)
            override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, result: CaptureResult) =
                base.onCaptureProgressed(session, request, result)
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                base.onCaptureCompleted(session, request, result)
                if (photo == null || pending !== photo || !accepts(request)) return
                val actual = if (descriptor.physicalId == null) result else
                    if (android.os.Build.VERSION.SDK_INT >= 28) result.physicalCameraResults[descriptor.physicalId] else null
                val sensor = actual?.get(CaptureResult.SENSOR_TIMESTAMP) ?: result[CaptureResult.SENSOR_TIMESTAMP]
                if (sensor == null) { fail(photo, IllegalStateException("No PIP sensor timestamp")); return }
                photo.pair.timestamp = sensor
                photo.metadata = captureMetadata(telemetry, sessionId, sensor, photo.requestId) +
                    mapOf("physicalId" to descriptor.physicalId, "requestTag" to request.tag?.toString(), "pipSource" to true,
                        "resultStatus" to if (actual == null) "unavailable" else "matched", "frameNumber" to result.frameNumber,
                        "exposureTimeNs" to actual?.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                        "sensitivityIso" to actual?.get(CaptureResult.SENSOR_SENSITIVITY),
                        "frameDurationNs" to actual?.get(CaptureResult.SENSOR_FRAME_DURATION),
                        "aeState" to actual?.get(CaptureResult.CONTROL_AE_STATE),
                        "jpegSource" to if (format == ImageFormat.JPEG) "CAMERA" else "YUV",
                        "captureResult" to mapOf("exposureNs" to actual?.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                            "iso" to actual?.get(CaptureResult.SENSOR_SENSITIVITY),
                            "frameDurationNs" to actual?.get(CaptureResult.SENSOR_FRAME_DURATION),
                            "ae" to actual?.get(CaptureResult.CONTROL_AE_STATE),
                            "af" to actual?.get(CaptureResult.CONTROL_AF_STATE),
                            "awb" to actual?.get(CaptureResult.CONTROL_AWB_STATE),
                            "sensorTimestamp" to actual?.get(CaptureResult.SENSOR_TIMESTAMP)))
                save(photo)
            }
            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                base.onCaptureFailed(session, request, failure)
                if (photo != null && pending === photo && accepts(request))
                    fail(photo, IllegalStateException("PIP sensor capture failed: ${failure.reason}"))
            }
            override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: android.view.Surface, frameNumber: Long) {
                base.onCaptureBufferLost(session, request, target, frameNumber)
                if (photo != null && pending === photo && target === reader.surface)
                    fail(photo, IllegalStateException("PIP sensor buffer lost"))
            }
        }
    }

    private fun save(photo: Pending) {
        if (pending !== photo || photo.metadata == null) return
        val selected = photo.pair.selected(format == ImageFormat.YUV_420_888, format == ImageFormat.JPEG) ?: return
        val timestamp = checkNotNull(photo.pair.timestamp)
        val metadata = checkNotNull(photo.metadata)
        pending = null
        photo.pair.clear()
        io.execute {
            val saved = runCatching {
                val encoded = selected.first?.let { encodeYuvStill(it, photo.rotation) }
                val artifacts = library.saveCapture(photo.name, encoded, selected.second, metadata)
                PhotoResult(photo.requestId, photo.name, timestamp, artifacts.map { it.uri }, artifacts)
            }
            main.post { photo.done(saved) }
        }
    }

    private fun fail(photo: Pending, error: Exception) {
        if (pending !== photo) return
        pending = null; photo.pair.clear()
        main.post { photo.done(Result.failure(error)) }
    }

    fun abort(error: Exception) { pending?.let { fail(it,error) } }

    fun close() {
        if (closed) return
        closed = true
        pending?.let { fail(it, IllegalStateException("PIP photo closed")) }
        reader.close()
    }
}
