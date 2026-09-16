package dev.halcamera.benchmark.domain

/**
 * Baseline pointers (docs/PLAN-BenchMarker-v0.3.md 7.1): (comparisonContractId, endpoint.key) -> run_id.
 * Pure; the file boundary is in [dev.halcamera.benchmark.platform.BenchmarkStore]. Baselines are set explicitly by the user (M4); M1 only
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
