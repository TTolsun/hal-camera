package dev.halcamera.benchmark.platform

import android.content.Context
import dev.halcamera.benchmark.domain.BenchmarkReportCodec
import dev.halcamera.benchmark.domain.BenchmarkRun
import dev.halcamera.benchmark.domain.Category
import dev.halcamera.benchmark.domain.JsonMaps
import dev.halcamera.benchmark.domain.ProfileArchive
import dev.halcamera.benchmark.domain.ProfileEntry
import dev.halcamera.benchmark.domain.RegressionDetector
import dev.halcamera.benchmark.domain.RegressionState
import dev.halcamera.metrics.jsonName
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Optional raw provenance: installation-scoped, stable across app updates, reset by app-data deletion. */
object DeviceInstance {
    @Synchronized fun id(context: Context): String {
        val preferences = context.getSharedPreferences("profile-device", Context.MODE_PRIVATE)
        preferences.getString("id", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        check(preferences.edit().putString("id", id).commit()) { "Cannot save the device install ID." }
        return id
    }
}

class ProfileLibrary(context: Context) {
    private val store = BenchmarkStore(context)
    private val root = File(context.filesDir, "profile-imports")
    private val archive = ProfileArchive(root, ::parse)
    val resultFile = File(context.filesDir, "profile-comparison-result.txt")

    fun import(input: InputStream) = archive.import(input)

    data class Loaded(val entries: List<ProfileEntry>, val errors: List<String>)
    fun load(): Loaded {
        val errors = mutableListOf<String>()
        val entries = (store.files().map { it to false } + archive.files().map { it to true }).mapNotNull { (file, imported) ->
            try {
                val bytes = file.inputStream().use(ProfileArchive::boundedRead)
                val hash = ProfileArchive.hash(bytes)
                if (imported) require(file.nameWithoutExtension == hash) { "Source hash mismatch" }
                val run = parse(ProfileArchive.decodeUtf8(bytes))
                val compact = run.copy(events = emptyList(), raw = mapOf(
                    "device_instance_id" to JsonMaps.s(run.raw["device_instance_id"]),
                    "observation" to mapOf("exposure_load_p50" to RegressionDetector.exposureLoad(run))),
                    // Saved verdicts and scores are never used by the analysis or raw-data view.
                    endpointScore = null, scoringRuleVersion = null,
                    metrics = run.metrics.map { it.copy(samples = null, excludedWarmup = null, score = null,
                        regression = RegressionState.UNKNOWN, baselineValue = null, deltaPct = null) })
                ProfileEntry("${if (imported) "import" else "local"}:$hash", hash,
                    if (imported) "Imported JSON" else "Local record", compact)
            } catch (e: Exception) { errors += "${file.name}: ${e.message}"; null }
        }
        return Loaded(entries.sortedByDescending { it.run.runId }, errors)
    }

    fun deleteImported(entry: ProfileEntry) {
        require(entry.key == "import:${entry.sha256}" && entry.sha256.matches(Regex("[0-9a-f]{64}")))
        check(File(root, "${entry.sha256}.json").delete()) { "Cannot delete the imported file." }
    }

    companion object {
        fun parse(text: String): BenchmarkRun {
            ProfileArchive.validateDepth(text)
            val tokener = JSONTokener(text)
            val json = tokener.nextValue() as? JSONObject ?: error("A JSON object is required.")
            require(tokener.nextClean() == '\u0000') { "Extra data after the JSON." }
            val map = BenchmarkReport.toMap(json)
            fun objectField(key: String) = requireNotNull(JsonMaps.map(map[key])) { "Missing $key object." }
            require(map["schema_version"] is Number && (map["schema_version"] as Number).toDouble() in listOf(3.0, 4.0)) { "Unsupported schema_version." }
            val compatibility = objectField("compatibility")
            require(compatibility["supported"] is Boolean) { "Missing compatibility.supported." }
            val endpoint = objectField("endpoint")
            require(endpoint["logicalCameraId"] is String && endpoint["role"] in dev.halcamera.camera.LensRole.entries.map { it.name } &&
                endpoint["facing"] is Number && (endpoint["facing"] as Number).toDouble() in listOf(0.0, 1.0, 2.0)) { "Invalid camera identity fields." }
            val metrics = map["metrics"] as? List<*> ?: error("A metrics array is required.")
            require(metrics.all { raw ->
                val m = JsonMaps.map(raw)
                m != null && m["timeout"] is Boolean && m.containsKey("value") &&
                    (m["value"] == null || m["value"] is Number) && m["n"] is Number &&
                    (m["n"] as Number).toDouble().let { it.isFinite() && it in 0.0..Int.MAX_VALUE.toDouble() && it % 1.0 == 0.0 } &&
                    m["category"] in Category.entries.map { it.jsonName }
            }) { "Malformed metric fields." }
            val validity = JsonMaps.map(map["validity"])
            require(validity != null && validity["flags"] is List<*> && (validity["flags"] as List<*>).all { it is String }) { "Missing or malformed validity.flags." }
            val env = JsonMaps.map(map["env"])
            require(env != null && listOf("charging", "power_save_mode").all { env.containsKey(it) && (env[it] == null || env[it] is Boolean) }) { "Missing or malformed env fields." }
            require(listOf("thermal_start", "thermal_max", "thermal_end").all { key ->
                env.containsKey(key) && (env[key] == null || env[key] is Number &&
                    (env[key] as Number).toDouble().let { it in 0.0..6.0 && it % 1.0 == 0.0 })
            }) { "Malformed thermal fields." }
            return BenchmarkReportCodec.fromJsonMap(map).also(ProfileArchive::validate)
        }
    }
}
