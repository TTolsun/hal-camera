package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class RecordingClockTest {
    @Test fun `hardware timestamp projects buffered samples to the capture origin`() {
        val clock = SampleClock(48_000)
        assertEquals(5_000_000_000L,clock.originNs(1920,5_040_000_000L))
        assertEquals(5_000_000_000L,clock.originNs(49_920,6_040_000_000L))
    }

    @Test fun `long recordings avoid multiplication overflow`() {
        assertEquals(123_000L,SampleClock(48_000).originNs(48_000L*86400*100,86400L*100*1_000_000_000+123_000))
    }

    @Test fun `dropped startup frames do not become the muxed video origin`() {
        val video = EncodedVideoTimeline()
        // Frames at 1.0 and 1.033 seconds were submitted but never encoded.
        assertEquals(0L,video.timeUs(1_066_666))
        assertEquals(33_334L,video.timeUs(1_100_000))
        val audio = RecordingTimeline(900_000_000,video.originUs*1_000,1_000_000)
        assertEquals(33_334L,audio.audioTimeUs(200_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reject video timestamp regression instead of publishing invalid sync`() {
        EncodedVideoTimeline().apply { timeUs(100); timeUs(99) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reject duplicate encoded timestamp`() {
        EncodedVideoTimeline().apply { timeUs(100); timeUs(100) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reject invalid hardware frame position`() {
        SampleClock(48_000).originNs(-1,100)
    }
}
