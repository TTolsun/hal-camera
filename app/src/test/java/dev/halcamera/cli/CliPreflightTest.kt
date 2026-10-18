package dev.halcamera.cli

import org.junit.Assert.*
import org.junit.Test

class CliPreflightTest {
    private class Device : CliPreflight.Device {
        var camera = true; var microphone = true; var storage = true; var locked = false
        var ids = listOf("0", "1"); var keys = listOf("custom:fast_on_off")
        override fun cameraGranted() = camera
        override fun microphoneGranted() = microphone
        override fun storageReady() = storage
        override fun locked() = locked
        override fun cameraIds() = ids
        override fun suiteKeys() = keys
    }
    private val device = Device()
    private val preflight = CliPreflight(device)

    private fun command(method: String, values: Map<String, Any?> = emptyMap(), arg: String? = null) = AdbArguments.command(method, values, arg)
    private fun refuses(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: CliFailure) { assertEquals(code, e.code) }
    }
    private fun screen(command: CliCommand, screen: String? = "live") = preflight.beforeScreen(command, command.spec, screen)

    @Test fun `busy screens and missing camera access stop a request before preparing`() {
        refuses("BUSY") { preflight.beforePreparing(CliCommands.get("capture"), busy = true) }
        device.camera = false
        refuses("PERMISSION_REQUIRED") { preflight.beforePreparing(CliCommands.get("capture"), busy = false) }
        refuses("PERMISSION_REQUIRED") { preflight.beforePreparing(CliCommands.get("probe"), busy = false) }
        preflight.beforePreparing(CliCommands.get("results.list"), busy = false)
        preflight.beforePreparing(CliCommands.get("cts.cases"), busy = false)
    }

    @Test fun `a locked device is refused before any permission or camera check`() {
        device.locked = true; device.microphone = false; device.ids = emptyList()
        refuses("DEVICE_LOCKED") { screen(command("record.start")) }
    }

    @Test fun `microphone is needed only for recording with sound`() {
        device.microphone = false
        refuses("PERMISSION_REQUIRED") { screen(command("record.start")) }
        screen(command("record.start", mapOf("audio" to false)))
        screen(command("dual.record", mapOf("first" to "2", "second" to "3")))
    }

    @Test fun `old storage permissions block only commands that save media`() {
        device.storage = false
        refuses("PERMISSION_REQUIRED") { screen(command("capture")) }
        screen(command("preview"))
    }

    @Test fun `suite keys and camera IDs must exist on this device`() {
        screen(command("cts.run", arg = "custom:fast_on_off"))
        refuses("UNKNOWN_CASE") { screen(command("cts.run", arg = "custom:missing")) }
        refuses("UNSUPPORTED_CAMERA") { screen(command("capture", mapOf("camera" to "7"))) }
    }

    @Test fun `only current screen commands run on Dual and every command needs a screen`() {
        refuses("APP_NOT_FOREGROUND") { screen(command("capture"), null) }
        refuses("APP_NOT_FOREGROUND") { screen(command("capture"), "dual") }
        screen(command("meter", mapOf("x" to "0.5", "y" to "0.5")), "dual")
        screen(command("live.info"), "dual")
        screen(command("live.info"), "multi")
        screen(command("preview.stop"), "multi")
        refuses("APP_NOT_FOREGROUND") { screen(command("capture"), "multi") }
        refuses("APP_NOT_FOREGROUND") { screen(command("live.info"), "gallery") }
    }

    @Test fun `Multi audio permission is explicit and silent recording needs none`() {
        device.microphone = false
        refuses("PERMISSION_REQUIRED") { screen(command("multi.record", mapOf("cameras" to "1,0"))) }
        screen(command("multi.record", mapOf("cameras" to "1,0", "audio" to false)))
    }
}
