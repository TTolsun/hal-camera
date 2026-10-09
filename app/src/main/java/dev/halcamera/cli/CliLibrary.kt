package dev.halcamera.cli

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dev.halcamera.benchmark.domain.*
import dev.halcamera.benchmark.platform.*
import org.json.JSONObject
import java.io.File

/** Uses the same reports and baseline rules as Results; media IDs are resolved only inside HALCamera. */
class CliLibrary(private val context: Context, private val commands: CommandCoordinator) {
    fun execute(command: CliCommand) {
        val options = command.options?.values.orEmpty()
        if (command.command.startsWith("incidents.")) { incidents(command); return }
        if (command.command.startsWith("settings.")) { settings(command); return }
        if (command.command.startsWith("gallery.")) { gallery(command); return }
        val store = BenchmarkStore(context)
        val report = BenchmarkReport(store)
        val catalog = StoreRunCatalog(store, report)
        store.index()
        if (store.lastIndexError != null) throw CliFailure("INDEX_UNREADABLE", "Baseline index is unreadable; repair it before changing results")
        val baselines = BaselineManager(catalog)
        fun load(id: String): BenchmarkRun = catalog.load(id) ?: throw CliFailure("RESULT_NOT_FOUND", "Run is missing or unreadable; use results list")
        val id = options["run"]
        val run = id?.takeUnless { command.command == "results.delete" }?.let(::load)
        val result = when (command.command) {
            "results.list" -> JSONObject().put("runs", CliJson.of(store.files().map { file ->
                val value = catalog.load(file.nameWithoutExtension)
                mapOf("run_id" to file.nameWithoutExtension, "readable" to (value != null),
                    "baseline" to (value?.let(baselines::isBaseline) ?: false), "camera" to value?.endpoint?.key,
                    "profile" to value?.contract?.profileId, "subject" to value?.subject?.toJsonMap(),
                    "comparison_eligible" to value?.validity?.comparisonEligible)
            }))
            "results.show" -> {
                val current = run!!
                val resolved = baselines.resolve(current)
                val view = ResultPresenter.present(current, resolved.comparison(current), resolved.comparedTo,
                    baselines.isBaseline(current), current.device.model, current.endpoint.key)
                (CliJson.of(BenchmarkReportCodec.toJsonMap(current)) as JSONObject).put("summary", view.render())
                    .put("compared_to", resolved.comparedTo.name).put("reference_ids", CliJson.of(resolved.runs.map { it.runId }))
            }
            "results.compare" -> {
                val reference = load(options.getValue("reference"))
                // Like History: an explicitly selected reference is the baseline for this comparison only.
                val kind = ComparedTo.BASELINE
                val view = ComparePresenter.present(reference, run!!, RegressionDetector.compare(reference, run), kind, selectedReference = true)
                JSONObject().put("comparison", view.render()).put("compared_to", kind.name)
            }
            "baseline.add" -> {
                val outcome = baselines.set(run!!)
                if (outcome == BaselineManager.SetOutcome.NOT_ELIGIBLE) throw CliFailure("NOT_ELIGIBLE", "Run is not comparison eligible; choose another run")
                JSONObject().put("baseline", true).put("run_id", id)
            }
            "baseline.remove" -> { baselines.clear(run!!); JSONObject().put("baseline", false).put("run_id", id) }
            "results.delete" -> {
                if (!store.deleteRun(id!!)) throw CliFailure("DELETE_FAILED", "Run could not be deleted")
                JSONObject().put("deleted", id)
            }
            "results.export" -> {
                val dir = commands.artifactDir(command.id)
                val json = File(dir, "run.json").apply { writeText(store.file(id!!).readText()) }
                val csv = File(dir, "metrics.csv").apply { bufferedWriter().use { BenchmarkCsv.write(sequenceOf(run!!), it) } }
                commands.complete(command.id, JSONObject().put("run_id", id).put("artifact_count", 2), listOf(
                    CliArtifact(json.name, "application/json", Uri.fromFile(json)), CliArtifact(csv.name, "text/csv", Uri.fromFile(csv))))
                return
            }
            else -> throw CliFailure("INVALID_ARGUMENT", "Unknown library command")
        }
        commands.complete(command.id, result)
    }

