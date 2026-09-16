package dev.halcamera.benchmark.platform

import dev.halcamera.benchmark.domain.BenchmarkIndex
import dev.halcamera.benchmark.domain.BenchmarkRun
import dev.halcamera.benchmark.domain.RunCatalog

/** [RunCatalog] over files/benchmarks/: [BenchmarkStore] holds the index, [BenchmarkReport] reads the runs. */
class StoreRunCatalog(private val store: BenchmarkStore, private val report: BenchmarkReport) : RunCatalog {
    override fun index(): BenchmarkIndex = store.index()
    override fun saveIndex(index: BenchmarkIndex) = store.saveIndex(index)
    override fun runIds(): Set<String> = store.runIds()
    override fun runIdsNewestFirst(): List<String> = store.files().map { it.nameWithoutExtension }
    override fun load(runId: String): BenchmarkRun? = store.file(runId).takeIf { it.exists() }?.let { report.read(it) }
}
