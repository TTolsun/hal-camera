package dev.halcamera.cts.snapshot

import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import dev.halcamera.cts.Camera2Ops
import dev.halcamera.cts.CameraCaseResult
import dev.halcamera.cts.CameraCaseRunner
import dev.halcamera.cts.CameraFacts
import dev.halcamera.cts.CaseEnvironment
import dev.halcamera.cts.Dim
import dev.halcamera.cts.JpegReader
import dev.halcamera.cts.StepResult
import dev.halcamera.cts.Verdict
import dev.halcamera.cts.recording.BasicRecordingRules
import dev.halcamera.cts.recording.CamcorderProfiles
import dev.halcamera.cts.recording.CamcorderRecording
import dev.halcamera.cts.snapshot.VideoSnapshotRules.Snapshot
import java.io.File
import java.util.Random

/**
 * Performs the VideoSnapshot case: per camera, one [VideoSnapshotRules.RECORDING_DURATION_MS] recording at the
 * profile [VideoSnapshotRules.chooseProfile] picks, with a JPEG reader in the same session and one
 * TEMPLATE_VIDEO_SNAPSHOT request at the planned moment targeting the preview, the recording and the reader,
 * as testVideoSnapshot does. The video and the snapshot are judged as two rows.
 */
class VideoSnapshotRunner(env: CaseEnvironment, private val random: Random = Random()) :
    CameraCaseRunner(env, VideoSnapshotRules.SOURCE, "cts-video-snapshot") {

    override fun runCamera(cameraId: String): CameraCaseResult {
        val steps = ArrayList<StepResult>()
        val chars = try { characteristics(cameraId) } catch (e: Exception) {
            steps += step(cameraId, "camera", Verdict.FAIL, listOf("characteristics unreadable: ${e.message}"))
            return CameraCaseResult(cameraId, steps)
        }
        val numericId = cameraId.toIntOrNull()
        if (numericId == null) {
            steps += step(cameraId, "camera", Verdict.SKIP, listOf("CamcorderProfile needs a numeric camera id"))
            return CameraCaseResult(cameraId, steps)
        }
        val profiles = CamcorderProfiles.read(numericId)
        val info = CameraFacts.recordingInfo(cameraId, chars, profiles.keys, env.windowWidth, env.windowHeight)
        BasicRecordingRules.cameraSkipReason(info)?.let {
            steps += step(cameraId, "camera", Verdict.SKIP, listOf(it))
            return CameraCaseResult(cameraId, steps)
        }
        val plan = VideoSnapshotRules.chooseProfile(info, profiles)
        if (plan == null) {
            steps += step(cameraId, "camera", Verdict.SKIP, VideoSnapshotRules.noProfileReason(info, profiles))
            return CameraCaseResult(cameraId, steps)
        }
        val profile = plan.profile!!
        val quality = BasicRecordingRules.qualityName(profile.quality)
        val snapshotSize = VideoSnapshotRules.snapshotSize(info.isLegacy, profile.size, CameraFacts.orderedStillSizes(chars))
        if (snapshotSize == null) {
            steps += step(cameraId, "camera", Verdict.SKIP, listOf("Camera $cameraId reports no JPEG output size"))
            return CameraCaseResult(cameraId, steps)
        }
        val plannedAt = VideoSnapshotRules.snapshotAtMs(random)
        listener.onProgress(cameraId, "$quality 녹화 · ${plannedAt / 1000}초에 스냅샷", 0, 1)

        val file = File(env.outputDir, "test_video.mp4")
        var camera: Camera2Ops.OpenedCamera? = null
        var recorder: MediaRecorder? = null
        var reader: JpegReader? = null
        var snapshot = Snapshot(plannedAt)
        try {
            camera = ops.open(cameraId)
            recorder = MediaRecorder()
            reader = JpegReader(snapshotSize.width, snapshotSize.height, ops.handler)
            val previewSurface = env.previewHost.acquirePreview(plan.preview!!, Camera2Ops.WAIT_FOR_SURFACE_CHANGE_TIMEOUT_MS)
                ?: error("wait for surface change to ${plan.preview} timed out")
            val recording = CamcorderRecording(ops, camera.device, recorder, CamcorderProfiles.get(numericId, profile.quality), file)
            val rec = recording.record(previewSurface, extraOutputs = listOf(reader.surface)) { live ->
                val startedAt = SystemClock.elapsedRealtimeNanos()
                if (sleepUnlessCancelled(plannedAt)) {
                    snapshot = try {
                        val request = camera.device.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT).apply {
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, recording.fpsRange)
                            addTarget(live.previewSurface)
                            addTarget(live.recordingSurface)
                            addTarget(reader.surface)
                        }.build()
                        val requestedAt = SystemClock.elapsedRealtimeNanos()
                        val still = ops.captureStill(live.session, request, reader)
                        val image = still.image
                        try {
                            snapshot.copy(
                                requestedAtMs = ms(startedAt, requestedAt),
                                captureMs = still.imageAtNs?.let { ms(still.requestedAtNs, it) },
                                resultReceived = still.resultReceived,
                                image = image?.let { JpegReader.describe(it) }
                            )
                        } finally {
                            image?.close()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "camera $cameraId video snapshot failed", e)
                        snapshot.copy(requestedAtMs = ms(startedAt, SystemClock.elapsedRealtimeNanos()), error = describe(e))
                    }
                }
                val remaining = VideoSnapshotRules.RECORDING_DURATION_MS - (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000
                if (remaining > 0) sleepUnlessCancelled(remaining)
            }
            if (cancelled.get()) {
                steps += step(cameraId, quality, Verdict.SKIP, listOf(CANCELLED))
                if (snapshot.requestedAtMs != null) judgeSnapshot(cameraId, snapshotSize, snapshot, steps)
                return CameraCaseResult(cameraId, steps)
            }
            val (videoVerdict, videoDetails) = VideoSnapshotRules.judgeVideo(cameraId, info.isLegacy, profile, rec, snapshotSize)
            steps += step(cameraId, quality, videoVerdict, videoDetails)
            judgeSnapshot(cameraId, snapshotSize, snapshot, steps)
        } catch (e: Exception) {
            // A cancel cuts the recording short; MediaRecorder may then refuse to stop, which is not a device fault.
            if (cancelled.get()) {
                steps += step(cameraId, quality, Verdict.SKIP, listOf(CANCELLED, describe(e)))
                return CameraCaseResult(cameraId, steps)
            }
            Log.w(TAG, "camera $cameraId video snapshot recording failed", e)
            steps += step(cameraId, quality, Verdict.FAIL, listOf(describe(e)))
            if (snapshot.requestedAtMs != null) judgeSnapshot(cameraId, snapshotSize, snapshot, steps)
        } finally {
            file.delete()
            recorder?.release()
            reader?.close()
            camera?.close()
        }
        return CameraCaseResult(cameraId, steps)
    }

    /** The snapshot row, judged the same way whether the recording ended cleanly or not. */
    private fun judgeSnapshot(cameraId: String, snapshotSize: Dim, snapshot: Snapshot, steps: MutableList<StepResult>) {
        val (verdict, details) = VideoSnapshotRules.judgeSnapshot(snapshotSize, snapshot)
        steps += step(cameraId, VideoSnapshotRules.SNAPSHOT_STEP_ID, verdict, details)
    }

    companion object {
        private const val TAG = "VideoSnapshotRunner"
        private const val CANCELLED = "Cancelled before the recording finished"
    }
}
