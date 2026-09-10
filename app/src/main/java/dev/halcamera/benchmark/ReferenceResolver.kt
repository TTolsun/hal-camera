package dev.halcamera.benchmark

/**
 * REFERENCE selection (docs/PLAN-BenchMarker-v0.3.md 7.1): the most recent comparison-eligible run of the same
 * (comparisonContractId, endpoint.key), excluding the run itself. Nothing is stored; the answer is computed
 * whenever a result is shown, so deleting a run changes the reference without any bookkeeping.
 *
 * "Most recent" means the run id, which is the run's start timestamp, and only runs older than the current one
 * qualify: opening an older result must not report a run that did not exist yet as its previous measurement.
 */
object ReferenceResolver {

    /** The one place the reference rule lives; [BaselineManager] applies it while reading run files lazily. */
    fun isCandidate(candidate: BenchmarkRun, current: BenchmarkRun): Boolean =
        candidate.runId < current.runId &&
            candidate.validity.comparisonEligible &&
            candidate.contract.comparisonContractId == current.contract.comparisonContractId &&
            candidate.endpoint.key == current.endpoint.key
}
