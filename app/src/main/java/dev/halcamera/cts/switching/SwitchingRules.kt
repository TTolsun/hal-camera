package dev.halcamera.cts.switching

import dev.halcamera.cts.OpenCycle
import dev.halcamera.cts.Verdict
import dev.halcamera.cts.recording.BasicRecordingRules
import dev.halcamera.cts.recording.BasicRecordingRules.Profile
import dev.halcamera.cts.recording.BasicRecordingRules.Recording

/**
 * The decisions of the Switching case: the public cameras are visited in turn, [DEFAULT_ROUNDS] times over,
 * each visit an open → preview → first frame → close pass, and afterwards every camera records
 * [RECORDING_DURATION_MS] once to show it still works after the switching.
 *
 * Pure Kotlin: [SwitchingRunner] measures on the device and this object plans and judges. Not CTS; the recording
 * check is testBasicRecording's validateRecording reduced to what a switch must guarantee.
 */
object SwitchingRules {
    const val SOURCE = "custom#Switching"
    const val DEFAULT_ROUNDS = 5
    const val RECORDING_DURATION_MS = 3000L
    const val DURATION_MARGIN = BasicRecordingRules.DURATION_MARGIN

    data class Visit(val round: Int, val cameraId: String)

    /** The visiting order: every eligible camera in list order, then again, [rounds] times. */
    fun order(ids: List<String>, rounds: Int = DEFAULT_ROUNDS): List<Visit> =
        (1..rounds).flatMap { round -> ids.map { Visit(round, it) } }

    fun visitStepId(round: Int): String = "round_$round"
    const val RECORD_STEP_ID = "record"

    fun judgeVisit(cycle: OpenCycle): Pair<Verdict, List<String>> {
        val failures = cycle.failures()
        return (if (failures.isEmpty()) Verdict.PASS else Verdict.FAIL) to (listOfNotNull(cycle.summary()) + failures)
    }

    /** The profile the closing recording uses: the first present in CTS order, i.e. the largest quality. */
    fun recordingProfile(profiles: Map<Int, Profile>): Profile? =
        BasicRecordingRules.PROFILE_ORDER.firstNotNullOfOrNull { profiles[it] }

    /** Why the recording step is skipped rather than run; null means record. */
    fun recordingSkipReason(cameraId: String, numericId: Boolean, profile: Profile?): String? = when {
        !numericId -> "CamcorderProfile needs a numeric camera id"
        profile == null -> "Camera $cameraId has no CamcorderProfile"
        else -> null
    }

    /** validateRecording reduced: a file, a video track, the profile size, and a length within [DURATION_MARGIN] of the requested time. */
    fun validateRecording(cameraId: String, profile: Profile, rec: Recording, requestedMs: Long = RECORDING_DURATION_MS): List<String> {
        if (!rec.fileExists) return listOf("No video is recorded")
        val recorded = rec.recordedSize ?: return listOf("Cannot find video track!")
        if (recorded != profile.size) return listOf("Video size doesn't match, expected ${profile.size} got $recorded")
        val duration = (rec.durationUs / 1000).toFloat()
        val minMs = requestedMs * (1f - DURATION_MARGIN)
        val maxMs = requestedMs * (1f + DURATION_MARGIN)
        if (!(duration > minMs && duration < maxMs))
            return listOf("Camera $cameraId: Video duration doesn't match: recorded ${duration}ms, expected [$minMs,$maxMs]ms.")
        return emptyList()
    }

    fun recordingSummary(profile: Profile, rec: Recording): String =
        "${BasicRecordingRules.qualityName(profile.quality)} ${profile.size}@${profile.frameRate}fps · duration ${rec.durationUs / 1000}ms · frames ${rec.sampleTimesUs.size} · results ${rec.framesProduced}"
}
