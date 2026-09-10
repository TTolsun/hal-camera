package dev.halcamera.benchmark

/**
 * The run directory as the baseline and reference logic needs it. Keeping it an interface is what makes the
 * whole of [BaselineManager] testable on the JVM: the app passes [StoreRunCatalog] over files, a test passes an
 * in-memory map, and neither the pointer rules nor the reference rule are duplicated between them.
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

/** [RunCatalog] over files/benchmarks/: [BenchmarkStore] holds the index, [BenchmarkReport] reads the runs. */
class StoreRunCatalog(private val store: BenchmarkStore, private val report: BenchmarkReport) : RunCatalog {
    override fun index(): BenchmarkIndex = store.index()
    override fun saveIndex(index: BenchmarkIndex) = store.saveIndex(index)
    override fun runIds(): Set<String> = store.runIds()
    override fun runIdsNewestFirst(): List<String> = store.files().map { it.nameWithoutExtension }
    override fun load(runId: String): BenchmarkRun? = store.file(runId).takeIf { it.exists() }?.let { report.read(it) }
}

/**
 * BASELINE pointers and REFERENCE lookup (docs/PLAN-BenchMarker-v0.3.md 7.1).
 *
 * A baseline is never created automatically: v0.2 made the first run the baseline and that run carried the
 * stream start-up artefact for the rest of the device's life (checkpoint-007). It is set only by
 * `SET AS BASELINE`, only from a comparison-eligible run, and it survives a fingerprint change on purpose,
 * because tracking regressions across builds is the point of the product.
 */
class BaselineManager(private val catalog: RunCatalog) {

    enum class SetOutcome { SET, CLEARED, NOT_ELIGIBLE }

    /**
     * The baseline run id for a (contract, endpoint) key. A pointer to a run whose file is gone is removed here
     * and the index is rewritten, so a deleted baseline returns to NO_BASELINE by itself.
     */
    fun pointer(contractId: String, endpointKey: String): String? {
        val index = catalog.index()
        val id = index.baseline(contractId, endpointKey) ?: return null
        if (id in catalog.runIds()) return id
        catalog.saveIndex(index.retaining(catalog.runIds()))
        return null
    }

    fun pointer(run: BenchmarkRun): String? = pointer(run.contract.comparisonContractId, run.endpoint.key)

    fun isBaseline(run: BenchmarkRun): Boolean = pointer(run) == run.runId

    /** The baseline run itself, or null when there is no pointer or its file cannot be read. */
    fun baselineRun(current: BenchmarkRun): BenchmarkRun? {
        val id = pointer(current) ?: return null
        return if (id == current.runId) current else catalog.load(id)
    }

    /**
     * `SET AS BASELINE`. A comparison-ineligible run (5.3) is refused, so the rule holds even if a caller
     * forgets to disable the button. Pointing at another run simply overwrites the pointer.
     */
    fun set(run: BenchmarkRun): SetOutcome {
        if (!run.validity.comparisonEligible) return SetOutcome.NOT_ELIGIBLE
        catalog.saveIndex(catalog.index().withBaseline(run.contract.comparisonContractId, run.endpoint.key, run.runId))
        return SetOutcome.SET
    }

    /**
     * `CLEAR BASELINE`. Clearing removes the pointer only; the run file stays, because a configuration ceasing
     * to be the reference and its measurement ceasing to be worth keeping are different things (7.1).
     */
    fun clear(run: BenchmarkRun): SetOutcome {
        catalog.saveIndex(catalog.index().withBaseline(run.contract.comparisonContractId, run.endpoint.key, null))
        return SetOutcome.CLEARED
    }

    /** One button, two labels (8.4): `SET AS BASELINE` on any other run, `CLEAR BASELINE` on the baseline itself. */
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
