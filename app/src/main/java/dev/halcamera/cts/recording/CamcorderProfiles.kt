package dev.halcamera.cts.recording

import android.media.CamcorderProfile
import dev.halcamera.cts.Dim

/** The CamcorderProfiles CTS asks a numeric camera id for, in [BasicRecordingRules.PROFILE_ORDER] plus QHD and 2K. */
object CamcorderProfiles {
    @Suppress("DEPRECATION")
    fun read(cameraId: Int): Map<Int, BasicRecordingRules.Profile> {
        val out = LinkedHashMap<Int, BasicRecordingRules.Profile>()
        (BasicRecordingRules.PROFILE_ORDER + listOf(BasicRecordingRules.QUALITY_QHD, BasicRecordingRules.QUALITY_2K)).forEach { q ->
            if (runCatching { CamcorderProfile.hasProfile(cameraId, q) }.getOrDefault(false)) {
                val p = CamcorderProfile.get(cameraId, q)
                out[q] = BasicRecordingRules.Profile(q, Dim(p.videoFrameWidth, p.videoFrameHeight), p.videoFrameRate)
            }
        }
        return out
    }

    /** The platform profile behind a [BasicRecordingRules.Profile], for MediaRecorder.setProfile. */
    @Suppress("DEPRECATION")
    fun get(cameraId: Int, quality: Int): CamcorderProfile = CamcorderProfile.get(cameraId, quality)
}
