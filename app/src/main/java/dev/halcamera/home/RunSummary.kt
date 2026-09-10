package dev.halcamera.home

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
    val consumerText: String?,
    val aborted: String? = null,
    /** Run id of a newer run that was aborted, when the summary shown is the latest completed one. */
    val newerAbortedRunId: String? = null
) {
    data class EndpointLine(val role: String, val level: String, val diagnosis: String)

    companion object {
        fun files(context: Context): List<File> =
            File(context.filesDir, "checks").listFiles()?.filter { it.extension == "json" }
                ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name }).orEmpty()

        fun latestFile(context: Context): File? = files(context).firstOrNull()

        /**
         * The home verdict is the latest *completed* run. An aborted run is a partial result and must not replace it;
         * it is mentioned as a note instead. If every stored run is aborted, the latest aborted one is shown as-is.
         */
        fun load(context: Context): RunSummary? {
            val parsed = files(context).mapNotNull { parse(it) }
            if (parsed.isEmpty()) return null
            val completed = parsed.firstOrNull { it.aborted == null } ?: return parsed.first()
            val newest = parsed.first()
            return if (newest.aborted != null && newest.runId != completed.runId) completed.copy(newerAbortedRunId = newest.runId) else completed
        }

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
            val aborted = o.optString("aborted").takeIf { it.isNotEmpty() && it != "null" }
            RunSummary(o.getString("run_id"), o.getJSONObject("health").getString("level"), file, lines, created, null, aborted)
        } catch (_: Exception) { null }
    }
}
