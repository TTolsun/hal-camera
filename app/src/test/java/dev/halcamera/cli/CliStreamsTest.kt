package dev.halcamera.cli

import dev.halcamera.camera.*
import org.junit.Assert.*
import org.junit.Test

class CliStreamsTest {
    private val hd = LiveSize(1280, 720)
    private val small = LiveSize(640, 480)
    private val video = LiveVideo(hd, 30, "H264")
    private val support = LiveStreamSupport(listOf(hd, small), listOf(small), listOf(hd),
        emptyList(), listOf(video), video)

    @Test fun `overrides preserve Off and default omitted fields`() {
        val command = AdbArguments.command("capture", mapOf("engine" to "CameraX", "preview_size" to "640x480", "yuv_size" to "off"))
        assertEquals("CameraX", command.engine)
        val value = command.streams!!.resolve(support)
        assertEquals(small, value.preview)
        assertNull(value.yuv)
        assertEquals(hd, value.jpeg)
        assertNull(value.video)
    }

    @Test fun `invalid options reject before camera preparation`() {
        listOf("0x720", "-1x720", "999999999999x1", "1280:720", "off").forEach { value ->
            rejects("INVALID_ARGUMENT") { CliStreams(mapOf("preview_size" to value)) }
        }
        rejects("INVALID_ARGUMENT") { AdbArguments.command("capture", mapOf("yuv_size" to "off", "jpeg_size" to "off")) }
        rejects("INVALID_ARGUMENT") { AdbArguments.command("streams", mapOf("preview_size" to "1280x720")) }
        rejects("INVALID_ARGUMENT") { AdbArguments.command("preview.stop", mapOf("engine" to "CameraX")) }
        rejects("INVALID_ARGUMENT") { CliStreams(mapOf("video_fps" to "0")) }
    }

    @Test fun `unsupported sizes and codecs fail rather than falling back`() {
        rejects("PREFLIGHT_FAILED") { CliStreams(mapOf("preview_size" to "1920x1080")).resolve(support) }
        rejects("PREFLIGHT_FAILED") { CliStreams(mapOf("codec" to "HEVC")).resolve(support) }
        val xSupport = support.copy(videos = listOf(video.copy(codec = "Auto")), defaultVideo = video.copy(codec = "Auto"))
        assertEquals("Auto", CliStreams(mapOf("video_size" to "1280x720")).resolve(xSupport).video!!.codec)
        rejects("PREFLIGHT_FAILED") { CliStreams(mapOf("codec" to "H264")).resolve(xSupport) }
    }

    @Test fun `legacy commands retain defaults and capabilities accept camera and engine`() {
        assertNull(AdbArguments.command("preview", emptyMap()).streams)
        assertNull(AdbArguments.command("preview", emptyMap()).engine)
        assertEquals("0", AdbArguments.command("streams", emptyMap()).camera)
        assertEquals("CameraX", AdbArguments.command("streams", mapOf("engine" to "CameraX")).engine)
    }

    private fun rejects(code: String, action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (e: CliFailure) { assertEquals(code, e.code) }
    }
}
