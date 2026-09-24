package dev.halcamera.benchmark.platform

import android.util.Range
import android.util.Size
import dev.halcamera.benchmark.domain.BenchmarkProfile
import dev.halcamera.camera.RecordSpec
import dev.halcamera.camera.StreamSpec

/**
 * Turns a profile's condition strings into the exact streams an engine must configure.
 *
 * It belongs beside [ProfileCompatibilityChecker] rather than in the screen that used to hold it: both translate
 * the same strings into the same Android values, and `android.util.Size` and `Range` are types the domain may
 * not touch at all, so this mapping can only live in the platform layer.
 */
object StreamSpecs {

    /** The 1080p fallbacks only apply to a profile whose sizes this build cannot parse, which the preflight refuses anyway. */
    fun of(profile: BenchmarkProfile): StreamSpec = StreamSpec(
        preview = ProfileCompatibilityChecker.size(profile.previewSize) ?: Size(1920, 1080),
        yuv = ProfileCompatibilityChecker.size(profile.yuvSize) ?: Size(1920, 1080),
        jpeg = ProfileCompatibilityChecker.size(profile.stillSize) ?: Size(1920, 1080),
        fpsRange = ProfileCompatibilityChecker.fpsRange(profile.fpsRange) ?: Range(30, 30),
        record = record(profile)
    )

    /**
     * The recorder conditions, or null for a profile without a RECORD stage. A size the profile names but this
     * build cannot parse is not silently replaced: the stage is left out, and the run then reports the 3.x
     * metrics as not measured rather than measuring them under conditions nobody asked for.
     */
    fun record(profile: BenchmarkProfile): RecordSpec? {
        val size = profile.recordSize?.let { ProfileCompatibilityChecker.size(it) } ?: return null
        return RecordSpec(
            size = size,
            codec = profile.recordCodec ?: return null,
            bitrate = profile.recordBitrate ?: return null,
            fps = profile.recordFps ?: return null,
            audio = profile.recordAudio ?: false
        )
    }
}
