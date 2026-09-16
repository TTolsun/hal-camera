package dev.halcamera.cts.snapshot

import dev.halcamera.cts.Dim
import dev.halcamera.cts.OpenCycle
import dev.halcamera.cts.Verdict
import dev.halcamera.cts.combination.StillPreviewCombinationRules
import dev.halcamera.cts.recording.BasicRecordingRules
import dev.halcamera.cts.recording.BasicRecordingRules.CameraInfo
import dev.halcamera.cts.recording.BasicRecordingRules.Profile
import dev.halcamera.cts.recording.BasicRecordingRules.ProfilePlan
import dev.halcamera.cts.recording.BasicRecordingRules.Recording
import java.util.Random

/**
 * The decisions of the VideoSnapshot case, modelled on CTS `RecordingTest#testVideoSnapshot`: one
 * [RECORDING_DURATION_MS] recording per camera at its largest usable CamcorderProfile, with a video snapshot
 * (a JPEG through TEMPLATE_VIDEO_SNAPSHOT into the same session) at one random moment between
 * [SNAPSHOT_EARLIEST_MS] and [SNAPSHOT_LATEST_MS]. The video is held to validateRecording with the snapshot
 * test's frame-drop tolerance; the JPEG to validateImage.
 *
 * Differences from CTS: one profile and one snapshot per camera instead of every profile and several
 * snapshots, and a 25 s recording instead of 3 s so the snapshot lands well inside a running recording.
 * Pure Kotlin; [VideoSnapshotRunner] records on the device.
 */
object VideoSnapshotRules {
    const val SOURCE = "custom#VideoSnapshot"
    const val RECORDING_DURATION_MS = 25_000L
    const val SNAPSHOT_EARLIEST_MS = 5_000L
    const val SNAPSHOT_LATEST_MS = 20_000L
    /** videoSnapshotTestByCamera: FRAMEDROP_TOLERANCE as a rate in percent, widened for snapshots above FRAME_SIZE_15M. */
    const val FRAMEDROP_TOLERANCE = 8f
    const val FRAME_SIZE_15M = 15_000_000L
    const val FRAME_DROP_TOLERANCE_FACTOR = 1.5f
    const val SNAPSHOT_STEP_ID = "snapshot"

    /** The profile to record: the first in CTS order that testBasicRecording would record; null when none is. */
    fun chooseProfile(info: CameraInfo, profiles: Map<Int, Profile>): ProfilePlan? =
        BasicRecordingRules.plan(info, profiles).firstOrNull { it.verdict == null }

    /** Why nothing is recorded, for the SKIP row: the first plan's own reason, or the absence of any profile. */
    fun noProfileReason(info: CameraInfo, profiles: Map<Int, Profile>): List<String> {
        val plans = BasicRecordingRules.plan(info, profiles)
        val explained = plans.firstOrNull { it.profile != null } ?: plans.firstOrNull()
        return listOf("No CamcorderProfile can be recorded on camera ${info.cameraId}") + explained?.details.orEmpty()
    }

    /**
     * videoSnapshotTestByCamera's snapshot size: the largest JPEG size; on LEGACY the largest JPEG within the
     * video size, or the smallest JPEG when none fits.
     */
    fun snapshotSize(isLegacy: Boolean, videoSize: Dim, stillSizes: List<Dim>): Dim? {
        if (stillSizes.isEmpty()) return null
        if (!isLegacy) return stillSizes.first()
        return stillSizes.firstOrNull { it.width <= videoSize.width && it.height <= videoSize.height } ?: stillSizes.last()
    }

    /** One random moment in the snapshot window, inclusive of both ends. */
    fun snapshotAtMs(random: Random): Long = SNAPSHOT_EARLIEST_MS + (random.nextDouble() * (SNAPSHOT_LATEST_MS - SNAPSHOT_EARLIEST_MS)).toLong()

    fun frameDropTolerance(snapshot: Dim): Float =
        if (snapshot.area > FRAME_SIZE_15M) FRAMEDROP_TOLERANCE * FRAME_DROP_TOLERANCE_FACTOR else FRAMEDROP_TOLERANCE

    /** What the runner measured for the snapshot; a null phase was never reached. */
    data class Snapshot(
        val plannedAtMs: Long,
        val requestedAtMs: Double? = null,
        val captureMs: Double? = null,
        val resultReceived: Boolean = false,
        val image: StillPreviewCombinationRules.ImageInfo? = null,
        val error: String? = null
    )

    /** The video row: validateRecording with the snapshot tolerance, after the usual summary line. */
    fun judgeVideo(cameraId: String, isLegacy: Boolean, profile: Profile, rec: Recording, snapshot: Dim): Pair<Verdict, List<String>> {
        val failures = BasicRecordingRules.validate(cameraId, isLegacy, profile, rec, frameDropTolerance(snapshot))
        return (if (failures.isEmpty()) Verdict.PASS else Verdict.FAIL) to (listOf(BasicRecordingRules.summary(profile, rec)) + failures)
    }

    /** The snapshot row: the moment, the timings, then validateImage against the snapshot size. */
    fun judgeSnapshot(snapshotSize: Dim, snapshot: Snapshot): Pair<Verdict, List<String>> {
        val failures = ArrayList<String>()
        snapshot.error?.let { failures += it }
        if (snapshot.error == null) {
            if (snapshot.requestedAtMs == null) failures += "Video snapshot was never requested"
            else {
                if (!snapshot.resultReceived) failures += "Timeout waiting for the video snapshot result"
                failures += StillPreviewCombinationRules.imageFailures(snapshotSize, snapshot.image)
            }
        }
        return (if (failures.isEmpty()) Verdict.PASS else Verdict.FAIL) to (listOf(summary(snapshotSize, snapshot)) + failures)
    }

    fun summary(snapshotSize: Dim, snapshot: Snapshot): String {
        val parts = ArrayList<String>()
        parts += "$snapshotSize planned at ${snapshot.plannedAtMs} ms"
        snapshot.requestedAtMs?.let { parts += "requested at ${OpenCycle.ms(it)}" }
        snapshot.captureMs?.let { parts += "jpeg after ${OpenCycle.ms(it)}" }
        snapshot.image?.let { parts += "jpeg ${it.bytes / 1024} KB" }
        return parts.joinToString(" · ")
    }
}
