package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class RecordingTimelineTest {
    @Test fun `trim microphone preroll and align the first overlapping packet`() {
        val timeline = RecordingTimeline(1_000_000_000,1_200_000_000,1_000_000)
        assertNull(timeline.audioTimeUs(199_999))
        assertEquals(0L,timeline.audioTimeUs(200_000))
        assertEquals(21_333L,timeline.audioTimeUs(221_333))
    }

    @Test fun `same microphone packet has independent time in each camera`() {
        val first = RecordingTimeline(0,100_000_000,1_000_000)
        val second = RecordingTimeline(0,150_000_000,1_000_000)
        assertEquals(100_000L,first.audioTimeUs(200_000))
        assertEquals(50_000L,second.audioTimeUs(200_000))
    }

    @Test fun `exclude extractor end marker and audio after video ends`() {
        val timeline = RecordingTimeline(0,100_000_000,500_000)
        assertNull(timeline.audioTimeUs(-1))
        assertEquals(499_999L,timeline.audioTimeUs(599_999))
        assertNull(timeline.audioTimeUs(600_000))
        assertNull(timeline.audioTimeUs(700_000))
    }

    @Test fun `later audio origin preserves its initial gap`() {
        val timeline = RecordingTimeline(100_000_000,0,1_000_000)
        assertEquals(100_000L,timeline.audioTimeUs(0))
    }
}
