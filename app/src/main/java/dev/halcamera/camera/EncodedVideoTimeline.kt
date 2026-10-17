package dev.halcamera.camera

/** Normalize encoded output, never the first submitted frame (which an encoder may drop). */
internal class EncodedVideoTimeline {
    var originUs = -1L
        private set
    private var lastUs = -1L

    fun timeUs(captureTimeUs: Long): Long {
        require(captureTimeUs >= 0 && captureTimeUs > lastUs) { "Non-monotonic video timestamp" }
        if (originUs < 0) originUs = captureTimeUs
        lastUs = captureTimeUs
        return captureTimeUs - originUs
    }
}
