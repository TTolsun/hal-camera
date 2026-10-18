package dev.halcamera.cli

import dev.halcamera.cli.CliCommandSpec.Flow
import dev.halcamera.cli.CliCommandSpec.Target
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * The spec table is the one place a command is defined. These checks keep its derived behavior equal to the
 * name lists it replaced, and keep the hello fixture the PC client tests read in step with it.
 */
class CliCommandSpecTest {
    private val specs = CliCommands.specs.values

    private fun names(predicate: (CliCommandSpec) -> Boolean) = specs.filter(predicate).map { it.name }.toSet()

    @Test fun `every screen command names how Live runs it and screenless commands name no flow`() {
        specs.forEach { spec ->
            if (spec.target == Target.LIVE) assertNotEquals(spec.name, Flow.NONE, spec.flow)
            else assertEquals(spec.name, Flow.NONE, spec.flow)
        }
    }

    @Test fun `derived properties match the name lists they replaced`() {
        assertEquals(setOf("record.start", "dual.record", "multi.record"), names { it.recording })
        assertEquals(setOf("record.start"), names { it.snapshots })
        assertEquals(setOf("capture", "burst", "bracket"), names { it.stills })
        assertEquals(setOf("dual.record"), names { it.silentOnly })
        assertEquals(setOf("benchmark.run"), names { it.profile })
        assertEquals(setOf("cts.run"), names { it.cases })
        assertEquals(setOf("preview", "streams"), names { it.acceptsMode })
        assertEquals(names { it.name.endsWith(".delete") }, names { it.confirmRequired })
        assertEquals(names { it.target != Target.LIBRARY && it.name != "cts.cases" }, names { it.cameraPermission })
        assertEquals(setOf("live.info", "events", "meter", "pip.sources"), names { it.flow == Flow.INSPECT || it.flow == Flow.EVENTS })
        val cameraCommands = names { it.camera }
        val legacyEarlyCancel = names { (it.name in cameraCommands && it.name != "streams") ||
            it.name in setOf("preview.stop", "cts.run", "events", "meter", "live.info") }
        assertEquals(legacyEarlyCancel + names { it.flow == Flow.MULTI }, names { it.earlyCancelNotifiesHost })
    }

    @Test fun `content call aliases resolve to listed commands`() {
        CliCommands.aliases.values.forEach { assertTrue(it, it in CliCommands.specs) }
        assertEquals("preview", AdbArguments.command("preview.start", emptyMap()).command)
    }

    @Test fun `an unexpected content call argument is named`() {
        try { AdbArguments.command("results.list", mapOf("zoom" to "2")); fail() }
        catch (e: CliFailure) { assertEquals("Unexpected argument for results.list: zoom", e.message) }
    }

    /**
     * `tools/halcam/tests/fixtures/hello.json` holds the spec part of hello. The Python and shell client tests
     * read it, so a spec change that would break the clients fails there. Set HALCAM_UPDATE_FIXTURES=1 to rewrite it.
     */
    @Test fun `PC client fixture matches the advertised specs`() {
        val fixture = listOf("../tools/halcam/tests/fixtures/hello.json", "tools/halcam/tests/fixtures/hello.json").map(::File)
            .firstOrNull { it.parentFile.isDirectory } ?: error("tools/halcam/tests/fixtures is missing")
        val expected = buildString {
            append("{\n\"commands\": ").append(json(CliCommand.COMMANDS)).append(",\n")
            append("\"stream_keys\": ").append(json(CliStreams.KEYS.toList())).append(",\n")
            append("\"preview_screens\": ").append(json(CliCommands.previewScreens.toList())).append(",\n")
            append("\"command_specs\": {\n")
            append(CliCommands.specs.entries.joinToString(",\n") { (name, spec) -> "  ${json(name)}: ${json(spec.toMap())}" })
            append("\n}\n}\n")
        }
        if (System.getenv("HALCAM_UPDATE_FIXTURES") == "1") fixture.writeText(expected)
        assertEquals("Run with HALCAM_UPDATE_FIXTURES=1 to rewrite ${fixture.path}", expected, fixture.readText().replace("\r\n", "\n"))
    }

    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { "${json(it.key.toString())}: ${json(it.value)}" }
        is Iterable<*> -> value.joinToString(", ", "[", "]") { json(it) }
        else -> value.toString()
    }
}
