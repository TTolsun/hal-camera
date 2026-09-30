package dev.halcamera.benchmark.platform

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LaunchDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `records policy membership frequency units thermal and read interval`() {
        val root = temporary.newFolder()
        val policy = File(root, "policy6").apply { mkdir() }
        File(policy, "related_cpus").writeText("6 7\n")
        File(policy, "scaling_cur_freq").writeText("1017600\n")
        var now = 123L
        val sample = LaunchDiagnostics({ now++ }, { 1 }, root).snapshot()
        assertEquals("123", sample["start_ns"])
        assertEquals("124", sample["end_ns"])
        assertEquals(1, sample["thermal_status"])
        assertEquals("available", sample["cpu_status"])
        val cpu = (sample["cpu_policies"] as List<*>).single() as Map<*, *>
        assertEquals("6 7", cpu["related_cpus"])
        assertEquals(1017600L, cpu["scaling_cur_freq_khz"])
    }

    @Test fun `missing inaccessible or invalid frequencies remain unknown rather than zero`() {
        val root = temporary.newFolder()
        listOf("garbage", "0", "-1").forEachIndexed { i, text ->
            File(root, "policy$i").apply { mkdir(); File(this, "scaling_cur_freq").writeText(text) }
        }
        File(root, "policy6").mkdir()
        val sample = LaunchDiagnostics({ 1L }, { throw SecurityException() }, root).snapshot()
        assertNull(sample["thermal_status"])
        assertEquals("unavailable", sample["cpu_status"])
        (sample["cpu_policies"] as List<*>).forEach {
            assertNull((it as Map<*, *>)["scaling_cur_freq_khz"])
        }
        assertEquals("unavailable", LaunchDiagnostics({ 1L }, { null }, File(root, "absent")).snapshot()["cpu_status"])
    }

    @Test fun `partial policy access is explicit`() {
        val root = temporary.newFolder()
        File(root, "policy0").apply { mkdir(); File(this, "scaling_cur_freq").writeText("1000000") }
        File(root, "policy6").mkdir()
        assertEquals("partial", LaunchDiagnostics({ 1L }, { 0 }, root).snapshot()["cpu_status"])
    }
}
