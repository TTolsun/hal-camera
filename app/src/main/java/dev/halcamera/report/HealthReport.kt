package dev.halcamera.report

import android.content.Context
import android.os.Build
import dev.halcamera.check.AutoCheckRunner
import dev.halcamera.check.CheckEvaluator
import dev.halcamera.diagnosis.ThresholdTable
import dev.halcamera.diagnosis.jsonName
import dev.halcamera.telemetry.Event
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Writes the Health Report run JSON (docs/PRODUCT-v0.2.md 13.3) to files/checks/<runId>.json. */
class HealthReport(private val context: Context) {

    fun write(
        runId: String,
        results: List<AutoCheckRunner.EndpointResult>,
        evaluations: List<CheckEvaluator.EndpointEvaluation>,
        overall: String,
        aborted: String?,
        env: Map<String, Any?>,
        events: List<Event>,
        baselineRefs: Map<String, Map<String, Any?>>
    ): File {
        val dir = File(context.filesDir, "checks").apply { mkdirs() }
        val file = File(dir, "$runId.json")
        val root = JSONObject()
        root.put("schema_version", 2)
        root.put("run_id", runId)
        root.put("product_definition_version", "0.2-draft")
        root.put("threshold_table_version", ThresholdTable.VERSION)
        root.put("metric_definition_version", "0.2-draft")
        root.put("stats_method", "nearest_rank")
        root.put("clock", "elapsedRealtimeNanos")
        root.put("exported_at_utc", utc())
        root.put("aborted", aborted ?: JSONObject.NULL)
        root.put("device", json(mapOf(
            "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL, "build" to Build.DISPLAY, "fingerprint" to Build.FINGERPRINT,
            "sdk" to Build.VERSION.SDK_INT,
            "media_performance_class" to if (Build.VERSION.SDK_INT >= 31) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0
        )))
        root.put("env", json(env))
        root.put("endpoints", JSONArray(results.map { json(it.endpoint.toJsonMap()) }))
        root.put("baseline_ref", json(baselineRefs))
        val eps = JSONArray()
        results.forEachIndexed { i, r ->
            val ev = evaluations.getOrNull(i)
            val o = JSONObject()
            o.put("endpoint_key", r.endpoint.key)
            o.put("session_id", r.session)
            o.put("hard_failure", r.hardFailure ?: JSONObject.NULL)
            o.put("timestamps_ns", json(r.timestamps.mapValues { it.value.toString() }))
            o.put("raw_ms", json(mapOf(
                "open" to r.openMs, "configure" to r.configureMs, "first_started" to r.firstStartedMs, "yuv_proxy" to r.yuvProxyMs,
                "preview_total" to r.previewTotalMs, "close" to r.closeMs,
                "still_image" to r.stillLatenciesMs, "still_result" to r.stillResultLatenciesMs, "shot_to_shot" to r.shotToShotMs
            )))
            if (ev != null) {
                o.put("metric_states", JSONArray(ev.states.map { json(it.toJsonMap()) }))
                o.put("diagnosis", json(ev.diagnosis.toJsonMap()))
                o.put("health", json(ev.health.toJsonMap()))
                ev.observation?.let { obs ->
                    o.put("observation", json(mapOf(
                        "frames" to obs.n, "interval_p50_ms" to obs.intervalP50, "duration_p50_ms" to obs.durationP50,
                        "partial_p50_ms" to obs.partialP50, "exposure_load_p50" to obs.exposureLoadP50, "stall_count" to obs.stallCount,
                        "three_a_stable" to obs.threeAStable, "af_supported" to obs.afSupported
                    )))
                }
            }
            eps.put(o)
        }
        root.put("endpoint_results", eps)
        root.put("health", json(mapOf("level" to overall, "score" to null, "score_visible" to false)))
        root.put("events", JSONArray(events.map { e ->
            json(mapOf("atNs" to e.atNs.toString(), "session" to e.session, "kind" to e.kind, "frame" to e.frame,
                "sensorNs" to e.sensorNs?.toString(), "values" to e.values))
        }))
        file.writeText(root.toString(2))
        return file
    }

    private fun json(map: Map<*, *>): JSONObject = JSONObject().also { o -> map.forEach { (k, v) -> o.put(k.toString(), value(v)) } }
    private fun value(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> json(v)
        is List<*> -> JSONArray(v.map(::value))
        is Enum<*> -> v.jsonName
        is Double -> if (v.isNaN() || v.isInfinite()) JSONObject.NULL else v
        else -> v
    }
    private fun utc() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}
