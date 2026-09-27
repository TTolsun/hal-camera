package dev.halcamera.benchmark.domain

/**
 * Baseline sets (docs/PLAN-BenchMarker-v0.3.md 7.1): (comparisonContractId, endpoint.key) -> run ids, in the order
 * they were added. Pure; the file boundary is in [dev.halcamera.benchmark.platform.BenchmarkStore]. Baselines are
 * set explicitly by the user.
 *
 * A key held one run id until schema 1. One run could not be a baseline on a camera whose launch alternates between
 * two modes: whichever mode that run happened to catch decided every later verdict (#165). Schema 2 keeps a set,
 * and a schema 1 file reads as a set of one.
 */
data class BenchmarkIndex(val baselines: Map<String, List<String>> = emptyMap()) {
    fun baselines(contractId: String, endpointKey: String): List<String> = baselines[key(contractId, endpointKey)].orEmpty()

    fun isBaseline(run: BenchmarkRun): Boolean = run.runId in baselines(run.contract.comparisonContractId, run.endpoint.key)

    /** Every run id that belongs to some baseline set; retention never deletes these. */
    val allRunIds: Set<String> get() = baselines.values.flatten().toSet()

    /**
     * Splits a history list into the runs that are a baseline and the rest, each keeping its order. The history
     * screen lists the baselines first: a baseline is usually the oldest run of its camera, so in a newest-first
     * list it sat at the very bottom, marked only by a grey badge (2026-09-26).
     */
    fun baselinesFirst(runs: List<BenchmarkRun>): Pair<List<BenchmarkRun>, List<BenchmarkRun>> = runs.partition(::isBaseline)

    fun withAdded(contractId: String, endpointKey: String, runId: String): BenchmarkIndex {
        val k = key(contractId, endpointKey)
        val ids = baselines[k].orEmpty()
        return if (runId in ids) this else BenchmarkIndex(baselines + (k to ids + runId))
    }

    fun withRemoved(contractId: String, endpointKey: String, runId: String): BenchmarkIndex {
        val k = key(contractId, endpointKey)
        val ids = baselines[k].orEmpty() - runId
        return BenchmarkIndex(if (ids.isEmpty()) baselines - k else baselines + (k to ids))
    }

    /** Drops ids of runs that no longer exist, and a set left empty by that. */
    fun retaining(existingRunIds: Set<String>): BenchmarkIndex =
        BenchmarkIndex(baselines.mapValues { (_, ids) -> ids.filter { it in existingRunIds } }.filterValues { it.isNotEmpty() })

    fun toJsonMap(): Map<String, Any?> = mapOf("schema_version" to SCHEMA_VERSION, "baselines" to baselines)

    companion object {
        const val SCHEMA_VERSION = 2
        fun key(contractId: String, endpointKey: String) = "$contractId|$endpointKey"

        /** Reads schema 2 lists and schema 1 single ids alike; anything else under a key is dropped. */
        fun fromJsonMap(m: Map<String, Any?>?): BenchmarkIndex {
            val b = JsonMaps.map(m?.get("baselines")) ?: return BenchmarkIndex()
            return BenchmarkIndex(b.mapNotNull { (k, v) ->
                val ids = when (v) {
                    is String -> listOf(v)
                    is List<*> -> v.filterIsInstance<String>().distinct()
                    else -> emptyList()
                }
                ids.takeIf { it.isNotEmpty() }?.let { k to it }
            }.toMap())
        }
    }
}
