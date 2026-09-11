package dev.halcamera.benchmark

import java.io.Writer

/** One row per run/metric, with stable columns shared with tools/aggregate.py. No derived verdicts. */
object BenchmarkCsv {
    val runColumns = listOf(
        "run_id", "exported_at_utc", "comparison_contract_id", "profile.id",
        "metric_definition_version", "stats_method", "clock", "regression_rule_version", "scoring_rule_version",
        "device.manufacturer", "device.model", "device.build_display", "device.fingerprint",
        "device.vendor_fingerprint", "device.camera_info_version", "app.version_name", "app.version_code", "app.debuggable",
        "subject.build_label", "subject.commit", "subject.branch", "subject.note",
        "endpoint.logicalCameraId", "endpoint.physicalCameraId", "endpoint.role",
        "env.thermal_start", "env.thermal_max", "env.thermal_end", "env.battery_start", "env.battery_end",
        "env.charging", "env.power_save_mode", "validity.measurement_valid", "validity.comparison_eligible",
        "validity.scoring_eligible", "validity.validity_rule_version", "validity.flags", "aborted"
    )
    val metricColumns = listOf("id", "category", "unit", "value", "p50", "p95", "min", "max", "n", "timeout", "unknown_reason")
    val headers = runColumns + metricColumns.map { "metric.$it" }

    fun write(runs: Sequence<BenchmarkRun>, writer: Writer) {
        writer.write(headers.joinToString(",") + "\r\n")
        runs.forEach { run ->
            val map = BenchmarkReportCodec.toJsonMap(run)
            val prefix = runColumns.map { path ->
                path.split('.').fold(map as Any?) { value, key -> (value as? Map<*, *>)?.get(key) }
            }
            val metrics = run.metrics.map { it.toJsonMap() }.ifEmpty { listOf(emptyMap()) }
            metrics.forEach { metric ->
                writer.write((prefix + metricColumns.map { metric[it] }).joinToString(",", transform = ::cell) + "\r\n")
            }
        }
    }

    /** Quote strings and neutralize spreadsheet formula prefixes; numeric negatives remain numbers. */
    internal fun cell(value: Any?): String {
        if (value == null) return ""
        val text = if (value is Collection<*>) value.joinToString(";") else value.toString()
        val safe = if (value is String && (text.trimStart().firstOrNull() in listOf('=', '+', '-', '@') ||
                text.firstOrNull() in listOf('\t', '\r', '\n'))) "'$text" else text
        return "\"${safe.replace("\"", "\"\"")}\""
    }
}
