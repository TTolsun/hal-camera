package dev.halcamera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import dev.halcamera.telemetry.FlightRecorder
import dev.halcamera.telemetry.Telemetry
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Injects one configure failure, then uses the actual Camera2LiveRecorder to configure and save real video. */
object Camera2SnapshotFallbackCheck {
    @Suppress("MissingPermission", "DEPRECATION")
    fun run(context: Context, library: MediaLibrary): String {
        val thread = HandlerThread("snapshot-fallback-test").apply { start() }
        val handler = Handler(thread.looper)
        val main = Handler(Looper.getMainLooper())
        val io = Executors.newSingleThreadExecutor()
        val manager = context.getSystemService(CameraManager::class.java)
        val cameraCharacteristics = manager.getCameraCharacteristics("0")
        val opened = CountDownLatch(1)
        val previewReady = CountDownLatch(1)
        var previewRestored = CountDownLatch(1)
        val started = CountDownLatch(1)
        val saved = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val result = AtomicReference<Result<Uri>?>()
        val cameraRef = AtomicReference<CameraDevice?>()
        val sessionRef = AtomicReference<CameraCaptureSession?>()
        val reader = ImageReader.newInstance(640, 480, ImageFormat.PRIVATE, 3)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
        val output = ConfiguredOutput(OutputDescriptor("preview", OutputKind.PREVIEW, true), reader.surface)
        val notices = Collections.synchronizedList(mutableListOf<String>())
        val configurations = Collections.synchronizedList(mutableListOf<Int>())
        var cameraActive = true
        val telemetry = Telemetry(FlightRecorder(SystemClock::elapsedRealtimeNanos))
        fun preview(latch: CountDownLatch) {
            val camera = cameraRef.get() ?: return
            camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    sessionRef.set(session)
                    session.setRepeatingRequest(camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(reader.surface)
                    }.build(), null, handler)
                    latch.countDown()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    error.set(IllegalStateException("Test preview refused")); latch.countDown()
                }
            }, handler)
        }
        lateinit var recorder: Camera2LiveRecorder
        recorder = Camera2LiveRecorder(context, handler, main, telemetry, "fallback-test", library, io,
            object : Camera2LiveRecorder.Host {
                override val camera get() = cameraRef.get()
                override val session get() = sessionRef.get()
                override val characteristics: CameraCharacteristics get() = cameraCharacteristics
                override val active get() = cameraActive
                override val benchmark = false
                override val settings = LiveVideo(LiveSize(1280, 720), 30, "H264")
                override val previewOutput = output
                override val stillInFlight = false
                override val snapshotDisabled = false
                override val requestedJpeg = LiveSize(1280, 720)
                override val captureCallback = object : CameraCaptureSession.CaptureCallback() {}
                override fun orientation(c: CameraCharacteristics) = 0
                override fun orientationHint(c: CameraCharacteristics) = 0
                override fun chooseSize(sizes: Array<Size>, maxPixels: Long) = sizes.first()
                override fun snapshotRequest(camera: CameraDevice, c: CameraCharacteristics, tag: String, rotation: Int): CaptureRequest =
                    error("No snapshot request may be submitted after fallback")
                override fun onSessionConfigured(session: CameraCaptureSession?) { sessionRef.set(session) }
                override fun startRepeating(camera: CameraDevice, session: CameraCaptureSession, c: CameraCharacteristics,
                    outputs: StreamConfiguration<Surface>) {
                    session.setRepeatingRequest(camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        outputs.repeating.filter { recorder.recording || it.descriptor.kind != OutputKind.RECORDING }.forEach { addTarget(it.target) }
                    }.build(), null, handler)
                }
                override fun rebuildPreview() { if (cameraActive) preview(previewRestored) }
                override fun recordingState(recording: Boolean) = Unit
                override fun status(message: String, ok: Boolean) = Unit
                override fun report(message: String, ok: Boolean) = Unit
                override fun notice(text: String) { notices += text }
                override fun fail(e: Exception) { error.set(e); started.countDown() }
            }, createSession = { camera, outputs, callback, callbackHandler ->
                configurations += outputs.size
                val inject = configurations.size == 1
                camera.createCaptureSessionByOutputConfigurations(outputs, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        // Deliberately report the combined session as refused. The replacement is not intercepted.
                        if (inject) callback.onConfigureFailed(session) else callback.onConfigured(session)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) = callback.onConfigureFailed(session)
                    override fun onClosed(session: CameraCaptureSession) = callback.onClosed(session)
                }, callbackHandler)
            })
        try {
            manager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { cameraRef.set(camera); opened.countDown() }
                override fun onDisconnected(camera: CameraDevice) { error.set(IllegalStateException("Disconnected")); camera.close(); opened.countDown() }
                override fun onError(camera: CameraDevice, code: Int) { error.set(IllegalStateException("Camera error $code")); camera.close(); opened.countDown() }
                override fun onClosed(camera: CameraDevice) { closed.countDown() }
            }, handler)
            check(opened.await(10, TimeUnit.SECONDS)) { "Camera open timed out" }
            error.get()?.let { throw it }
            handler.post { preview(previewReady) }
            check(previewReady.await(10, TimeUnit.SECONDS)) { "Preview timed out" }
            error.get()?.let { throw it }
            recorder.prepare { recorder.start(false, { started.countDown() }, { result.set(it); saved.countDown() }) }
            check(started.await(15, TimeUnit.SECONDS)) { "Fallback recording did not start: ${error.get()}" }
            val callbackFinished = CountDownLatch(1)
            handler.post { callbackFinished.countDown() }
            check(callbackFinished.await(5, TimeUnit.SECONDS))
            error.get()?.let { throw it }
            check(configurations == listOf(3, 2)) { "Expected preview+encoder+JPEG then preview+encoder: $configurations" }
            check(recorder.snapshotStatus.reason == VideoSnapshotPlan.REFUSED_REASON)
            check(notices.contains(VideoSnapshotPlan.REFUSED_REASON))
            SystemClock.sleep(2000)
            recorder.stop()
            check(saved.await(15, TimeUnit.SECONDS)) { "Video save timed out" }
            check(result.get()?.isSuccess == true) { "Fallback recording failed: ${result.get()}" }
            val retriever = MediaMetadataRetriever()
            try {
                context.contentResolver.openFileDescriptor(result.get()!!.getOrThrow(), "r")!!.use {
                    retriever.setDataSource(it.fileDescriptor)
                    check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes")
                    check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) == "1280")
                    check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) == "720")
                    check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() >= 1000)
                }
            } finally { retriever.release() }
            check(previewRestored.await(10, TimeUnit.SECONDS)) { "Preview not restored" }
            check(!recorder.busy)
            previewRestored = CountDownLatch(1)
            val idleReady = CountDownLatch(1)
            recorder.prepare { idleReady.countDown() }
            check(idleReady.await(15, TimeUnit.SECONDS)) { "Idle encoder not prepared" }
            check(recorder.prepared && !recorder.busy && !recorder.recording)
            val encoder = Camera2LiveRecorder::class.java.getDeclaredField("recorder").apply { isAccessible = true }
            val idleEncoder = encoder.get(recorder) as android.media.MediaRecorder
            recorder.encoderFailed(idleEncoder, IllegalStateException("Injected prepared encoder failure"))
            check(previewRestored.await(10, TimeUnit.SECONDS)) { "Idle encoder failure did not restore preview" }
            check(!recorder.prepared && !recorder.busy && !recorder.recording)
            check(encoder.get(recorder) == null && recorder.surface == null)
            return "camera2_injected_configure_failure: outputs=3->2, video_saved, preview_restored; prepared_encoder_failure: released, preview_restored"
        } finally {
            handler.post {
                cameraActive = false
                sessionRef.get()?.close()
                recorder.finish()
                cameraRef.get()?.close()
            }
            closed.await(10, TimeUnit.SECONDS)
            reader.close()
            io.shutdown()
            io.awaitTermination(10, TimeUnit.SECONDS)
            thread.quitSafely()
            thread.join(5000)
        }
    }
}
