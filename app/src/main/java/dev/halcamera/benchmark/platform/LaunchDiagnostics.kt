package dev.halcamera.benchmark.platform

import java.io.File

/** Best-effort sysfs snapshots. Policy membership avoids guessing big/little topology from CPU numbers. */
class LaunchDiagnostics(
    private val clock: () -> Long,
    private val thermalStatus: () -> Int?,
    private val root: File = File("/sys/devices/system/cpu/cpufreq")
) {
    fun snapshot(): Map<String, Any?> {
        val start = clock()
        val policies = try {
            root.listFiles()?.filter { it.name.matches(Regex("policy[0-9]+")) }
                ?.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE }
        } catch (_: SecurityException) { null }
        val cpu = policies?.map { policy ->
            val frequency = read(policy, "scaling_cur_freq")?.toLongOrNull()?.takeIf { it > 0 }
            mapOf(
                "policy" to policy.name,
                "related_cpus" to read(policy, "related_cpus"),
                "scaling_cur_freq_khz" to frequency,
                "status" to if (frequency == null) "unavailable" else "available"
            )
        }.orEmpty()
        val thermal = try { thermalStatus() } catch (_: Exception) { null }
        return mapOf(
            "start_ns" to start.toString(), "end_ns" to clock().toString(),
            "clock" to "elapsedRealtimeNanos", "thermal_status" to thermal,
            "cpu_status" to when {
                cpu.isEmpty() || cpu.all { it["status"] == "unavailable" } -> "unavailable"
                cpu.any { it["status"] == "unavailable" } -> "partial"
                else -> "available"
            },
            "cpu_policies" to cpu
        )
    }

    private fun read(policy: File, name: String): String? = try {
        File(policy, name).readText().trim().takeIf { it.isNotEmpty() }
    } catch (_: java.io.IOException) { null } catch (_: SecurityException) { null }
}
