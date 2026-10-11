package dev.halcamera.cli

import dev.halcamera.camera.*
import org.junit.Assert.*
import org.junit.Test

class CliModeTest {
    private val size = LiveSize(640, 480)
    private val video = LiveVideo(size, 30, "H264")
    private val support = LiveStreamSupport(listOf(size), listOf(size), listOf(size), listOf(LiveFps(15, 30)),
        listOf(video), video, listOf(LiveStabilization.AUTO, LiveStabilization.OFF, LiveStabilization.VIDEO), raw = listOf(size))

    @Test fun `Photo RAW and Video stabilization are preserved`() {
        assertEquals(size, CliStreams(mapOf("raw_size" to "$size")).resolve(support, LiveModePolicy.PHOTO).raw)
        assertEquals(LiveStabilization.VIDEO, CliStreams(mapOf("stabilization" to "VIDEO")).resolve(support, LiveModePolicy.VIDEO).stabilization)
    }

    @Test fun `mode incompatible overrides are rejected instead of silently dropped`() {
        val cases = listOf(
            LiveModePolicy.VIDEO to mapOf("raw_size" to "$size"),
            LiveModePolicy.VIDEO to mapOf("fps" to "15-30"),
            LiveModePolicy.PHOTO to mapOf("stabilization" to "VIDEO"),
            LiveModePolicy.PHOTO to mapOf("video_size" to "$size"))
        for ((mode, values) in cases) {
            try { CliStreams(values).resolve(support, mode); fail("Accepted $mode $values") }
            catch (e: CliFailure) { assertEquals("PREFLIGHT_FAILED", e.code) }
        }
    }

    @Test fun `Video preview and capability query carry explicit mode`() {
        for (name in listOf("preview", "streams")) assertTrue(AdbArguments.command(name, mapOf("mode" to "Video")).videoMode)
        assertFalse(AdbArguments.command("capture", emptyMap()).videoMode)
        assertTrue(AdbArguments.command("record.start", emptyMap()).videoMode)
        for (args in listOf("capture" to "Video", "preview" to "video")) {
            try { AdbArguments.command(args.first, mapOf("mode" to args.second)); fail("Accepted $args") }
            catch (e: CliFailure) { assertEquals("INVALID_ARGUMENT", e.code) }
        }
    }

    @Test fun `command contracts preserve defaults required options and dispatch`() {
        assertEquals(CliCommandSpec.Target.QUERY, CliCommands.get("streams").target)
        assertEquals(CliCommandSpec.Target.LIBRARY, CliCommands.get("results.export").target)
        assertEquals(setOf("run", "reference"), CliCommands.get("results.compare").requiredOptions)
        assertEquals(1_800_000L, CliCommands.get("cts.run").timeoutMs)
        assertEquals(600_000L, CliCommands.get("benchmark.run").timeoutMs)
        assertFalse(CliCommands.get("cts.cases").cameraPermission)
        assertTrue(CliCommands.get("preview.stop").currentScreen)
    }

    @Test fun `PIP requires an explicit pair and sizes are request scoped`() {
        val source = CliOptions(mapOf("pip_type" to "physical", "pip_camera" to "2")).pipSource()!!
        val streams = CliStreams(mapOf("pip_preview_size" to "$size", "pip_photo_size" to "$size"))
        assertEquals(PipStreamSettings(size, size), streams.resolve(support, LiveModePolicy.PHOTO, source).pip["physical:2"])
        val invalid = listOf(mapOf("pip_camera" to "2"), mapOf("pip_type" to "service"),
            mapOf("pip_type" to "off", "pip_camera" to "2"),
            mapOf("pip_type" to "service", "pip_camera" to "1", "pip_x" to "0.9", "pip_y" to "0", "pip_width" to "0.2", "pip_height" to "0.2"))
        for (values in invalid) {
            try { CliOptions(values); fail("Accepted $values") }
            catch (e: CliFailure) { assertEquals("INVALID_ARGUMENT", e.code) }
        }
        try { streams.resolve(support, LiveModePolicy.PHOTO); fail("Accepted PIP sizes without a source") }
        catch (e: CliFailure) { assertEquals("INVALID_ARGUMENT", e.code) }
        assertNull(CliOptions(mapOf("pip_type" to "off")).pipSource())
    }

    @Test fun `flash precapture is refused for a composited PIP photo`() {
        val flash = LiveControlSupport(-3..3, 1.0, true, true, true, true, true)
        val options = CliOptions(mapOf("pip_type" to "service", "pip_camera" to "1", "flash" to "AUTO"))
        try { options.controls(flash, ManualSupport(), false); fail("Accepted PIP auto flash") }
        catch (e: CliFailure) { assertEquals("PREFLIGHT_FAILED", e.code) }
    }
}
