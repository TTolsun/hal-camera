package dev.halcamera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import dev.halcamera.telemetry.FlightRecorder
import dev.halcamera.telemetry.Telemetry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Opt-in hardware check. Keeps generated files for inspection; never deletes existing media. */
object JpegSourceCheck {
    @Suppress("MissingPermission", "DEPRECATION")
    fun run(context: Context): String {
        val manager = context.getSystemService(CameraManager::class.java)
        val c = manager.getCameraCharacteristics("0")
        val thread = HandlerThread("jpeg-source-check").apply { start() }
        val handler = Handler(thread.looper)
        val io = Executors.newSingleThreadExecutor()
        val device = AtomicReference<CameraDevice?>()
        val sessionRef = AtomicReference<CameraCaptureSession?>()
        val failure = AtomicReference<Throwable?>()
        val configured = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val yuv = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 3)
        val jpeg = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 3)
        var useYuv = true
        var useJpeg = true
        var cameraActive = true
        val telemetry = Telemetry(FlightRecorder(SystemClock::elapsedRealtimeNanos))
        val observed = java.util.concurrent.ConcurrentHashMap<Long, Long>()
        val idle = AtomicReference(CountDownLatch(0))
        val still = Camera2StillCapture(context, handler, Handler(Looper.getMainLooper()), telemetry,
            "jpeg-source-test", MediaLibrary(context), io, false, object : Camera2StillCapture.Host {
                override val camera get() = device.get()
                override val session get() = sessionRef.get()
                override val characteristics get() = c
                override val active get() = cameraActive
                override val recordingBusy = false
                override val captureYuv get() = useYuv
                override val captureJpeg get() = useJpeg
                override val captureRaw = false
                override val needsPrecapture = false
                override val flashName = "OFF"
                override val zoomRequested = 1f
                override val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                        result[CaptureResult.SENSOR_TIMESTAMP]?.let { observed[it] = result[CaptureResult.SENSOR_EXPOSURE_TIME] ?: -1 }
                    }
                }
                override fun orientation(c: CameraCharacteristics) = 0
                override fun stillRequest(camera: CameraDevice, c: CameraCharacteristics, tag: String, rotation: Int?): CaptureRequest =
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        if (useYuv) addTarget(yuv.surface)
                        if (useJpeg) addTarget(jpeg.surface)
                        setTag(tag)
                    }.build()
                override fun precaptureTrigger(callback: CameraCaptureSession.CaptureCallback) = false
                override fun report(message: String, ok: Boolean) {
                    if (message.startsWith("Saved ")) idle.get().countDown()
                }
                override fun fail(e: Exception) { failure.set(e) }
            })
        yuv.setOnImageAvailableListener({ reader ->
            reader.acquireNextImage()?.use { image ->
                try { still.onImage(image, ImageFormat.YUV_420_888, "analysis") } catch (e: Exception) { still.onImageFailed(e) }
            }
        }, handler)
        jpeg.setOnImageAvailableListener({ reader ->
            reader.acquireNextImage()?.use { image ->
                try { still.onImage(image, ImageFormat.JPEG, "still") } catch (e: Exception) { still.onImageFailed(e) }
            }
        }, handler)
        val artifacts = mutableListOf<String>()
        try {
            manager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device.set(camera)
                    camera.createCaptureSession(listOf(yuv.surface, jpeg.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { sessionRef.set(s); configured.countDown() }
                        override fun onConfigureFailed(s: CameraCaptureSession) { failure.set(IllegalStateException("Configuration rejected")); configured.countDown() }
                    }, handler)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); configured.countDown() }
                override fun onError(camera: CameraDevice, error: Int) { failure.set(IllegalStateException("Camera error $error")); camera.close(); configured.countDown() }
                override fun onClosed(camera: CameraDevice) { closed.countDown() }
            }, handler)
            check(configured.await(10, TimeUnit.SECONDS)) { "Camera configuration timed out" }
            failure.get()?.let { throw it }
            val cases = listOf(true to false, false to true)
            cases.forEachIndexed { index, (yuvEnabled, jpegEnabled) ->
                val done = CountDownLatch(1)
                val result = AtomicReference<Result<PhotoResult>?>()
                idle.set(CountDownLatch(1))
                handler.post {
                    useYuv = yuvEnabled; useJpeg = jpegEnabled
                    still.capture("format-test-$index") { result.set(it); done.countDown() }
                }
                check(done.await(15, TimeUnit.SECONDS)) { "Capture timed out" }
                val photo = result.get()!!.getOrThrow()
                val expectedImages = (if (yuvEnabled) 1 else 0) + (if (jpegEnabled) 1 else 0)
                check(photo.artifacts.size == expectedImages + 1)
                check(photo.artifacts.none { it.mime == "application/zip" })
                check(photo.artifacts.count { it.name.endsWith("_YUV.jpg") } ==
                    if (yuvEnabled) 1 else 0)
                check(photo.artifacts.count { it.name.endsWith("_JPEG.jpg") } == if (jpegEnabled) 1 else 0)
                val sidecar = photo.artifacts.single { it.mime == "application/json" }
                val metadata = context.contentResolver.openInputStream(sidecar.uri)!!.use {
                    JSONObject(it.readBytes().toString(Charsets.UTF_8))
                }
                val capture = metadata.getJSONObject("capture")
                check(capture.getLong("sensorTimestampNs") == photo.sensorTimestamp)
                check(capture.getLong("exposureTimeNs") == observed[photo.sensorTimestamp])
                val outputs = metadata.getJSONArray("outputs")
                check(outputs.length() == expectedImages)
                artifacts += photo.artifacts.joinToString { it.name }
                check(idle.get().await(2, TimeUnit.SECONDS)) { "Save never released BUSY" }
            }
            return "JPEG_SOURCES_OK: app YUV JPEG and camera JPEG, JSON timestamp/exposure verified.\n${artifacts.joinToString("\n")}"
        } finally {
            handler.post { cameraActive = false; still.close(); sessionRef.get()?.close(); device.get()?.close() }
            closed.await(5, TimeUnit.SECONDS)
            io.shutdown(); io.awaitTermination(10, TimeUnit.SECONDS)
            yuv.close(); jpeg.close(); thread.quitSafely()
        }
    }
}
