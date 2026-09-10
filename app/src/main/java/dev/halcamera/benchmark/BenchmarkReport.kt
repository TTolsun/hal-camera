package dev.halcamera.benchmark

import android.content.Context
import dev.halcamera.telemetry.Event
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
 *
 * Decoding is strict about the comparison contract (PR #11 follow-up review): kind, every contract component and
 * the stored comparison_contract_id are required and must agree, and a confirmed (non-draft) canonical profile
 * must be stored exactly as this app defines it. A file that fails any of these is unreadable, never silently
 * promoted to the current contract.
 */
object BenchmarkReportCodec {
    const val SCHEMA_VERSION = 3
    const val KIND = "benchmark"

    fun toJsonMap(run: BenchmarkRun): Map<String, Any?> = linkedMapOf(
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
        "events" to run.events
    )

    /**
     * [canonicalProfiles] is injectable so the canonical-profile check can be tested before a non-draft profile
     * exists; production callers use [BenchmarkProfile.CANONICAL].
     */
    fun fromJsonMap(
        m: Map<String, Any?>,
        file: File? = null,
        canonicalProfiles: Map<String, BenchmarkProfile> = BenchmarkProfile.CANONICAL
    ): BenchmarkRun {
        require(JsonMaps.i(m["schema_version"]) == SCHEMA_VERSION) { "unsupported schema_version ${m["schema_version"]}" }
        require(m["kind"] == KIND) { "kind must be \"$KIND\": ${m["kind"]}" }
        val profile = BenchmarkProfile.fromJsonMap(JsonMaps.map(m["profile"]) ?: throw IllegalArgumentException("profile missing"))
        // A draft profile may still change (3.5), so only confirmed ids are checked against the canonical definition.
        if (!profile.isDraft) canonicalProfiles[profile.id]?.let { canonical ->
            require(canonical == profile) { "profile ${profile.id} differs from the canonical definition" }
        }
        val contract = MeasurementContract(
            profileId = profile.id,
            metricDefinitionVersion = JsonMaps.reqString(m, "metric_definition_version", "run"),
            statsMethod = JsonMaps.reqString(m, "stats_method", "run"),
            clock = JsonMaps.reqString(m, "clock", "run")
        )
        val storedContractId = JsonMaps.reqString(m, "comparison_contract_id", "run")
        require(storedContractId == contract.comparisonContractId) {
            "comparison_contract_id mismatch: stored $storedContractId, recomputed ${contract.comparisonContractId}"
        }
        val conditions = JsonMaps.map(JsonMaps.map(m["conditions"])?.get("effective"))
            ?.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }?.toMap() ?: emptyMap()
        val summary = JsonMaps.map(m["summary"])
        return BenchmarkRun(
            runId = JsonMaps.reqString(m, "run_id", "run"),
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
            metrics = JsonMaps.maps(m["metrics"]).map { BenchmarkMetric.fromJsonMap(it) },
            regressionRuleVersion = m["regression_rule_version"] as? String ?: RegressionRules.VERSION,
            scoringRuleVersion = JsonMaps.s(m["scoring_rule_version"]),
            endpointScore = JsonMaps.i(summary?.get("endpoint_score")),
            raw = JsonMaps.map(m["raw"]) ?: emptyMap(),
            events = JsonMaps.maps(m["events"]),
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

    /** Writes atomically (temp file, fsync, rename) so a killed process never leaves a truncated run file. */
    fun write(run: BenchmarkRun, events: List<Event>): File {
        val file = store.file(run.runId)
        val withEvents = run.copy(events = events.map { BenchmarkReportCodec.eventToMap(it) })
        AtomicFiles.write(file, json(BenchmarkReportCodec.toJsonMap(withEvents)).toString(2))
        return file
    }

    /** null means unreadable; the reason is kept in [lastReadError] so the UI can say so instead of hiding it. */
    fun read(file: File): BenchmarkRun? = try {
        lastReadError = null
        BenchmarkReportCodec.fromJsonMap(toMap(JSONObject(file.readText())), file)
    } catch (e: Exception) {
        lastReadError = "${file.name}: ${e.message}"
        android.util.Log.w(TAG, "unreadable run file ${file.name}: ${e.message}")
        null
    }

    var lastReadError: String? = null
        private set

    companion object {
        private const val TAG = "BenchmarkReport"

        fun utcNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

        /** Millisecond suffix keeps ids unique across a fast abort and restart within the same second. */
        fun newRunId(): String = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

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
