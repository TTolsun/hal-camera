package dev.halcamera.benchmark.domain

/**
 * The run directory as the baseline and reference logic needs it. Keeping it an interface is what makes the
 * whole of [BaselineManager] testable on the JVM: the app passes [dev.halcamera.benchmark.platform.StoreRunCatalog] over files, a test passes an
 * in-memory map, and neither the baseline rules nor the reference rule are duplicated between them.
 */
interface RunCatalog {
    fun index(): BenchmarkIndex
    fun saveIndex(index: BenchmarkIndex)
    fun runIds(): Set<String>

    /** Run ids newest first. Ids are start timestamps, so name order is time order. */
    fun runIdsNewestFirst(): List<String>

    /** null when the run is missing or unreadable. */
    fun load(runId: String): BenchmarkRun?
}

/**
 * Baseline sets and REFERENCE lookup (docs/PLAN-BenchMarker-v0.3.md 7.1).
 *
 * A baseline is never created automatically: v0.2 made the first run the baseline and that run carried the
 * stream start-up artefact for the rest of the device's life (checkpoint-007). A run joins the set only through
 * `baseline에 추가`, only when it is comparison-eligible, and the set survives a fingerprint change on purpose,
 * because tracking regressions across builds is the point of the product.
 *
 * The set replaced a single pointer (#165). One run could not stand for a camera whose launch alternates between
 * two modes: a baseline that caught the fast mode marked every later normal run as degraded. Several normal runs
 * together cover that spread, and [RegressionDetector] judges against their range.
 */
class BaselineManager(private val catalog: RunCatalog) {

    enum class SetOutcome { SET, CLEARED, NOT_ELIGIBLE }

    /**
     * The baseline set for a (contract, endpoint) key, in the order the runs were added. Ids whose file is gone
     * are removed here and the index is rewritten, so deleting every baseline run returns to NO_BASELINE by itself.
     */
    fun members(contractId: String, endpointKey: String): List<String> {
        val index = catalog.index()
        val ids = index.baselines(contractId, endpointKey)
        if (ids.isEmpty()) return ids
        val existing = catalog.runIds()
        if (ids.all { it in existing }) return ids
        catalog.saveIndex(index.retaining(existing))
        return ids.filter { it in existing }
    }

    fun members(run: BenchmarkRun): List<String> = members(run.contract.comparisonContractId, run.endpoint.key)

    fun isBaseline(run: BenchmarkRun): Boolean = run.runId in members(run)

    /**
     * The runs [current] is judged against: every member of its set except itself. A member is measured against
     * the others, which is what tells whether it belongs in the set; the only member of a set of one has nothing
     * to be judged against. A member whose file cannot be read is skipped.
     */
    fun baselineRuns(current: BenchmarkRun): List<BenchmarkRun> =
        members(current).filter { it != current.runId }.mapNotNull { catalog.load(it) }

    /**
     * What a run's result is measured against: its baseline set when there is one, otherwise the previous run
     * (shown without a verdict), otherwise nothing.
     */
    fun resolve(current: BenchmarkRun): Resolved {
        val bases = baselineRuns(current)
        if (bases.isNotEmpty()) return Resolved(bases, ComparedTo.BASELINE)
        val previous = reference(current) ?: return Resolved(emptyList(), ComparedTo.NONE)
        return Resolved(listOf(previous), ComparedTo.PREVIOUS)
    }

    /** The runs a result is measured against and what they are. With none, [comparison] lists values only. */
    data class Resolved(val runs: List<BenchmarkRun>, val comparedTo: ComparedTo) {
        fun comparison(current: BenchmarkRun): RunComparison = RegressionDetector.compare(runs, current)
    }

    /**
     * `baseline에 추가`. A comparison-ineligible run (5.3) is refused, so the rule holds even if a caller forgets to
     * disable the button. Adding a run that is already a member changes nothing.
     */
    fun set(run: BenchmarkRun): SetOutcome {
        if (!run.validity.comparisonEligible) return SetOutcome.NOT_ELIGIBLE
        val index = catalog.index()
        val next = index.withAdded(run.contract.comparisonContractId, run.endpoint.key, run.runId)
        if (next != index) catalog.saveIndex(next)
        return SetOutcome.SET
    }

    /**
     * `baseline에서 빼기`. It removes the run from the set only; the run file stays, because a configuration ceasing
     * to be the reference and its measurement ceasing to be worth keeping are different things (7.1).
     */
    fun clear(run: BenchmarkRun): SetOutcome {
        catalog.saveIndex(catalog.index().withRemoved(run.contract.comparisonContractId, run.endpoint.key, run.runId))
        return SetOutcome.CLEARED
    }

    /** One button, two labels (8.4): add any other run, remove a run that is already a member. */
    fun toggle(run: BenchmarkRun): SetOutcome = if (isBaseline(run)) clear(run) else set(run)

    /**
     * The most recent eligible earlier run (7.1). Ids are listed newest first, so the scan stops at the first
     * match and normally reads one run file instead of the whole directory.
     */
    fun reference(current: BenchmarkRun): BenchmarkRun? = catalog.runIdsNewestFirst().asSequence()
        .filter { it < current.runId }
        .mapNotNull { catalog.load(it) }
        .firstOrNull { ReferenceResolver.isCandidate(it, current) }
}
