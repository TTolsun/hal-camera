package dev.halcamera.benchmark

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.halcamera.benchmark.domain.*
import dev.halcamera.benchmark.platform.*
import dev.halcamera.ui.Look
import java.io.File
import java.util.concurrent.Executors

/** Explicit analysis workspace. Its imports and selections never mutate BenchmarkStore or baseline pointers. */
class ProfileComparisonActivity : ComponentActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val library by lazy { ProfileLibrary(this) }
    private val preferences by lazy { getSharedPreferences("profile-comparison", MODE_PRIVATE) }
    private var entries = emptyList<ProfileEntry>()
    private var errors = emptyList<String>()
    private var before = linkedSetOf<String>()
    private var after = linkedSetOf<String>()
    private var deviceFilter: String? = null
    private var options = ProfileComparison.Options()
    private var busy = false
    private lateinit var body: LinearLayout
    private var resultText: String? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) work({
            require(uris.size <= 50) { "You can import up to 50 files at once." }
            var added = 0; var duplicates = 0
            val failures = mutableListOf<String>()
            uris.forEachIndexed { i, uri ->
                try {
                    val imported = requireNotNull(contentResolver.openInputStream(uri)).use(library::import)
                    if (imported.duplicate) duplicates++ else added++
                } catch (e: Exception) { failures += "File ${i + 1}: ${e.message ?: "read failed"}" }
            }
            Triple(library.load(), "Imported $added · $duplicates existing copies", failures)
        }) { (loaded, summary, failures) ->
            applyLoaded(loaded); errors = failures + errors; message(summary); render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        before = preferences.getStringSet("before", emptySet())!!.toCollection(linkedSetOf())
        after = preferences.getStringSet("after", emptySet())!!.toCollection(linkedSetOf())
        deviceFilter = preferences.getString("filter", null)
        options = ProfileComparison.Options(preferences.getBoolean("different", false),
            preferences.getBoolean("same", false), preferences.getBoolean("independent", false))
        val scroll = ScrollView(this).apply { setBackgroundColor(Look.expertTile) }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left + dp(16), bars.top + dp(16), bars.right + dp(16), bars.bottom + dp(16)); insets
        }
        reload()
    }

    override fun onDestroy() { io.shutdown(); super.onDestroy() }
    private fun reload() = work({ library.load() }) { applyLoaded(it); render() }

    private fun applyLoaded(loaded: ProfileLibrary.Loaded) {
        entries = loaded.entries; errors = loaded.errors
        val keys = entries.map { it.key }.toSet()
        if ((before + after).any { it !in keys }) {
            errors = errors + "A previously selected source was deleted or changed. Recheck the selection before analysis."
            options = options.copy(sameDeviceConfirmed = false, independentRunsConfirmed = false)
        }
        before.retainAll(keys); after.retainAll(keys)
        persist()
    }

    private fun persist() {
        preferences.edit().putStringSet("before", before.toSet()).putStringSet("after", after.toSet())
            .putString("filter", deviceFilter).putBoolean("different", options.differentDevices)
            .putBoolean("same", options.sameDeviceConfirmed).putBoolean("independent", options.independentRunsConfirmed).apply()
        resultText = null
    }

    private fun visible() = entries.filter { deviceFilter == null || it.deviceLabel == deviceFilter }
    private fun render() {
        body.removeAllViews()
        text("Profile comparison", 24)
        text("Repeated-run A/B comparison · analysis separate from baseline")
        if (busy) text("Processing data…")
        button("Import JSON files") { picker.launch(arrayOf("*/*")) }
        button("Device filter · ${deviceFilter ?: "All"}") {
            val models = entries.map { it.deviceLabel }.distinct().sorted()
            AlertDialog.Builder(this).setTitle("Show by model · separate from same-physical-device check")
                .setItems((listOf("All") + models).toTypedArray()) { _, index ->
                    deviceFilter = if (index == 0) null else models[index - 1]; persist(); render()
                }.setNegativeButton("Cancel", null).show()
        }
        button("Select before / A · ${before.size}") { select(true) }
        selectionSummary(before)
        button("Select after / B · ${after.size}") { select(false) }
        selectionSummary(after)
        if (before.intersect(after).isNotEmpty()) text("A/B contain the same source. Remove it from one side.")
        checkbox("Compare across different devices", options.differentDevices) {
            options = options.copy(differentDevices = it, sameDeviceConfirmed = false); persist(); render()
        }
        if (!options.differentDevices) checkbox("Confirm same physical device, including reinstalled or ID-missing data", options.sameDeviceConfirmed) {
            options = options.copy(sameDeviceConfirmed = it); persist(); render()
        }
        checkbox("Independent repeated runs · same scene and lighting confirmed", options.independentRunsConfirmed) {
            options = options.copy(independentRunsConfirmed = it); persist(); render()
        }
        if (options.differentDevices) text("Differences across devices cannot be read as an SW change effect. Check lens role and field of view.")
        button("Analyze selected sets", before.isNotEmpty() && after.isNotEmpty()) {
            val a = entries.filter { it.key in before }; val b = entries.filter { it.key in after }; val selectedOptions = options
            work({
                val result = ProfileComparison.compare(a, b, selectedOptions).render()
                synchronized(ProfileLibrary::class.java) { AtomicFiles.write(library.resultFile, result) }
                result
            }) { resultText = it; render(); showResult(it) }
        }
        button("Reopen last analysis result") {
            work({ check(library.resultFile.isFile) { "No saved analysis result." }; library.resultFile.readText() }) { showResult(it) }
        }
        body.addView(Look.disclosure(this, "Comparison conditions · device ID notes", Look.text(this,
            "Build each set from one device and build. Selections are kept when the filter changes.\n\nThe device ID is per app install and can change after a reinstall or data wipe. IDs in external JSON are not certified hardware identifiers.\n\nImported data stays separate from the existing baseline. On different devices the same camera ID does not mean the same lens.",
            13, Look.onDarkMuted)), lp())
        button("Refresh data list") { reload() }
        resultText?.let { text("Analysis saved · includes source hashes, method, and exclusion reasons") }
        button("Clear selection") { before.clear(); after.clear(); options = ProfileComparison.Options(); persist(); render() }
        errors.forEach { text("Needs review: $it") }
        text("${visible().size} entries · tap a row below to see raw metrics.")
        visible().take(100).forEach { e -> button(e.label + "\nSource ${e.sha256.take(12)}") { showEntry(e) } }
        if (visible().size > 100) text("The list shows up to the latest 100 entries. The set picker can select all data in the current filter.")
        button("Back to history") { finish() }
    }

    private fun selectionSummary(keys: Set<String>) {
        val selected = entries.filter { it.key in keys }
        if (selected.isEmpty()) return
        val groups = selected.groupBy { entry ->
            "${entry.deviceLabel} · ${entry.instanceId?.take(8) ?: "no device ID"}\n" +
                "${entry.run.subject.subjectBuildLabel ?: entry.run.device.buildDisplay} · ${entry.run.subject.subjectCommit ?: "no commit"}"
        }
        text(groups.entries.joinToString("\n") { (identity, runs) -> "$identity · ${runs.size} runs" }, 13)
        if (groups.size > 1) text("Multiple devices or builds are selected. Check the set composition.", 13)
    }

    private fun select(isBefore: Boolean) {
        val rows = visible()
        val original = if (isBefore) before else after
        val choices = original.toMutableSet()
        AlertDialog.Builder(this).setTitle(if (isBefore) "Before / A · up to 50" else "After / B · up to 50")
            .setMultiChoiceItems(rows.map { it.label + "\n${it.sha256.take(12)}" }.toTypedArray(), BooleanArray(rows.size) { rows[it].key in choices }) { _, i, checked ->
                if (checked) choices.add(rows[i].key) else choices.remove(rows[i].key)
            }.setNegativeButton("Cancel", null).setPositiveButton("Apply selection") { _, _ ->
                if (choices.size > RepeatStatistics.MAX_RUNS) message("Select up to 50 per set.")
                else {
                    original.clear(); original.addAll(choices)
                    options = options.copy(sameDeviceConfirmed = false, independentRunsConfirmed = false)
                    persist(); render()
                }
            }.show()
    }

    private fun showEntry(e: ProfileEntry) {
        val r = e.run
        val detail = buildString {
            appendLine(e.label); appendLine("SHA-256 ${e.sha256}"); appendLine("Original run ID: ${r.runId}")
            appendLine("Install ID: ${e.instanceId ?: "unknown"}\n${r.device}\n${r.app}\n${r.subject}")
            appendLine("Contract: ${r.contract.comparisonContractId}\n${r.profile}\n${r.endpoint}\n${r.effectiveConditions}\n${r.env}\n${r.validity}")
            r.metrics.forEach { appendLine("${it.id}: ${it.value ?: "—"} ${it.unit}, n=${it.sampleCount}, timeout=${it.timeout}") }
            appendLine("Stored scores and degradation verdicts are not used as comparison evidence on this screen.")
        }
        val dialog = AlertDialog.Builder(this).setTitle("Measurement source info").setMessage(detail).setPositiveButton("Close", null)
        if (e.key.startsWith("import:")) dialog.setNeutralButton("Delete imported copy") { _, _ ->
            AlertDialog.Builder(this).setTitle("Delete the imported copy?").setMessage("The original file and local runs and baseline are kept.")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                    work({ library.deleteImported(e); library.load() }) { applyLoaded(it); render() }
                }.show()
        }
        dialog.show()
    }

    private fun showResult(value: String) {
        val text = Look.text(this, value, 13, Look.ink, mono = true).apply { setPadding(dp(16), dp(12), dp(16), dp(12)); setTextIsSelectable(true) }
        AlertDialog.Builder(this).setTitle("Repeat measurement analysis result")
            .setView(ScrollView(this).apply { setBackgroundColor(Look.canvas); addView(text) })
            .setPositiveButton("Close", null).setNeutralButton("Export result") { _, _ ->
                work({
                    val directory = File(cacheDir, "benchmark-exports").apply { mkdirs() }
                    File.createTempFile("profile-comparison-", ".txt", directory).apply { writeText(value) }
                }) { file ->
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); clipData = ClipData.newRawUri("comparison", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Export analysis result"))
                }
            }.show()
    }

    private fun <T> work(task: () -> T, done: (T) -> Unit) {
        if (busy) return
        busy = true; render()
        io.execute {
            val result = runCatching(task)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                result.fold(done) { errors = listOf(it.message ?: "Processing failed"); message(errors.first()) }
                render()
            }
        }
    }
    private fun text(value: String, size: Int = 14) { body.addView(Look.text(this, value, size, Look.onDark), lp()) }
    private fun button(value: String, enabled: Boolean = true, action: () -> Unit) {
        body.addView(Look.ghostButton(this, value, dark = true) { if (!busy) action() }.apply { isEnabled = enabled && !busy; minHeight = dp(48) }, lp())
    }
    private fun checkbox(value: String, checked: Boolean, changed: (Boolean) -> Unit) {
        body.addView(CheckBox(this).apply { text = value; setTextColor(Look.onDark); isChecked = checked; isEnabled = !busy; minHeight = dp(48)
            setOnCheckedChangeListener { _, new -> changed(new) } }, lp())
    }
    private fun message(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = Look.dp(this, value)
    private fun lp() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
}
