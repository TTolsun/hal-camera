package dev.halcamera.camera

/** Projects a hardware capture timestamp back to PCM frame zero in the same clock domain. */
internal class SampleClock(private val rate: Int) {
    init { require(rate > 0) }

    fun originNs(framePosition: Long, timestampNs: Long): Long {
        require(framePosition >= 0 && timestampNs > 0)
        return timestampNs - (framePosition / rate) * 1_000_000_000L -
            (framePosition % rate) * 1_000_000_000L / rate
    }
}
