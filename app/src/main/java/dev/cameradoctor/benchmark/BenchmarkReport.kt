package dev.cameradoctor.benchmark

import android.content.Context
import dev.cameradoctor.telemetry.Event
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Run JSON schema 3 (docs/PLAN-BenchMarker-v0.3.md chapter 6) as a pure map codec. Both directions are
 * JVM-testable; [BenchmarkReport] adds the org.json file boundary.
 */
object BenchmarkReportCodec {
    const val SCHEMA_VERSION = 3
    const val KIND = "benchmark"

    fun toJsonMap(run: BenchmarkRun, events: List<Map<String, Any?>> = emptyList()): Map<String, Any?> = linkedMapOf(
        "schema_version" to SCHEMA_VERSION,
        "kind" to KIND,
        "run_id" to run.runId,
        "exported_at_utc" to run.exportedAtUtc,
        "aborted" to run.aborted,

        "profile" to run.profile.toJsonMap(),
        "compatibility" to run.compatibility.toJsonMap(),
        "conditions" to mapOf("effective" to run.effectiveConditions),
        "metric_definition_version" to run.contract.metricDefinitionVersion,
        "stats_method" to run.contract.statsMethod,
        "clock" to run.contract.clock,
        "comparison_contract_id" to run.contract.comparisonContractId,
        "regression_rule_version" to run.regressionRuleVersion,
        "scoring_rule_version" to run.scoringRuleVersion,

        "device" to run.device.toJsonMap(),
        "app" to run.app.toJsonMap(),
        "subject" to run.subject.toJsonMap(),
        "endpoint" to run.endpoint.toJsonMap(),
        "env" to run.env.toJsonMap(),

        "validity" to run.validity.toJsonMap(),
        "baseline_ref" to run.baselineRef?.toJsonMap(),
        "reference_ref" to run.referenceRef?.toJsonMap(),

        "metrics" to run.metrics.map { it.toJsonMap() },
        "summary" to mapOf(
            "regressed" to run.regressedCount, "improved" to run.improvedCount,
            "stable" to run.stableCount, "unknown" to run.unknownCount, "endpoint_score" to run.endpointScore
        ),
        "raw" to run.raw,
        "events" to events
    )

    fun fromJsonMap(m: Map<String, Any?>, file: File? = null): BenchmarkRun {
        require(JsonMaps.i(m["schema_version"]) == SCHEMA_VERSION) { "unsupported schema_version ${m["schema_version"]}" }
        val profile = BenchmarkProfile.fromJsonMap(JsonMaps.map(m["profile"]) ?: error("profile missing"))
        val contract = MeasurementContract(
            profileId = profile.id,
            metricDefinitionVersion = m["metric_definition_version"] as? String ?: MeasurementContract.METRIC_DEFINITION_VERSION,
            statsMethod = m["stats_method"] as? String ?: MeasurementContract.STATS_METHOD,
            clock = m["clock"] as? String ?: MeasurementContract.CLOCK
        )
        val conditions = JsonMaps.map(JsonMaps.map(m["conditions"])?.get("effective"))
            ?.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }?.toMap() ?: emptyMap()
        val summary = JsonMaps.map(m["summary"])
        return BenchmarkRun(
            runId = m["run_id"] as String,
            exportedAtUtc = m["exported_at_utc"] as? String ?: "",
            aborted = JsonMaps.s(m["aborted"]),
            profile = profile,
            contract = contract,
            compatibility = Compatibility.fromJsonMap(JsonMaps.map(m["compatibility"])),
            effectiveConditions = conditions,
            endpoint = JsonMaps.endpointFromMap(JsonMaps.map(m["endpoint"]) ?: emptyMap()),
            device = DeviceInfo.fromJsonMap(JsonMaps.map(m["device"]) ?: emptyMap()),
            app = AppInfo.fromJsonMap(JsonMaps.map(m["app"]) ?: emptyMap()),
            subject = SubjectLabel.fromJsonMap(JsonMaps.map(m["subject"])),
            env = RunEnv.fromJsonMap(JsonMaps.map(m["env"])),
            validity = RunValidity.fromJsonMap(JsonMaps.map(m["validity"])),
            baselineRef = RunRef.fromJsonMap(JsonMaps.map(m["baseline_ref"])),
            referenceRef = RunRef.fromJsonMap(JsonMaps.map(m["reference_ref"])),
            metrics = (m["metrics"] as? List<*>)?.mapNotNull { JsonMaps.map(it) }?.map { BenchmarkMetric.fromJsonMap(it) } ?: emptyList(),
            regressionRuleVersion = m["regression_rule_version"] as? String ?: RegressionRules.VERSION,
            scoringRuleVersion = JsonMaps.s(m["scoring_rule_version"]),
            endpointScore = JsonMaps.i(summary?.get("endpoint_score")),
            raw = JsonMaps.map(m["raw"]) ?: emptyMap(),
            file = file
        )
    }

    fun eventToMap(e: Event): Map<String, Any?> = mapOf(
        "atNs" to e.atNs.toString(), "session" to e.session, "kind" to e.kind, "frame" to e.frame,
        "sensorNs" to e.sensorNs?.toString(), "values" to e.values
    )
}

/** Writes and reads run JSON files under files/benchmarks/. The only place org.json touches the contract. */
class BenchmarkReport(private val store: BenchmarkStore) {
    constructor(context: Context) : this(BenchmarkStore(context))

    fun write(run: BenchmarkRun, events: List<Event>): File {
        val file = store.file(run.runId)
        val map = BenchmarkReportCodec.toJsonMap(run, events.map { BenchmarkReportCodec.eventToMap(it) })
        file.writeText(json(map).toString(2))
        return file
    }

    fun read(file: File): BenchmarkRun? = try {
        BenchmarkReportCodec.fromJsonMap(toMap(JSONObject(file.readText())), file)
    } catch (_: Exception) { null }

    companion object {
        fun utcNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        fun newRunId(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

        fun json(map: Map<*, *>): JSONObject = JSONObject().also { o -> map.forEach { (k, v) -> o.put(k.toString(), value(v)) } }

        private fun value(v: Any?): Any = when (v) {
            null -> JSONObject.NULL
            is Map<*, *> -> json(v)
            is List<*> -> JSONArray(v.map(::value))
            is Enum<*> -> v.name.lowercase(Locale.US)
            is Double -> if (v.isNaN() || v.isInfinite()) JSONObject.NULL else v
            is Float -> if (v.isNaN() || v.isInfinite()) JSONObject.NULL else v.toDouble()
            else -> v
        }

        fun toMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { k -> fromJson(o.opt(k)) }

        private fun fromJson(v: Any?): Any? = when (v) {
            null, JSONObject.NULL -> null
            is JSONObject -> toMap(v)
            is JSONArray -> (0 until v.length()).map { fromJson(v.opt(it)) }
            else -> v
        }
    }
}
