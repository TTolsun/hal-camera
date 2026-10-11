package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class DualCallbackStreamsTest {
    @Test fun nativePhysicalRecordingDescribesBothEncoderOutputsWithoutFalseBufferTimes() {
        val rows = dualCallbackStreams(recordingConfigured = true, physicalIds = listOf("rear-wide", "rear-tele"))
        val recordings = rows.filter { it["kind"] == "RECORDING" }
        assertEquals(listOf("recording_main", "recording_sub"), recordings.map { it["id"] })
        assertEquals(listOf("rear-wide", "rear-tele"), recordings.map { it["physicalId"] })
        assertTrue(recordings.all { it["observable"] == false && it["repeating"] == true })
        assertFalse(recordings.any { it["label"].toString().contains("App") })
    }

    @Test fun cameraXPreviewRelayDoesNotAdvertiseAppEncodersOrUnsupportedPhotoRows() {
        val rows = dualCallbackStreams(photos = false)
        assertEquals(listOf("preview_main", "preview_sub"), rows.map { it["id"] })
        assertFalse(rows.any { it["kind"] == "RECORDING" })
    }

    @Test fun photoModeDoesNotAdvertiseEncoderOutputs() {
        assertFalse(dualCallbackStreams().any { it["kind"] == "RECORDING" })
        assertEquals(2, dualCallbackStreams(photos = false).size)
    }

    @Test fun nativeRecordingTargetsAreTrackedOnlyWhileRecordingAndNeverAddedToPhotos() {
        val rows = dualCallbackStreams(recordingConfigured = true)
        val configured = rows.map { it["id"] }.toSet()
        assertEquals(listOf("preview_main", "preview_sub"), dualRequestStreams(photo = false, recording = false))
        assertEquals(listOf("preview_main", "preview_sub", "recording_main", "recording_sub"),
            dualRequestStreams(photo = false, recording = true))
        assertEquals(listOf("photo_main", "photo_sub"), dualRequestStreams(photo = true, recording = true))
        assertTrue(dualRequestStreams(photo = false, recording = true).all { it in configured })
    }
}