    private fun incidents(command: CliCommand) {
        val dir = File(context.filesDir, "incidents")
        val files = dir.listFiles()?.filter { it.isFile && it.extension == "zip" }.orEmpty()
        if (command.command == "incidents.list") {
            commands.complete(command.id, JSONObject().put("incidents", CliJson.of(files.sortedByDescending { it.name }.map {
                mapOf("incident_id" to it.nameWithoutExtension, "bytes" to it.length())
            }))); return
        }
        val file = files.firstOrNull { it.nameWithoutExtension == command.options!!.values["incident"] }
            ?: throw CliFailure("ARTIFACT_MISSING", "ZIP not found; use incidents list")
        if (command.command == "incidents.delete") {
            if (!file.delete()) throw CliFailure("DELETE_FAILED", "ZIP could not be deleted")
            commands.complete(command.id, JSONObject().put("deleted", file.nameWithoutExtension))
        } else commands.complete(command.id, JSONObject().put("artifact_count", 1), listOf(CliArtifact(file.name, "application/zip", Uri.fromFile(file))))
    }

    private fun settings(command: CliCommand) {
        val prefs = BenchmarkPrefs(context)
        if (command.command == "settings.show") {
            commands.complete(command.id, commands.hello().put("run_limit", prefs.runLimit)); return
        }
        val store = BenchmarkStore(context)
        store.index()
        if (store.lastIndexError != null) throw CliFailure("INDEX_UNREADABLE", "Baseline index is unreadable")
        val limit = command.options!!.values.getValue("limit").toInt()
        val protected = store.index().allRunIds
        val deleting = RunRetention.toDelete(store.files().map { it.nameWithoutExtension }, protected, limit)
        val apply = command.options.values["confirm"] == "true"
        if (apply) {
            deleting.forEach { if (!store.deleteRun(it)) throw CliFailure("DELETE_FAILED", "Could not delete $it; retention setting was not changed") }
            prefs.runLimit = limit
        }
        commands.complete(command.id, JSONObject().put("run_limit", limit).put("applied", apply)
            .put("delete_runs", CliJson.of(deleting)).put("next", if (apply) "settings show" else "settings limit --limit $limit --confirm true"))
    }

    data class Media(val id: String, val name: String, val mime: String, val bytes: Long, val uri: Uri)

    @Suppress("DEPRECATION")
    fun media(): List<Media> = buildList {
        val collection = MediaStore.Files.getContentUri("external")
        val path = if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val clause = if (Build.VERSION.SDK_INT >= 29) "($path = ? OR $path = ?) AND is_pending = 0" else "($path LIKE ? OR $path LIKE ?)"
        val paths = if (Build.VERSION.SDK_INT >= 29) arrayOf("DCIM/HALCamera/", "Download/HALCamera/") else arrayOf("%/DCIM/HALCamera/%", "%/Download/HALCamera/%")
        context.contentResolver.query(collection, arrayOf("_id", "_display_name", "mime_type", "_size"), clause, paths, "date_added DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val row = cursor.getLong(0)
                add(Media(row.toString(), cursor.getString(1), cursor.getString(2) ?: "application/octet-stream", cursor.getLong(3), ContentUris.withAppendedId(collection, row)))
            }
        }
    }

    private fun gallery(command: CliCommand) {
        val entries = media()
        if (command.command == "gallery.list") {
            commands.complete(command.id, JSONObject().put("media", CliJson.of(entries.map { mapOf("media_id" to it.id, "name" to it.name, "mime" to it.mime, "bytes" to it.bytes) })))
            return
        }
        val item = entries.firstOrNull { it.id == command.options?.values?.get("media") }
            ?: throw CliFailure("MEDIA_NOT_FOUND", "HALCamera file not found; use gallery list")
        if (command.command == "gallery.delete") {
            try {
                if (context.contentResolver.delete(item.uri, null, null) != 1) throw CliFailure("DELETE_FAILED", "File was not deleted")
            } catch (e: SecurityException) { throw CliFailure("PERMISSION_REQUIRED", "Android requires owner approval; delete this file in Gallery") }
            commands.complete(command.id, JSONObject().put("deleted", item.id))
        } else {
            commands.complete(command.id, JSONObject().put("artifact_count", 1).put("original_name", item.name), listOf(CliArtifact(
                "media_${item.id}." + item.name.substringAfterLast('.', "bin").takeIf { it.matches(Regex("[A-Za-z0-9]+")) }.orEmpty().ifEmpty { "bin" }, item.mime, item.uri)))
        }
    }
}
