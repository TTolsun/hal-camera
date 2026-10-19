package dev.halcamera.ui

import org.junit.Assert.*
import org.junit.Test

class LiveStreamReadoutTest {
    @Test fun depthOnlyDoesNotInventColorOutputs() {
        assertEquals(LiveStreamReadout("Depth 320×240", "Depth 320x240"),
            LiveStreamReadout.from(mapOf("depth" to "320x240")))
    }
    @Test fun everyInactiveStreamIsHiddenFromBothReadouts() {
        val values = listOf("analysis", "jpeg", "raw", "recording", "pip", "pipYuv", "pipJpeg").associateWith { null } +
            mapOf("preview" to "1280x720", "composite" to "590x1280")
        assertEquals(LiveStreamReadout("P 1280×720", "Preview 1280x720"), LiveStreamReadout.from(values))
        assertEquals(LiveStreamReadout("", ""), LiveStreamReadout.from(null))
        assertEquals(LiveStreamReadout("", ""), LiveStreamReadout.from(mapOf("raw" to "", "jpeg" to " ")))
    }

    @Test fun appJpegKeepsItsSourceSizeAndCameraJpegTakesPrecedence() {
        val converted = LiveStreamReadout.from(mapOf("appJpeg" to "640x480"))
        assertEquals("J YUV (640×480)", converted.text)
        assertEquals("JPEG YUV (640x480)", converted.description)
        assertEquals("J 1920×1080", LiveStreamReadout.from(mapOf("jpeg" to "1920x1080", "appJpeg" to "640x480")).text)
    }

    @Test fun pipHasOnePrefixAndConfiguredRecordingKeepsItsCodec() {
        val value = LiveStreamReadout.from(mapOf("preview" to "1280x720", "recording" to "1920x1080",
            "recordingFormat" to "HEVC", "pip" to "640x480", "pipJpeg" to "1280x720"))
        assertEquals("P 1280×720   R HEVC 1920×1080\nPIP P 640×480   J 1280×720", value.text)
        assertEquals("Preview 1280x720, Recording HEVC 1920x1080, PIP Preview 640x480, PIP JPEG 1280x720", value.description)
        assertEquals("R Auto 1920×1080", LiveStreamReadout.from(mapOf("recording" to "1920x1080")).text)
    }

    @Test fun multiNumbersEachCameraInScreenOrder() {
        val two = LiveStreamReadout.multi(listOf("Front 1" to mapOf("preview" to "1280x720", "jpeg" to "1920x1440"),
            "Rear 0" to mapOf("preview" to "640x480", "jpeg" to "1440x1080")))
        assertEquals("1 · P 1280×720   J 1920×1440\n2 · P 640×480   J 1440×1080", two.text)
        assertTrue(two.description.startsWith("Screen 1, Front 1: Preview 1280x720"))
        val three = LiveStreamReadout.multi(List(3) { "Camera $it" to mapOf("preview" to "640x480") })
        assertEquals("1 · P 640×480\n2 · P 640×480\n3 · P 640×480", three.text)
    }
}
