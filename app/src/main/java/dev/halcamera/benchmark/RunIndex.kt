package dev.halcamera.benchmark

/** Eligibility is derived by the report reader, just as it is for comparison. */
enum class RunFilter(val label: String) {
    ALL("전체"), COMPARISON("비교 가능만"), SCORING("점수 산정 가능만");

    fun accepts(run: BenchmarkRun): Boolean = when (this) {
        ALL -> true
        COMPARISON -> run.validity.comparisonEligible
        SCORING -> run.validity.scoringEligible
    }
}

/** Compact, in-memory history. Raw events and sample arrays never stay resident in the list. */
data class RunIndex(val runs: List<BenchmarkRun>, val unreadableIds: List<String>) {
    fun filtered(filter: RunFilter, profileId: String? = null, endpointKey: String? = null) = runs.filter {
        filter.accepts(it) && (profileId == null || it.profile.id == profileId) &&
            (endpointKey == null || it.endpoint.key == endpointKey)
    }

    companion object {
        fun load(catalog: RunCatalog): RunIndex {
            val unreadable = mutableListOf<String>()
            val runs = catalog.runIdsNewestFirst().mapNotNull { id ->
                val run = catalog.load(id)
                if (run == null || run.runId != id) {
                    unreadable += id
                    null
                } else run.copy(
                    events = emptyList(),
                    raw = mapOf("observation" to mapOf("exposure_load_p50" to RegressionDetector.exposureLoad(run))),
                    metrics = run.metrics.map { it.copy(samples = null, excludedWarmup = null) }
                )
            }
            return RunIndex(runs.sortedByDescending { it.runId }, unreadable)
        }
    }
}

/** Removal must succeed before baseline pointers are cleared. Runs referenced by old JSON stay immutable. */
object RunDeletion {
    fun delete(runId: String, index: BenchmarkIndex, remove: () -> Boolean, save: (BenchmarkIndex) -> Unit): Boolean {
        if (!remove()) return false
        val next = BenchmarkIndex(index.baselines.filterValues { it != runId })
        if (next != index) save(next)
        return true
    }
}
