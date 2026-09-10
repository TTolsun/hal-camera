package dev.halcamera.check

import dev.halcamera.diagnosis.MetricState
import org.json.JSONObject
import java.io.File

/** What the result screen renders: built either from a fresh run or read back from a stored run JSON. */
data class CheckResult(
    val runId: String,
    val level: String,
    val aborted: String?,
    val baselineCreated: Boolean,
    val endpoints: List<Endpoint>,
    val file: File?
) {
    data class Endpoint(val role: String, val key: String, val level: String, val rule: String, val states: List<MetricState>, val rawMs: Map<String, Any?>, val hardFailure: String?)

    companion object {
        fun fromEvaluations(runId: String, level: String, aborted: String?, evaluations: List<CheckEvaluator.EndpointEvaluation>,
                            results: List<AutoCheckRunner.EndpointResult>, baselineCreated: Boolean, file: File?): CheckResult =
            CheckResult(runId, level, aborted, baselineCreated, evaluations.mapIndexed { i, ev ->
                val r = results[i]
                Endpoint(ev.endpoint.role.name, ev.endpoint.key, ev.health.level.name.lowercase(), ev.diagnosis.rule, ev.states,
                    mapOf("open" to r.openMs, "configure" to r.configureMs, "first_started" to r.firstStartedMs, "yuv_proxy" to r.yuvProxyMs,
                        "preview_total" to r.previewTotalMs, "close" to r.closeMs, "still_image" to r.stillLatenciesMs), r.hardFailure)
            }, file)

        fun fromFile(file: File): CheckResult? = try {
            val o = JSONObject(file.readText())
            val eps = o.getJSONArray("endpoints")
            val res = o.getJSONArray("endpoint_results")
            val refs = o.optJSONObject("baseline_ref")
            val created = refs?.keys()?.asSequence()?.any { refs.optJSONObject(it)?.optBoolean("created_now") == true } ?: false
            CheckResult(o.getString("run_id"), o.getJSONObject("health").getString("level"), o.optString("aborted").takeIf { it.isNotEmpty() && it != "null" }, created,
                (0 until res.length()).map { i ->
                    val r = res.getJSONObject(i)
                    val ep = eps.optJSONObject(i)
                    val states = r.optJSONArray("metric_states")?.let { arr -> (0 until arr.length()).map { MetricState.fromJsonMap(toMap(arr.getJSONObject(it))) } } ?: emptyList()
                    Endpoint(ep?.optString("role") ?: "UNKNOWN", r.optString("endpoint_key"), r.optJSONObject("health")?.optString("level") ?: "insufficient",
                        r.optJSONObject("diagnosis")?.optString("rule") ?: "normal", states, r.optJSONObject("raw_ms")?.let(::toMap) ?: emptyMap(),
                        r.optString("hard_failure").takeIf { it.isNotEmpty() && it != "null" })
                }, file)
        } catch (_: Exception) { null }

        private fun toMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { k -> o.opt(k).let { if (it == JSONObject.NULL) null else it } }
    }
}
