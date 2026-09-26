package dev.halcamera.benchmark

import dev.halcamera.ui.BenchmarkResultCards

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.halcamera.benchmark.domain.*
import dev.halcamera.camera.CameraLabel
import dev.halcamera.benchmark.platform.*
import dev.halcamera.ui.Look
import dev.halcamera.ui.showSelectionPopup
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
    private var pickingComparison = false
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
        pickingComparison = savedInstanceState?.getBoolean("pickingComparison") ?: false
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
                else if (selectedId != null || pickingComparison) { selectedId = null; pickingComparison = false; render() }
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
        outState.putBoolean("pickingComparison", pickingComparison)
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
        if (compareId != null && renderComparison()) {
            scroll.post { scroll.scrollTo(0, if (wasComparison) scrollY else 0) }
            return
        }
        titleBar("실행 기록", 24, "벤치마크로 돌아가기") { finish() }
        if (busy) text("실행 기록을 처리하고 있습니다.")
        button("필터 · ${filter.label} ▾") { anchor ->
            showSelectionPopup(anchor, RunFilter.values().map { it.label }, filter.ordinal) { filter = RunFilter.values()[it]; pageSize = 50; render() }
        }
        button("${endpointKey?.let(CameraLabel::short) ?: "Camera · 전체"} ▾") { anchor ->
            val values = (index.runs.map { it.endpoint.key } + listOfNotNull(endpointKey)).distinct().sorted()
            showSelectionPopup(anchor, listOf("Camera · 전체") + values.map(CameraLabel::short), values.indexOf(endpointKey) + 1) { endpointKey = if (it == 0) null else values[it - 1]; pageSize = 50; render() }
        }
        val runs = visible()
        // The list actions share one row, as 비교 and 내보내기 do on the result screen: two full-width buttons and a
        // count line pushed the first run to the middle of the screen. The count now sits on the list headings.
        val listActions = Look.row(this)
        // Half-width buttons: the ghost button's 20dp side padding wrapped "목록 CSV 내보내기" onto two lines on a
        // Galaxy S25+ at the default font size. Narrower padding keeps it on one line there; at a larger font it can
        // still wrap, so both buttons fill the row's height and stay the same size.
        fun rowButton(view: View) = view.apply { setPadding(dp(8), paddingTop, dp(8), paddingBottom) }
        fun rowParams() = Look.buttonParams(0, 1f).apply { height = LinearLayout.LayoutParams.MATCH_PARENT }
        if (selectedId == null && !pickingComparison) listActions.addView(
            rowButton(ghost("두 실행 비교", runs.size >= 2) { pickingComparison = true; render() }), rowParams()
        )
        listActions.addView(
            // "목록" says what goes into the file: every run listed below, not one run and not the screen.
            rowButton(ghost("목록 CSV 내보내기", runs.isNotEmpty()) { exportCsv(runs) }.apply {
                contentDescription = "현재 필터의 실행 ${runs.size}개를 CSV로 내보내기"
            }),
            rowParams().apply { if (listActions.childCount > 0) marginStart = dp(8) }
        )
        body.addView(listActions, lp())
        if (pickingComparison && selectedId == null) {
            text("기준으로 사용할 실행을 선택하세요.")
            button("비교 선택 취소") { pickingComparison = false; render() }
        }
        indexError?.let { text("Baseline을 읽지 못했습니다: $it") }
        if (index.unreadableIds.isNotEmpty()) text("읽을 수 없는 파일 ${index.unreadableIds.size}개: ${index.unreadableIds.joinToString()}")
        selectedId?.let { id ->
            text("비교 기준으로 선택: $id\n비교할 다른 실행을 누르세요.")
            button("선택 취소") { selectedId = null; pickingComparison = false; render() }
        }
        if (runs.isEmpty()) text("이 조건에 맞는 실행이 없습니다. 필터를 바꾸거나 새 벤치마크를 실행하세요.")
        val byId = index.runs.associateBy { it.runId }
        // Baselines lead the list under their own heading. In a newest-first list a baseline, usually the oldest
        // run of its camera, sat at the very bottom with a grey badge, and nobody could tell which run it was.
        val (baselineRuns, otherRuns) = pointers.baselinesFirst(runs)
        // Each heading counts its own group. One baseline per camera is the usual case, so "1개" would only be
        // noise; a count appears once several cameras are listed together.
        if (baselineRuns.isNotEmpty()) {
            heading(if (baselineRuns.size == 1) "Baseline" else "Baseline · ${baselineRuns.size}개")
            baselineRuns.forEach { runRow(it, byId) }
            if (otherRuns.isNotEmpty()) heading("다른 실행 · ${otherRuns.size}개")
        } else if (otherRuns.isNotEmpty()) heading("실행 · ${otherRuns.size}개")
        otherRuns.take(pageSize).forEach { runRow(it, byId) }
        if (otherRuns.size > pageSize) button("더 보기 · ${otherRuns.size - pageSize}개 남음") { pageSize += 50; render() }
        scroll.post { scroll.scrollTo(0, if (wasComparison) listScrollY else scrollY) }
    }

    private fun runRow(run: BenchmarkRun, byId: Map<String, BenchmarkRun>) {
        val baselineId = pointers.baseline(run.contract.comparisonContractId, run.endpoint.key)
        val baseline = byId[baselineId]?.takeIf { it.runId != run.runId }
        val regression = baseline?.let { RegressionDetector.compare(it, run).regressedCount } ?: 0
        val capture = run.metric("2.2")?.let { ResultPresenter.format(it, it.value) } ?: "—"
        val status = when {
            baselineId == run.runId -> "★ Baseline"
            regression > 0 -> "▲ $regression degraded"
            else -> ""
        }
        val card = Look.card(this, dark = true)
        if (selectedId == run.runId) {
            card.background = Look.cardBackground(this, Look.expertTile2, Look.primaryOnDark)
            androidx.core.view.ViewCompat.setStateDescription(card, "비교 기준으로 선택됨")
        }
        // Two lines, or three when a build label was typed: when the run happened and how it did, then
        // the numbers. The run id, the profile and the raw validity flags live on the result screen this
        // row opens.
        val lines = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = Look.row(this)
        head.addView(
            // A long badge must not wrap the time onto a second line at a large font scale; the time is a
            // fixed 16 characters, so letting it ellipsize is the right way to lose the argument.
            Look.text(this, ResultPresenter.localTime(run.runId) ?: run.runId, 15, Look.onDark, bold = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        // One badge, on the headline row. A verdict outranks an eligibility note: a run that degraded is
        // worth opening whether or not it also missed scoring, and the result screen carries both.
        val badge = status.ifEmpty { ResultPresenter.shortStatus(run).orEmpty() }
        // A baseline row shows no badge: it only ever appears under the Baseline heading, which already says so,
        // and a bordered chip beside the ⋮ button read as another button. The spoken label below keeps the word.
        if (badge.isNotEmpty() && baselineId != run.runId) {
            val badgeColor = if (status.isEmpty()) Look.statusWarn else Look.statusFail
            head.addView(Look.text(this, badge, 13, badgeColor, bold = true))
        }
        lines.addView(head)
        run.subject.subjectBuildLabel?.takeIf { it.isNotBlank() }?.let {
            lines.addView(Look.text(this, it, 13, Look.onDarkMuted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        }
        val facts = "Capture $capture · ${CameraLabel.full(run.endpoint)}"
        // With one camera picked in the filter every row would repeat its name, and the name pushed the line onto a
        // third row behind the ⋮ button. The spoken label keeps it.
        val shownFacts = if (endpointKey != null) "Capture $capture" else facts
        // Proportional, not monospace: nothing lines up between rows, and the mono advance pushed this
        // line onto a second row behind the ⋮ button.
        lines.addView(Look.text(this, shownFacts, 13, Look.onDarkMuted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })

        val label = listOfNotNull(
            ResultPresenter.localTime(run.runId) ?: run.runId,
            badge.takeIf { it.isNotEmpty() },
            run.subject.subjectBuildLabel?.takeIf { it.isNotBlank() },
            facts
        ).joinToString(" · ")
        val row = Look.row(this)
        row.addView(lines, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Look.ghostButton(this, "⋮", dark = true) { if (!busy) menu(run) }.apply {
            contentDescription = "${run.runId} 작업 메뉴"
            setPadding(0, 0, 0, 0)
            isEnabled = !busy
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        card.addView(row)
        card.isFocusable = true
        card.contentDescription = label
        card.setOnClickListener {
            if (!busy) {
                if (selectedId == null && pickingComparison) { selectedId = run.runId; render() }
                else if (selectedId == null) open(run)
                else if (selectedId != run.runId) { compareId = run.runId; render() }
                else message("다른 실행을 선택하세요.")
            }
        }
        card.setOnLongClickListener { if (!busy) menu(run); true }
        body.addView(card, lp())
    }

    private fun renderComparison(): Boolean {
        val base = index.runs.find { it.runId == selectedId } ?: return false
        val current = index.runs.find { it.runId == compareId } ?: return false
        val onBaseline = pointers.baseline(current.contract.comparisonContractId, current.endpoint.key) == base.runId
        val comparison = RegressionDetector.compare(base, current)
        titleBar("비교", 20, "실행 이력으로 돌아가기") { compareId = null; render() }
        if (busy) text("실행 기록을 처리하고 있습니다.")
        // Which run is which, in the time the list shows; ids, builds and identity sit in the card's 실행 정보.
        fun who(role: String, run: BenchmarkRun) = listOfNotNull(
            "$role: ${ResultPresenter.localTime(run.runId) ?: run.runId}",
            run.subject.subjectBuildLabel?.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        text("${who("기준", base)}\n${who("현재", current)}")
        if (!comparison.sameContract || !comparison.sameEndpoint) text("Profile·측정 계약 또는 camera endpoint가 달라 판정할 수 없습니다.")
        // The same delta chart as the result screen's 비교. Only a baseline reference is judged, so against a run
        // picked here no bar turns red and the values stay grey: the bars show direction and size, as they do
        // against the previous run.
        BenchmarkResultCards.addCompare(
            this, body, current, base, comparison,
            if (onBaseline) ComparedTo.BASELINE else ComparedTo.PREVIOUS,
            pointers.isBaseline(current), selectedReference = true
        )
        button("기준 / 현재 바꾸기") { val old = selectedId; selectedId = compareId; compareId = old; render() }
        button("CSV 내보내기 · 두 실행") { exportCsv(listOf(base, current)) }
        return true
    }

    private fun open(run: BenchmarkRun) {
        startActivity(Intent(this, BenchmarkActivity::class.java).putExtra(BenchmarkActivity.EXTRA_RUN_ID, run.runId))
    }

    private fun menu(run: BenchmarkRun) {
        val isBaseline = pointers.baseline(run.contract.comparisonContractId, run.endpoint.key) == run.runId
        choose(run.runId, listOf("결과 열기", if (isBaseline) "baseline 해제" else "baseline으로 지정", "비교", "JSON 내보내기", "CSV 내보내기", "삭제")) {
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
        AlertDialog.Builder(this).setTitle(title).setNegativeButton("취소", null)
            .setItems(items.toTypedArray()) { _, i -> onSelect(i) }
            .show()
    }
    /** A list section title, marked as a heading so TalkBack can jump between sections. */
    private fun heading(value: String) {
        val view = Look.text(this, value, 17, Look.onDark, bold = true)
        ViewCompat.setAccessibilityHeading(view, true)
        body.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
    }

    private fun text(value: String, size: Int = 14, bold: Boolean = false) {
        body.addView(Look.text(this, value, size, Look.onDark, bold = bold), lp())
    }
    private fun ghost(label: String, enabled: Boolean = true, click: (View) -> Unit) =
        Look.ghostButton(this, label, dark = true) {}.apply {
            setOnClickListener { if (!busy) click(it) }
            isEnabled = enabled && !busy
            minHeight = dp(48)
        }
    private fun button(label: String, enabled: Boolean = true, click: (View) -> Unit) {
        body.addView(ghost(label, enabled, click), lp())
    }
    private fun titleBar(title: String, sizeSp: Int, backLabel: String, click: () -> Unit) {
        body.addView(Look.titleBar(this, title, sizeSp, backLabel) { if (!busy) click() }.apply {
            getChildAt(0).isEnabled = !busy
        }, lp())
    }
    private fun message(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun lp() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    private fun dp(value: Int) = Look.dp(this, value)
}
