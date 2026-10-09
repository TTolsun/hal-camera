package dev.halcamera.cli

import dev.halcamera.camera.*
import org.junit.Assert.*
import org.junit.Test

class CliParityTest {
    private fun rejects(code: String = "INVALID_ARGUMENT", action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (e: CliFailure) { assertEquals(code, e.code) }
    }

    @Test fun `raw only capture is valid and cannot silently fall back`() {
        val command = AdbArguments.command("capture", mapOf("raw_size" to "640x480", "yuv_size" to "off", "jpeg_size" to "off"))
        val size = LiveSize(640, 480)
        val support = LiveStreamSupport(listOf(size), emptyList(), emptyList(), emptyList(), emptyList(), null, raw = listOf(size))
        assertEquals(size, command.streams!!.resolve(support).raw)
        rejects("PREFLIGHT_FAILED") { command.streams.resolve(support.copy(raw = emptyList())) }
        rejects { AdbArguments.command("capture", mapOf("raw_size" to "off", "yuv_size" to "off", "jpeg_size" to "off")) }
    }

    @Test fun `fps format and stabilization validate exact support`() {
        val size = LiveSize(640, 480)
        val support = LiveStreamSupport(listOf(size), listOf(size), listOf(size), listOf(LiveFps(15, 30)), emptyList(), null,
            stabilization = listOf(LiveStabilization.OIS), yuvSaveFormats = listOf(YuvSaveFormat.NV21))
        val settings = CliStreams(mapOf("fps" to "15-30", "stabilization" to "OIS", "yuv_format" to "NV21")).resolve(support)
        assertEquals(LiveFps(15, 30), settings.fps)
        assertEquals(YuvSaveFormat.NV21, settings.yuvSaveFormat)
        rejects { CliStreams(mapOf("fps" to "30-15")) }
        rejects("PREFLIGHT_FAILED") { CliStreams(mapOf("fps" to "60")).resolve(support) }
    }

    @Test fun `manual options reject missing pairs nonfinite numbers and command misuse`() {
        rejects { AdbArguments.command("capture", mapOf("iso" to "100")) }
        rejects { AdbArguments.command("capture", mapOf("zoom" to "NaN")) }
        rejects { AdbArguments.command("capture", mapOf("count" to "3")) }
        rejects { AdbArguments.command("burst", mapOf("count" to "0")) }
        rejects { AdbArguments.command("meter", mapOf("x" to "0.5")) }
        rejects { AdbArguments.command("capture", mapOf("wb" to "AUTO", "gains" to "1,1,1,1")) }
    }

    @Test fun `unsupported manual exposure is refused without clamping`() {
        val options = CliOptions(mapOf("iso" to "100", "exposure_ns" to "1000000"))
        rejects("PREFLIGHT_FAILED") { options.controls(LiveControlSupport.NONE, ManualSupport(camera2 = false), false) }
        val manual = ManualSupport(100..800, 1000L..30_000_000L)
        assertEquals(100, options.controls(LiveControlSupport.NONE, manual, false).manual.exposure!!.iso)
        rejects("PREFLIGHT_FAILED") { CliOptions(mapOf("iso" to "900", "exposure_ns" to "1000000")).controls(LiveControlSupport.NONE, manual, false) }
    }

    @Test fun `destructive operations require an explicit listed id and confirmation`() {
        rejects { AdbArguments.command("results.delete", emptyMap()) }
        rejects("CONFIRM_REQUIRED") { AdbArguments.command("results.delete", mapOf("run" to "20261009-123000-123")) }
        rejects { AdbArguments.command("gallery.delete", mapOf("media" to "../private", "confirm" to "true")) }
        assertEquals("results.delete", AdbArguments.command("results.delete", mapOf("run" to "20261009-123000-123", "confirm" to "true")).command)
        rejects { AdbArguments.command("results.list", mapOf("confirm" to "true")) }
    }

    @Test fun `dual selection is explicit and no camera parameters leak into results`() {
        rejects { AdbArguments.command("dual.preview", emptyMap()) }
        rejects { AdbArguments.command("results.list", mapOf("camera" to "0")) }
        val dual = AdbArguments.command("dual.capture", mapOf("first" to "2", "second" to "3"))
        assertEquals("0", dual.camera)
        rejects { AdbArguments.command("dual.record", mapOf("first" to "2", "second" to "3", "audio" to true)) }
    }
}
