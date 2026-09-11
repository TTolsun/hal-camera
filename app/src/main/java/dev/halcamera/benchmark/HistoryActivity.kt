package dev.halcamera.benchmark

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.halcamera.ui.Look
import java.io.File
import java.util.concurrent.Executors

/** RESULTS (8.5). All file reads, deletes and CSV generation run on one serial worker. */
class HistoryActivity : ComponentActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val store by lazy { BenchmarkStore(this) }
    private val report by lazy { BenchmarkReport(store) }
    private val catalog by lazy { StoreRunCatalog(store, report) }
    private val baselines by lazy { BaselineManager(catalog) }
    private var index = RunIndex(emptyList(), emptyList())
    private var pointers = BenchmarkIndex()
    private var indexError: String? = null
    private var filter = RunFilter.COMPARISON
    private var profileId: String? = null
    private var endpointKey: String? = null
    private var selectedId: String? = null
    private var compareId: String? = null
    private var pageSize = 50
    private var busy = false
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private var listScrollY = 0
    private var showingComparison = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filter = RunFilter.values().firstOrNull { it.name == savedInstanceState?.getString("filter") } ?: RunFilter.COMPARISON
        profileId = if (savedInstanceState != null) savedInstanceState.getString("profile") else intent.getStringExtra("profile")
        endpointKey = if (savedInstanceState != null) savedInstanceState.getString("endpoint") else intent.getStringExtra("endpoint")
        selectedId = savedInstanceState?.getString("selected")
        compareId = savedInstanceState?.getString("compare")
        scroll = ScrollView(this).apply { setBackgroundColor(Look.expertTile) }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left + dp(16), bars.top + dp(16), bars.right + dp(16), bars.bottom + dp(16))
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (compareId != null) { compareId = null; render() }
                else if (selectedId != null) { selectedId = null; render() }
                else finish()
            }
        })
    }

    override fun onResume() { super.onResume(); reload() }
    override fun onDestroy() { io.shutdown(); super.onDestroy() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("filter", filter.name)
        outState.putString("profile", profileId)
        outState.putString("endpoint", endpointKey)
        outState.putString("selected", selectedId)
        outState.putString("compare", compareId)
        super.onSaveInstanceState(outState)
    }

    private fun reload() {
        work({
            val loaded = RunIndex.load(catalog)
            val baselineIndex = store.index()
            Triple(loaded, baselineIndex, store.lastIndexError)
        }) { (loaded, baselineIndex, error) ->
            index = loaded; pointers = baselineIndex; indexError = error
            if (index.runs.none { it.runId == selectedId }) selectedId = null
            if (index.runs.none { it.runId == compareId }) compareId = null
            render()
        }
    }

    private fun visible() = index.filtered(filter, profileId, endpointKey)

    private fun render() {
        val wasComparison = showingComparison
        val scrollY = scroll.scrollY
        showingComparison = compareId != null
        if (!wasComparison) listScrollY = scrollY
        body.removeAllViews()
        text("RESULTS", 24, true)
        if (busy) text("실행 기록을 처리하고 있습니다.")
        if (compareId != null && renderComparison()) {
            scroll.post { scroll.scrollTo(0, if (wasComparison) scrollY else 0) }
            return
        }
        button("BENCHMARK로 돌아가기") { finish() }
        button("필터 · ${filter.label}") {
            choose("실행 상태", RunFilter.values().map { it.label }) { filter = RunFilter.values()[it]; pageSize = 50; render() }
        }
        button("Profile · ${profileId ?: "전체"}") {
            val values = index.runs.map { it.profile.id }.distinct().sorted()
            choose("Profile", listOf("전체") + values) { profileId = if (it == 0) null else values[it - 1]; pageSize = 50; render() }
        }
        button("Camera · ${endpointKey ?: "전체"}") {
            val values = index.runs.map { it.endpoint.key }.distinct().sorted()
            choose("Camera endpoint", listOf("전체") + values) { endpointKey = if (it == 0) null else values[it - 1]; pageSize = 50; render() }
        }
        val runs = visible()
        text("${runs.size}개 실행 · 행을 눌러 결과를 열고, 길게 눌러 작업을 선택합니다.")
        button("CSV EXPORT · 현재 필터 ${runs.size}개", runs.isNotEmpty()) { exportCsv(runs) }
        indexError?.let { text("Baseline을 읽지 못했습니다: $it") }
        if (index.unreadableIds.isNotEmpty()) text("읽을 수 없는 파일 ${index.unreadableIds.size}개: ${index.unreadableIds.joinToString()}")
        selectedId?.let { id ->
            text("비교 기준으로 선택: $id\n비교할 다른 실행을 누르세요.")
            button("선택 취소") { selectedId = null; render() }
        }
        if (runs.isEmpty()) text("이 조건에 맞는 실행이 없습니다. 필터를 바꾸거나 새 벤치마크를 실행하세요.")
        val byId = index.runs.associateBy { it.runId }
        runs.take(pageSize).forEach { run ->
            val baselineId = pointers.baseline(run.contract.comparisonContractId, run.endpoint.key)
            val baseline = byId[baselineId]?.takeIf { it.runId != run.runId }
            val regression = baseline?.let { RegressionDetector.compare(it, run).regressedCount } ?: 0
            val capture = run.metric("2.2")?.let { ResultPresenter.format(it, it.value) } ?: "—"
            val status = when {
                baselineId == run.runId -> "★ baseline"
                regression > 0 -> "▲ $regression"
                else -> ""
            }
            val card = Look.card(this, dark = true)
            val label = listOf(
                run.runId,
                listOfNotNull(run.subject.subjectBuildLabel ?: "(subject 없음)", run.subject.subjectCommit).joinToString(" · "),
                "${run.profile.id} · Camera ${run.endpoint.key} · ${run.endpoint.role}",
                "Capture $capture  $status",
                ResultPresenter.eligibilityLine(run),
                run.validity.flags.joinToString(" · ")
            ).filter { it.isNotEmpty() }.joinToString("\n")
            card.addView(Look.text(this, label, 14, Look.onDark, mono = true))
            card.isFocusable = true
            card.contentDescription = label
            card.setOnClickListener {
                if (!busy) {
                    if (selectedId == null) open(run)
                    else if (selectedId != run.runId) { compareId = run.runId; render() }
                    else message("다른 실행을 선택하세요.")
                }
            }
            card.setOnLongClickListener { if (!busy) menu(run); true }
            body.addView(card, lp())
        }
        if (runs.size > pageSize) button("더 보기 · ${runs.size - pageSize}개 남음") { pageSize += 50; render() }
        scroll.post { scroll.scrollTo(0, if (wasComparison) listScrollY else scrollY) }
    }

    private fun renderComparison(): Boolean {
        val base = index.runs.find { it.runId == selectedId } ?: return false
        val current = index.runs.find { it.runId == compareId } ?: return false
        val onBaseline = pointers.baseline(current.contract.comparisonContractId, current.endpoint.key) == base.runId
        val comparison = RegressionDetector.compare(base, current)
        val view = ComparePresenter.present(base, current, comparison,
            if (onBaseline) ComparedTo.BASELINE else ComparedTo.PREVIOUS, selectedReference = true)
        text("COMPARE", 20, true)
        text("기준: ${base.runId}\n${base.subject.subjectBuildLabel.orEmpty()} · ${base.subject.subjectCommit.orEmpty()}")
        text("현재: ${current.runId}\n${current.subject.subjectBuildLabel.orEmpty()} · ${current.subject.subjectCommit.orEmpty()}")
        view.identityLine?.let { text(it) }
        view.conditionLine?.let { text(it) }
        view.referenceNote?.let { text(it) }
        if (!comparison.sameContract || !comparison.sameEndpoint) text("Profile·측정 계약 또는 camera endpoint가 달라 판정할 수 없습니다.")
        view.rows.forEach { row ->
            text("${row.label}\n${view.baseHeader}: ${row.base} → CURRENT: ${row.current}\n${row.delta} ${row.marker}")
        }
        button("기준 / 현재 바꾸기") { val old = selectedId; selectedId = compareId; compareId = old; render() }
        button("CSV EXPORT · 두 실행") { exportCsv(listOf(base, current)) }
        button("이력으로 돌아가기") { compareId = null; render() }
        return true
    }

    private fun open(run: BenchmarkRun) {
        startActivity(Intent(this, BenchmarkActivity::class.java).putExtra(BenchmarkActivity.EXTRA_RUN_ID, run.runId))
    }

    private fun menu(run: BenchmarkRun) {
        val isBaseline = pointers.baseline(run.contract.comparisonContractId, run.endpoint.key) == run.runId
        choose(run.runId, listOf("결과 열기", if (isBaseline) "CLEAR BASELINE" else "SET AS BASELINE", "COMPARE", "EXPORT JSON", "EXPORT CSV", "DELETE")) {
            when (it) {
                0 -> open(run)
                1 -> {
                    if (indexError != null) message("Baseline 파일을 읽을 수 없어 변경할 수 없습니다.")
                    else if (!isBaseline && !run.validity.comparisonEligible) message("비교 가능한 실행만 baseline으로 지정할 수 있습니다.")
                    else work({ baselines.toggle(run) }) { reload() }
                }
                2 -> { selectedId = run.runId; compareId = null; render() }
                3 -> share(store.file(run.runId), "application/json")
                4 -> exportCsv(listOf(run))
                5 -> AlertDialog.Builder(this).setTitle("실행을 삭제할까요?")
                    .setMessage("${run.runId}\n실행 JSON을 삭제합니다.${if (isBaseline) " 이 실행의 baseline 지정도 해제됩니다." else ""}")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("삭제") { _, _ -> work({ check(store.deleteRun(run.runId)) { "실행을 삭제하지 못했습니다." } }) { reload() } }
                    .show()
            }
        }
    }

    private fun exportCsv(runs: List<BenchmarkRun>) {
        work({
            val dir = File(cacheDir, "benchmark-exports").apply { check(isDirectory || mkdirs()) }
            val file = File.createTempFile("benchmarks-", ".csv", dir)
            file.bufferedWriter(Charsets.UTF_8).use { writer -> BenchmarkCsv.write(runs.asSequence(), writer) }
            file
        }) { share(it, "text/csv") }
    }

    private fun share(file: File, mime: String) {
        try {
            check(file.isFile) { "내보낼 파일이 없습니다." }
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("benchmark", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "실행 내보내기"))
        } catch (e: Exception) { message(e.message ?: "내보내기에 실패했습니다.") }
    }

    private fun <T> work(task: () -> T, done: (T) -> Unit) {
        if (busy) return
        busy = true
        render()
        io.execute {
            val result = runCatching(task)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                render()
                result.fold(done) { message(it.message ?: "작업에 실패했습니다.") }
            }
        }
    }

    private fun choose(title: String, items: List<String>, onSelect: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setItems(items.toTypedArray()) { _, i -> onSelect(i) }.show()
    }
    private fun text(value: String, size: Int = 14, bold: Boolean = false) {
        body.addView(Look.text(this, value, size, Look.onDark, bold = bold), lp())
    }
    private fun button(label: String, enabled: Boolean = true, click: () -> Unit) {
        body.addView(Look.ghostButton(this, label, dark = true) { if (!busy) click() }.apply {
            isEnabled = enabled && !busy
            minHeight = dp(48)
        }, lp())
    }
    private fun message(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun lp() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    private fun dp(value: Int) = Look.dp(this, value)
}
