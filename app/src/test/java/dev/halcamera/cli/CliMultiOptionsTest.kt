package dev.halcamera.cli

import dev.halcamera.camera.*
import org.junit.Assert.*
import org.junit.Test

class CliMultiOptionsTest {
    private val small = LiveSize(640, 480)
    private val large = LiveSize(1280, 720)
    private val cameras = (0..2).map { ConcurrentCamera(it.toString(), 1, listOf(small, large), listOf(small, large)) }
    private val plans = ConcurrentPlanner.plans(30, setOf(setOf("0", "1", "2")), cameras, 3)
    private fun command(vararg options: Pair<String, String>, engine: String? = null) =
        AdbArguments.command("multi.capture", mapOf("cameras" to "1,0") + options.toMap() + listOfNotNull(engine?.let { "engine" to it }).toMap())
    private fun refuses(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: CliFailure) { assertEquals(code, e.code) }
    }

    @Test fun `camera and size order is retained`() {
        val plan = CliMultiOptions.resolve(command("preview_sizes" to "640x480,1280x720"), 30, plans)
        assertEquals(listOf("1", "0"), plan.streams.map { it.camera.id })
        assertEquals(listOf(small, large), plan.streams.map { it.preview })
    }

    @Test fun `malformed repeated and mismatched lists are refused before submission`() {
        for (ids in listOf("0", "0,0", "0,", "../x,1", "0,1,1"))
            refuses("INVALID_ARGUMENT") { command("cameras" to ids) }
        for (sizes in listOf("640x480", "0x480,640x480", "999999x1,640x480", "640X480,640x480"))
            refuses("INVALID_ARGUMENT") { command("photo_sizes" to sizes) }
    }

    @Test fun `unsupported combinations sizes API levels and CameraX device counts never fall back`() {
        refuses("PREFLIGHT_FAILED") { CliMultiOptions.resolve(command(), 29, plans) }
        refuses("PREFLIGHT_FAILED") { CliMultiOptions.resolve(command(), 30, plans.map { it.copy(advertised = false) }) }
        refuses("PREFLIGHT_FAILED") { CliMultiOptions.resolve(command("photo_sizes" to "800x600,640x480"), 30, plans) }
        refuses("PREFLIGHT_FAILED") { CliMultiOptions.resolve(command("cameras" to "0,1,2", engine = "CameraX"), 30, plans) }
    }
}
