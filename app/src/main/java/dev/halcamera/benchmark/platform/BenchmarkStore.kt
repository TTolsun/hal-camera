package dev.halcamera.benchmark.platform

import android.content.Context
import android.util.Log
import dev.halcamera.benchmark.domain.AtomicFiles
import dev.halcamera.benchmark.domain.BenchmarkIndex
import dev.halcamera.benchmark.domain.RunDeletion
import org.json.JSONObject
import java.io.File

/** files/benchmarks/: one JSON per run plus index.json. */
class BenchmarkStore(val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, DIR_NAME))

    init { dir.mkdirs() }

    fun file(runId: String): File {
        require(runId.isNotBlank() && runId != "index" && runId.none { it == '/' || it == '\\' || it == ':' }) { "Invalid run id" }
        return File(dir, "$runId.json")
    }

    fun deleteRun(runId: String): Boolean {
        val index = index()
        check(lastIndexError == null) { "Baseline index is unreadable; deletion was cancelled" }
        return RunDeletion.delete(runId, index, { file(runId).delete() }, ::saveIndex)
    }

    /** Run files newest first. Run ids are timestamps, so name order is time order. Stray .tmp files are ignored. */
    fun files(): List<File> = dir.listFiles()?.filter { it.extension == "json" && it.name != INDEX_NAME }?.sortedByDescending { it.name }.orEmpty()

    fun runIds(): Set<String> = files().map { it.nameWithoutExtension }.toSet()

    /** A corrupt index is reported through [lastIndexError] and the log, not hidden as "no baselines". */
    var lastIndexError: String? = null
        private set

    fun index(): BenchmarkIndex {
        val f = File(dir, INDEX_NAME)
        if (!f.exists()) { lastIndexError = null; return BenchmarkIndex() }
        return try {
            lastIndexError = null
            BenchmarkIndex.fromJsonMap(BenchmarkReport.toMap(JSONObject(f.readText())))
        } catch (e: Exception) {
            lastIndexError = "$INDEX_NAME: ${e.message}"
            Log.w(TAG, "corrupt $INDEX_NAME, baselines unavailable: ${e.message}")
            BenchmarkIndex()
        }
    }

    fun saveIndex(index: BenchmarkIndex) {
        AtomicFiles.write(File(dir, INDEX_NAME), BenchmarkReport.json(index.toJsonMap()).toString(2))
    }

    companion object {
        private const val TAG = "BenchmarkStore"
        const val DIR_NAME = "benchmarks"
        const val INDEX_NAME = "index.json"
    }
}
