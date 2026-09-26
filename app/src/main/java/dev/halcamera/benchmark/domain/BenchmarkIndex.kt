package dev.halcamera.benchmark.domain

/**
 * Baseline pointers (docs/PLAN-BenchMarker-v0.3.md 7.1): (comparisonContractId, endpoint.key) -> run_id.
 * Pure; the file boundary is in [dev.halcamera.benchmark.platform.BenchmarkStore]. Baselines are set explicitly by the user (M4); M1 only
 * defines the storage shape so the index format is part of the frozen contract.
 */
data class BenchmarkIndex(val baselines: Map<String, String> = emptyMap()) {
    fun baseline(contractId: String, endpointKey: String): String? = baselines[key(contractId, endpointKey)]

    fun isBaseline(run: BenchmarkRun): Boolean = baseline(run.contract.comparisonContractId, run.endpoint.key) == run.runId

    /**
     * Splits a history list into the runs that are a baseline and the rest, each keeping its order. The history
     * screen lists the baselines first: a baseline is usually the oldest run of its camera, so in a newest-first
     * list it sat at the very bottom, marked only by a grey badge (2026-09-26).
     */
    fun baselinesFirst(runs: List<BenchmarkRun>): Pair<List<BenchmarkRun>, List<BenchmarkRun>> = runs.partition(::isBaseline)

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
