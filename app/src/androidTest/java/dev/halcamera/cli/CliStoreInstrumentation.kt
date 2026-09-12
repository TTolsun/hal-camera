package dev.halcamera.cli

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Device tests use real JSONObject and AtomicFile, with a deterministic clock and isolated files. */
class CliStoreInstrumentation : Instrumentation() {
    private var exportReports = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        exportReports = arguments?.getString("export_reports") == "true"
        start()
    }

    override fun onStart() {
        if (exportReports) {
            val store = dev.halcamera.benchmark.BenchmarkStore(targetContext)
            val codec = dev.halcamera.benchmark.BenchmarkReport(store)
            val reports = org.json.JSONArray()
            store.files().take(10).forEach { file ->
                check(codec.read(file) != null) { "Unreadable report ${file.name}" }
                val json = JSONObject(file.readText())
                val selected = JSONObject()
                listOf("run_id", "profile", "comparison_contract_id", "metric_definition_version", "stats_method", "clock",
                    "app", "env", "validity", "metrics", "aborted").forEach { key -> selected.put(key, json.opt(key)) }
                reports.put(selected)
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "HALCAM_REPORTS=$reports\n") })
            return
        }
        val directory = File(targetContext.cacheDir, "cli-store-test-${UUID.randomUUID()}")
        var passed = 0
        try {
            val fixture = JSONObject(context.assets.open("protocol-v1.json").bufferedReader().use { it.readText() })
            val decoded = CliJson.decode(fixture.getJSONObject("request"))
            check(decoded.command == "capture" && decoded.camera == "0" && decoded.timeoutMs == 30000L); passed++
            check(CliJson.encode(decoded).toString() == CliJson.encode(CliJson.decode(CliJson.encode(decoded))).toString()); passed++
            var clock = 1000L
            val store = CommandStore(directory) { clock }
            fun command() = CliCommand(UUID.randomUUID().toString(), "capture", "0", null, 30_000)
            fun finish(id: String, owner: CommandStore = store) {
                owner.transition(id, "preparing"); owner.transition(id, "running")
                owner.transition(id, "saving"); owner.transition(id, "succeeded")
            }
            val first = command()
            store.create(first)
            check(store.read(first.id)?.getString("state") == "accepted"); passed++
            check(CliJson.decode(store.read(first.id)!!.getJSONObject("request")) == first); passed++
            finish(first.id)
            store.transition(first.id, "failed") { it.put("error", CliJson.error("TEST", "late callback")) }
            check(store.read(first.id)?.getString("state") == "succeeded"); passed++

            val interrupted = command(); store.create(interrupted)
            val original = File(directory, "${interrupted.id}.json")
            check(original.renameTo(File(directory, "${interrupted.id}.json.bak")))
            val recovered = CommandStore(directory) { clock }
            check(recovered.read(interrupted.id)?.getString("state") == "interrupted"); passed++
            check(recovered.read(first.id)?.getString("state") == "succeeded"); passed++

            val active = command(); recovered.create(active)
            clock += CommandStore.RETENTION_MS + 1
            recovered.cleanup()
            check(recovered.read(first.id) == null && recovered.read(interrupted.id) == null); passed++
            check(recovered.read(active.id)?.getString("state") == "accepted"); passed++

            repeat(CommandStore.MAX_RECORDS + 3) {
                clock++
                val next = command(); recovered.create(next); finish(next.id, recovered)
            }
            recovered.cleanup()
            check(directory.listFiles()!!.count { it.extension == "json" } == CommandStore.MAX_RECORDS + 1); passed++

            val broken = command()
            File(directory, "${broken.id}.json").writeText("{broken")
            try { recovered.read(broken.id); error("Corrupt request was treated as absent") }
            catch (error: CliFailure) { check(error.code == "STORE_FAILED") }; passed++

            val unwritable = command()
            val blockedDirectory = File(directory, "blocked-store")
            val blockedStore = CommandStore(blockedDirectory) { clock }
            check(blockedDirectory.delete())
            blockedDirectory.writeText("not a directory")
            try { blockedStore.create(unwritable); error("Write failure was accepted") }
            catch (error: CliFailure) { check(error.code == "STORE_FAILED") }; passed++

            recovered.failInMemory(active.id, "disk full")
            check(recovered.read(active.id)?.getString("state") == "failed" &&
                recovered.read(active.id)?.getBoolean("durable") == false); passed++
            check(CommandStore(directory) { clock }.read(active.id)?.getString("state") == "interrupted"); passed++

            val privateRecord = JSONObject().put("request", JSONObject()).put("artifacts",
                org.json.JSONArray().put(JSONObject().put("source_uri", "file:///private").put("artifact_id", "file-0")))
            val public = CliJson.publicRecord(privateRecord)
            check(!public.has("request") && !public.getJSONArray("artifacts").getJSONObject(0).has("source_uri")); passed++
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "CLI_STORE_TESTS_PASSED=$passed\n") })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "CLI_STORE_TESTS_FAILED after $passed: ${error.stackTraceToString()}\n") })
        } finally { directory.deleteRecursively() }
    }
}
