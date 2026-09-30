package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class LiveStreamSettingsTest {
    @Test fun `CameraX automatic codec choices do not overwrite Camera2 settings`() {
        val camera2 = LiveStreamSettings(LiveSize(1280, 720), LiveSize(640, 480), LiveSize(1920, 1080), null,
            LiveVideo(LiveSize(1920, 1080), 30, "HEVC"))
        val cameraX = camera2.copy(jpeg = null, video = camera2.video!!.copy(codec = "Auto"))
        val settings = hashMapOf(liveStreamSettingsKey("0", "Camera2") to camera2,
            liveStreamSettingsKey("0", "CameraX") to cameraX)
        assertEquals(camera2, settings["0"])
        val bytes = java.io.ByteArrayOutputStream()
        java.io.ObjectOutputStream(bytes).use { it.writeObject(settings) }
        java.io.ObjectInputStream(java.io.ByteArrayInputStream(bytes.toByteArray())).use { assertEquals(settings, it.readObject()) }
        settings.remove("0") // CLI resets only its Camera2 defaults.
        assertEquals(cameraX, settings[liveStreamSettingsKey("0", "CameraX")])
        val options = listOf(cameraX.video!!, LiveVideo(LiveSize(1280, 720), 30, "Auto"))
        val support = LiveStreamSupport(listOf(cameraX.preview), listOf(cameraX.yuv!!), emptyList(), emptyList(), options, options[0])
        val draft = LiveVideoDraft(support, null)
        assertEquals(listOf("Auto"), draft.formats)
        draft.selectSize(LiveSize(1280, 720))
        assertEquals(options[1], draft.requested)
        assertNull(support.rejection(cameraX.copy(video = draft.requested)))
    }
    @Test fun `recording choices follow codec size and supported frame rate without changing an untouched default`() {
        val hd = LiveSize(1280, 720)
        val full = LiveSize(1920, 1080)
        val ultra = LiveSize(3840, 2160)
        val options = listOf(LiveVideo(hd, 30, "H264"), LiveVideo(full, 30, "H264"),
            LiveVideo(full, 60, "H264"), LiveVideo(ultra, 24, "HEVC"), LiveVideo(hd, 60, "HEVC"))
        val support = LiveStreamSupport(listOf(hd), emptyList(), emptyList(), emptyList(), options, options[1])
        val draft = LiveVideoDraft(support, null)
        assertNull(draft.requested)
        assertEquals(full, draft.value?.size)
        assertEquals(listOf(full, hd), draft.sizes())
        draft.selectFormat("HEVC")
        assertEquals(LiveVideo(ultra, 24, "HEVC"), draft.requested)
        assertEquals(listOf(ultra, hd), draft.sizes())
        assertEquals(listOf(24), draft.rates())
        draft.selectSize(hd)
        assertEquals(LiveVideo(hd, 60, "HEVC"), draft.requested)
        draft.selectFormat("H264")
        assertEquals(LiveVideo(hd, 30, "H264"), draft.requested)
        draft.selectSize(full)
        draft.selectRate(60)
        assertEquals(LiveVideo(full, 60, "H264"), draft.requested)
        assertNull(support.rejection(support.defaults().copy(video = draft.requested)))
    }
    @Test fun `recording default reports the actual size even without full HD`() {
        val hd = LiveSize(1280, 720)
        val full = LiveSize(1920, 1080)
        val ultra = LiveSize(3840, 2160)
        assertEquals(LiveVideo(full, 30, "H264"), defaultLiveVideo(listOf(ultra, hd, full)))
        assertEquals(LiveVideo(hd, 30, "H264"), defaultLiveVideo(listOf(hd, ultra)))
        assertEquals(LiveVideo(ultra, 30, "H264"), defaultLiveVideo(listOf(ultra)))
        assertNull(defaultLiveVideo(listOf(LiveSize(720, 1280))))
        assertNull(defaultLiveVideo(emptyList()))
    }
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
