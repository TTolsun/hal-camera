package dev.halcamera.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingTimebaseTest {
    @Test fun realtimeSensorIsConvertedForAudioWithoutChangingFrameIntervals() {
        val clock = RecordingTimebase(true, 18_000_000_000L)
        assertEquals(42_000_000_000L, clock.encoderTimestampNs(60_000_000_000L))
        assertEquals(33_000_000L, clock.encoderTimestampNs(60_033_000_000L) - clock.encoderTimestampNs(60_000_000_000L))
    }

    @Test fun unknownSensorClockIsNeverSubtractedFromAnAppClock() {
        assertEquals(123L, RecordingTimebase(false, 18_000_000_000L).encoderTimestampNs(123L))
    }
}
