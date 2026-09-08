package dev.cameradoctor.home

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Reads the latest Health Report run JSON for the home screen. Never throws; missing data is null. */
data class RunSummary(
    val runId: String,
    val level: String,
    val file: File,
    val endpoints: List<EndpointLine>,
    val baselineCreated: Boolean,
    val consumerText: String?
) {
    data class EndpointLine(val role: String, val level: String, val diagnosis: String)

    companion object {
        fun latestFile(context: Context): File? =
            File(context.filesDir, "checks").listFiles()?.filter { it.extension == "json" }?.maxByOrNull { it.lastModified() }

        fun load(context: Context): RunSummary? = latestFile(context)?.let { parse(it) }

        fun parse(file: File): RunSummary? = try {
            val o = JSONObject(file.readText())
            val eps = o.getJSONArray("endpoint_results")
            val endpoints = o.getJSONArray("endpoints")
            val lines = (0 until eps.length()).map { i ->
                val e = eps.getJSONObject(i)
                val role = endpoints.optJSONObject(i)?.optString("role") ?: "UNKNOWN"
                EndpointLine(role, e.optJSONObject("health")?.optString("level") ?: "insufficient", e.optJSONObject("diagnosis")?.optString("rule") ?: "normal")
            }
            val refs = o.optJSONObject("baseline_ref")
            val created = refs?.keys()?.asSequence()?.any { refs.optJSONObject(it)?.optBoolean("created_now") == true } ?: false
            RunSummary(o.getString("run_id"), o.getJSONObject("health").getString("level"), file, lines, created, null)
        } catch (_: Exception) { null }
    }
}
