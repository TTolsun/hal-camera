package dev.halcamera.camera

/** Align independent recorder origins in CLOCK_MONOTONIC; never compare sensor timestamps here. */
internal class RecordingTimeline(audioStartedNs: Long, videoStartedNs: Long, private val videoDurationUs: Long) {
    private val offsetUs = (videoStartedNs - audioStartedNs) / 1_000

    fun audioTimeUs(sourceTimeUs: Long): Long? = (sourceTimeUs - offsetUs)
        .takeIf { sourceTimeUs >= 0 && it >= 0 && it < videoDurationUs }
}
