package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class LiveStreamSettingsTest {
    @Test fun `saved camera settings retain disabled outputs video and default rollback across recreation`() {
        val first = LiveStreamSettings(LiveSize(1280, 720), null, LiveSize(1920, 1080), LiveFps(15, 30),
            LiveVideo(LiveSize(1920, 1080), 30, "HEVC"))
        val settings = hashMapOf("0" to first, "1" to first.copy(yuv = LiveSize(640, 480), jpeg = null))
        val lastGood = hashMapOf<String, LiveStreamSettings?>("0" to null, "1" to settings["1"])
        val buffer = java.io.ByteArrayOutputStream()
        java.io.ObjectOutputStream(buffer).use { it.writeObject(settings); it.writeObject(lastGood) }
        java.io.ObjectInputStream(java.io.ByteArrayInputStream(buffer.toByteArray())).use {
            assertEquals(settings, it.readObject())
            assertEquals(lastGood, it.readObject())
        }
        assertEquals(null, settings["0"]?.yuv)
        assertEquals(null, settings["1"]?.jpeg)
    }
    private val small = LiveSize(640, 480)
    private val hd = LiveSize(1280, 720)
    private val full = LiveSize(1920, 1080)
    private val fps = LiveFps(30, 30)
    private val video = LiveVideo(full, 30, "H264")
    private val support = LiveStreamSupport(listOf(small, hd, full), listOf(small, hd), listOf(full), listOf(fps), listOf(video))

    @Test fun `defaults preserve existing pixel budgets and automatic frame rate`() {
        assertEquals(LiveStreamSettings(hd, small, full, null), support.defaults())
    }
    @Test fun `all four output combinations are allowed without inventing combination support`() {
        for (yuv in listOf(null, small)) for (jpeg in listOf(null, full)) {
            val setting = LiveStreamSettings(hd, yuv, jpeg, fps, video)
            assertNull(support.rejection(setting))
            assertEquals(yuv != null || jpeg != null, setting.canCapture)
        }
    }
    @Test fun `unsupported size fps and encoder settings are rejected instead of coerced`() {
        val base = support.defaults()
        listOf(base.copy(preview = LiveSize(1, 1)), base.copy(yuv = full), base.copy(jpeg = small),
            base.copy(fps = LiveFps(120, 120)), base.copy(video = video.copy(codec = "HEVC")),
            base.copy(video = video.copy(bitrate = 1))).forEach { assertNotNull(support.rejection(it)) }
    }
    @Test fun `disabled outputs remain explicit in metadata`() {
        val value = support.defaults().copy(yuv = null, jpeg = null)
        assertNull(value.metadata()["analysis"])
        assertNull(value.metadata()["jpeg"])
        assertFalse(value.canCapture)
    }
}
