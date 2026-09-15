package dev.halcamera.compat

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.CamcorderProfile
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Performs CTS `RecordingTest#testBasicRecording` on the device: for every camera and every CamcorderProfile
 * quality CTS lists, open the camera, record RECORDING_DURATION_MS through MediaRecorder with a preview at
 * the video size, and let [BasicRecordingRules] judge the file. Blocking; call from a worker thread.
 *
 * One deliberate difference from CTS: an assertion there aborts the whole test method, so a failing profile
 * hides the profiles after it. Here every profile gets its own verdict; a camera FAILs if any profile does.
 *
 * Camera and MediaRecorder timeouts are the CameraTestUtils values (3 s each). The preview surface comes from
 * [PreviewHost], which the activity implements with a SurfaceView the way Camera2SurfaceViewCtsActivity does.
 */
class BasicRecordingRunner(
    private val manager: CameraManager,
    private val previewHost: PreviewHost,
    private val outputDir: File,
    private val windowWidth: Int,
    private val windowHeight: Int,
    private val listener: Listener
) {
    interface PreviewHost {
        /** Resize the preview buffer to [size] and return its Surface once the change is reported, or null on timeout. */
        fun acquirePreview(size: Dim, timeoutMs: Long): Surface?
    }

    interface Listener {
        fun onCameraStarted(cameraId: String, index: Int, total: Int)
        /** A profile is about to record; [index] and [total] count the profiles planned for this camera. */
        fun onProfileStarted(cameraId: String, quality: String, index: Int, total: Int)
        fun onStep(cameraId: String, step: StepResult)
        fun onFinished(report: CaseReport)
    }

    private val cancelled = AtomicBoolean(false)
    private val thread = HandlerThread("cts-recording").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    fun cancel() { cancelled.set(true) }

    fun run() {
        val cameras = ArrayList<CameraCaseResult>()
        try {
            val ids = manager.cameraIdList.toList()
            ids.forEachIndexed { index, id ->
                if (cancelled.get()) return@forEachIndexed
                listener.onCameraStarted(id, index, ids.size)
                cameras += runCamera(id)
            }
        } catch (e: Exception) {
            Log.w(TAG, "run aborted", e)
            cameras += CameraCaseResult("-", listOf(StepResult("run", Verdict.FAIL, listOf("${e.javaClass.simpleName}: ${e.message}"))))
        } finally {
            thread.quitSafely()
        }
        listener.onFinished(CaseReport(BasicRecordingRules.SOURCE, cameras, cancelled.get()))
    }

    private fun step(cameraId: String, id: String, verdict: Verdict, details: List<String>): StepResult =
        StepResult(id, verdict, details).also { listener.onStep(cameraId, it) }

    private fun runCamera(cameraId: String): CameraCaseResult {
        val steps = ArrayList<StepResult>()
        val chars = try { manager.getCameraCharacteristics(cameraId) } catch (e: Exception) {
            steps += step(cameraId, "camera", Verdict.FAIL, listOf("characteristics unreadable: ${e.message}"))
            return CameraCaseResult(cameraId, steps)
        }
        val numericId = cameraId.toIntOrNull()
        if (numericId == null) {
            steps += step(cameraId, "camera", Verdict.SKIP, listOf("CamcorderProfile needs a numeric camera id"))
            return CameraCaseResult(cameraId, steps)
        }
        val profiles = readProfiles(numericId)
        val info = cameraInfo(cameraId, chars, profiles.keys)
        BasicRecordingRules.cameraSkipReason(info)?.let {
            steps += step(cameraId, "camera", Verdict.SKIP, listOf(it))
            return CameraCaseResult(cameraId, steps)
        }
        val plans = BasicRecordingRules.plan(info, profiles)

        var camera: OpenedCamera? = null
        var recorder: MediaRecorder? = null
        try {
            camera = openCamera(cameraId)
            recorder = MediaRecorder()
            val toRecord = plans.count { it.verdict == null }
            var recorded = 0
            for (plan in plans) {
                if (cancelled.get()) break
                val id = BasicRecordingRules.qualityName(plan.quality)
                if (plan.verdict != null) { steps += step(cameraId, id, plan.verdict, plan.details); continue }
                val profile = plan.profile!!
                listener.onProfileStarted(cameraId, id, recorded++, toRecord)
                val result = try {
                    recordAndValidate(camera.device, recorder, info, profile, plan.preview!!)
                } catch (e: Exception) {
                    Log.w(TAG, "camera $cameraId $id failed", e)
                    runCatching { recorder.reset() }
                    Verdict.FAIL to listOf("${e.javaClass.simpleName}: ${e.message}")
                }
                steps += step(cameraId, id, result.first, result.second)
            }
            BasicRecordingRules.frameRateFloorFailure(profiles)?.let { steps += step(cameraId, "frame_rate_floor", Verdict.FAIL, listOf(it)) }
        } catch (e: Exception) {
            steps += step(cameraId, "camera", Verdict.FAIL, listOf("${e.javaClass.simpleName}: ${e.message}"))
        } finally {
            recorder?.release()
            camera?.close()
        }
        return CameraCaseResult(cameraId, steps)
    }

    // ---- what CTS reads before recording ----

    @Suppress("DEPRECATION")
    private fun readProfiles(cameraId: Int): Map<Int, BasicRecordingRules.Profile> {
        val out = LinkedHashMap<Int, BasicRecordingRules.Profile>()
        (BasicRecordingRules.PROFILE_ORDER + listOf(BasicRecordingRules.QUALITY_QHD, BasicRecordingRules.QUALITY_2K)).forEach { q ->
            if (runCatching { CamcorderProfile.hasProfile(cameraId, q) }.getOrDefault(false)) {
                val p = CamcorderProfile.get(cameraId, q)
                out[q] = BasicRecordingRules.Profile(q, Dim(p.videoFrameWidth, p.videoFrameHeight), p.videoFrameRate)
            }
        }
        return out
    }

    private fun cameraInfo(cameraId: String, chars: CameraCharacteristics, qualities: Set<Int>): BasicRecordingRules.CameraInfo {
        val map = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        val level = chars[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]
        val caps = chars[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.toSet() ?: emptySet()
        val previewBound = BasicRecordingRules.previewSizeBound(windowWidth, windowHeight)
        val previewSizes = map?.getOutputSizes(SurfaceHolder::class.java).orEmpty().map(::dim)
        val videoSizes = map?.getOutputSizes(MediaRecorder::class.java).orEmpty().map(::dim)
        val privateDurations = HashMap<Dim, Long>()
        map?.getOutputSizes(ImageFormat.PRIVATE).orEmpty().forEach { s ->
            runCatching { map?.getOutputMinFrameDuration(ImageFormat.PRIVATE, s) }.getOrNull()?.let { privateDurations[dim(s)] = it }
        }
        return BasicRecordingRules.CameraInfo(
            cameraId = cameraId,
            hasColorOutput = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE in caps,
            isExternal = level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL,
            isLegacy = level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
            orderedPreviewSizes = BasicRecordingRules.boundedDescending(previewSizes, previewBound),
            supportedVideoSizes = BasicRecordingRules.boundedDescending(videoSizes, BasicRecordingRules.videoSizeBound(qualities)),
            fpsRanges = chars[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES].orEmpty().map { it.lower to it.upper },
            privateMinFrameDurationNs = privateDurations
        )
    }

    // ---- one profile: prepareRecording, updatePreviewSurfaceWithVideo, startRecording, stopRecording, validateRecording ----

    private fun recordAndValidate(
        camera: CameraDevice, recorder: MediaRecorder, info: BasicRecordingRules.CameraInfo,
        profile: BasicRecordingRules.Profile, preview: Dim
    ): Pair<Verdict, List<String>> {
        val file = File(outputDir, "test_video.mp4")
        file.delete()
        @Suppress("DEPRECATION")
        val camcorder = CamcorderProfile.get(info.cameraId.toInt(), profile.quality)
        recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setProfile(camcorder)
        recorder.setOutputFile(file.absolutePath)
        recorder.prepare()
        val recordingSurface = recorder.surface ?: error("Recording surface must be non-null!")
        val previewSurface = previewHost.acquirePreview(preview, WAIT_FOR_SURFACE_CHANGE_TIMEOUT_MS)
            ?: error("wait for surface change to $preview timed out")
        check(previewSurface.isValid && recordingSurface.isValid) { "Both preview and recording surfaces should be valid" }

        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(profile.frameRate, profile.frameRate))
            addTarget(recordingSurface)
            addTarget(previewSurface)
        }.build()
        val frames = AtomicLong(0)
        var session: Pair<CameraCaptureSession, CountDownLatch>? = null
        try {
            session = configureSession(camera, listOf(previewSurface, recordingSurface), request)
            val firstStart = CountDownLatch(1)
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(s: CameraCaptureSession, r: CaptureRequest, timestamp: Long, frameNumber: Long) { firstStart.countDown() }
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) { frames.incrementAndGet() }
            }
            session.first.setRepeatingRequest(request, callback, handler)
            // Wait for the first capture start before starting mediaRecorder.
            check(firstStart.await(CAPTURE_RESULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Timeout waiting for the first capture start" }
            recorder.start()
            SystemClock.sleep(BasicRecordingRules.RECORDING_DURATION_MS)

            // stopRecording: stop streaming and wait for the session to close, then stop the recorder.
            session.first.close()
            check(session.second.await(SESSION_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Timeout waiting for the session to close" }
            session = null
            recorder.stop()
            recorder.reset()
        } finally {
            // On an error path the session is still open: close it so the next profile starts clean.
            session?.first?.close()
            recordingSurface.release()
        }

        val recording = readRecording(file, frames.get())
        val failures = BasicRecordingRules.validate(info.cameraId, info.isLegacy, profile, recording)
        file.delete()
        val summary = BasicRecordingRules.summary(profile, recording)
        return if (failures.isEmpty()) Verdict.PASS to listOf(summary) else Verdict.FAIL to (listOf(summary) + failures)
    }

    /** CameraTestUtils.configureCameraSessionWithParameters; the latch trips on onClosed. */
    private fun configureSession(camera: CameraDevice, surfaces: List<Surface>, initial: CaptureRequest): Pair<CameraCaptureSession, CountDownLatch> {
        val configured = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val ref = AtomicReference<CameraCaptureSession?>()
        val failed = AtomicBoolean(false)
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) { ref.set(session); configured.countDown() }
            override fun onConfigureFailed(session: CameraCaptureSession) { failed.set(true); configured.countDown() }
            override fun onClosed(session: CameraCaptureSession) { closed.countDown() }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, surfaces.map { OutputConfiguration(it) }, executor, callback)
            config.sessionParameters = initial
            camera.createCaptureSession(config)
        } else {
            // API 26-27 has no session parameters; the fps range still travels in the repeating request.
            @Suppress("DEPRECATION")
            camera.createCaptureSession(surfaces, callback, handler)
        }
        check(configured.await(SESSION_CONFIGURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Timeout waiting for the session to configure" }
        check(!failed.get()) { "Camera session configuration failed" }
        return ref.get()!! to closed
    }

    private fun readRecording(file: File, framesProduced: Long): BasicRecordingRules.Recording {
        if (!file.exists()) return BasicRecordingRules.Recording(false, null, 0, emptyList(), framesProduced)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var size: Dim? = null
            var durationUs = 0L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.contains("video")) {
                    durationUs = format.getLong(MediaFormat.KEY_DURATION)
                    size = Dim(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT))
                    extractor.selectTrack(i)
                    break
                }
            }
            if (size == null) return BasicRecordingRules.Recording(true, null, 0, emptyList(), framesProduced)
            val times = ArrayList<Long>()
            while (true) {
                times += extractor.sampleTime
                if (!extractor.advance()) break
            }
            return BasicRecordingRules.Recording(true, size, durationUs, times, framesProduced)
        } finally {
            extractor.release()
        }
    }

    // ---- camera open and close with the CTS timeouts ----

    /** A device plus the latch its StateCallback trips on onClosed, so close() can wait the CTS close budget. */
    private class OpenedCamera(val device: CameraDevice, private val closed: CountDownLatch) {
        fun close() {
            device.close()
            if (!closed.await(CAMERA_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) Log.w(TAG, "camera ${device.id} did not report closed in time")
        }
    }

    /** The activity has checked CAMERA before starting the run; a revoked permission surfaces as SecurityException in the step. */
    @SuppressLint("MissingPermission")
    private fun openCamera(cameraId: String): OpenedCamera {
        val latch = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val ref = AtomicReference<CameraDevice?>()
        val error = AtomicReference<String?>()
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) { ref.set(camera); latch.countDown() }
            override fun onClosed(camera: CameraDevice) { closed.countDown() }
            override fun onDisconnected(camera: CameraDevice) { error.compareAndSet(null, "camera disconnected"); camera.close(); latch.countDown() }
            override fun onError(camera: CameraDevice, code: Int) { error.compareAndSet(null, "camera error $code"); camera.close(); latch.countDown() }
        }, handler)
        check(latch.await(CAMERA_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Timeout waiting for the camera to open" }
        error.get()?.let { error(it) }
        return OpenedCamera(ref.get()!!, closed)
    }

    private fun dim(size: Size) = Dim(size.width, size.height)

    companion object {
        private const val TAG = "BasicRecordingRunner"
        const val CAMERA_OPEN_TIMEOUT_MS = 3000L
        const val SESSION_CONFIGURE_TIMEOUT_MS = 3000L
        const val SESSION_CLOSE_TIMEOUT_MS = 3000L
        const val CAPTURE_RESULT_TIMEOUT_MS = 3000L
        const val WAIT_FOR_SURFACE_CHANGE_TIMEOUT_MS = 1000L
        const val CAMERA_CLOSE_TIMEOUT_MS = 3000L
    }
}
