package dev.cameradoctor.benchmark

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Baseline pointers (docs/PLAN-BenchMarker-v0.3.md 7.1): (comparisonContractId, endpoint.key) -> run_id.
 * Pure; the file boundary is in [BenchmarkStore]. Baselines are set explicitly by the user (M4); M1 only
 * defines the storage shape so the index format is part of the frozen contract.
 */
data class BenchmarkIndex(val baselines: Map<String, String> = emptyMap()) {
    fun baseline(contractId: String, endpointKey: String): String? = baselines[key(contractId, endpointKey)]

    fun withBaseline(contractId: String, endpointKey: String, runId: String?): BenchmarkIndex {
        val k = key(contractId, endpointKey)
        return BenchmarkIndex(if (runId == null) baselines - k else baselines + (k to runId))
    }

    /** Drops pointers to runs that no longer exist. */
    fun retaining(existingRunIds: Set<String>): BenchmarkIndex = BenchmarkIndex(baselines.filterValues { it in existingRunIds })

    fun toJsonMap(): Map<String, Any?> = mapOf("schema_version" to SCHEMA_VERSION, "baselines" to baselines)

    companion object {
        const val SCHEMA_VERSION = 1
        fun key(contractId: String, endpointKey: String) = "$contractId|$endpointKey"

        fun fromJsonMap(m: Map<String, Any?>?): BenchmarkIndex {
            val b = JsonMaps.map(m?.get("baselines")) ?: return BenchmarkIndex()
            return BenchmarkIndex(b.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap())
        }
    }
}

/** files/benchmarks/: one JSON per run plus index.json. */
class BenchmarkStore(val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, DIR_NAME))

    init { dir.mkdirs() }

    fun file(runId: String): File = File(dir, "$runId.json")

    /** Run files newest first. Run ids are timestamps, so name order is time order. */
    fun files(): List<File> = dir.listFiles()?.filter { it.extension == "json" && it.name != INDEX_NAME }?.sortedByDescending { it.name }.orEmpty()

    fun runIds(): Set<String> = files().map { it.nameWithoutExtension }.toSet()

    fun index(): BenchmarkIndex = try {
        val f = File(dir, INDEX_NAME)
        if (f.exists()) BenchmarkIndex.fromJsonMap(BenchmarkReport.toMap(JSONObject(f.readText()))) else BenchmarkIndex()
    } catch (_: Exception) { BenchmarkIndex() }

    fun saveIndex(index: BenchmarkIndex) {
        File(dir, INDEX_NAME).writeText(BenchmarkReport.json(index.toJsonMap()).toString(2))
    }

    companion object {
        const val DIR_NAME = "benchmarks"
        const val INDEX_NAME = "index.json"
    }
}
