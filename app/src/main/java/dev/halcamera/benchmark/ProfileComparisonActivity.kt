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
            require(uris.size <= 50) { "한 번에 최대 50개 파일을 가져올 수 있습니다." }
            var added = 0; var duplicates = 0
            val failures = mutableListOf<String>()
            uris.forEachIndexed { i, uri ->
                try {
                    val imported = requireNotNull(contentResolver.openInputStream(uri)).use(library::import)
                    if (imported.duplicate) duplicates++ else added++
                } catch (e: Exception) { failures += "파일 ${i + 1}: ${e.message ?: "읽기 실패"}" }
            }
            Triple(library.load(), "가져옴 $added · 기존 사본 $duplicates", failures)
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
            errors = errors + "선택했던 원본이 삭제되거나 변경되었습니다. 분석 전에 선택을 다시 확인하세요."
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
        text("프로파일 비교", 24)
        text("반복 실행 A/B 비교 · baseline과 별도 분석")
        if (busy) text("자료를 처리하고 있습니다…")
        button("JSON 파일 가져오기") { picker.launch(arrayOf("*/*")) }
        button("기기 필터 · ${deviceFilter ?: "전체"}") {
            val models = entries.map { it.deviceLabel }.distinct().sorted()
            AlertDialog.Builder(this).setTitle("모델별 표시 · 동일 물리 기기 확인과는 별개")
                .setItems((listOf("전체") + models).toTypedArray()) { _, index ->
                    deviceFilter = if (index == 0) null else models[index - 1]; persist(); render()
                }.setNegativeButton("취소", null).show()
        }
        button("수정 전 / A 선택 · ${before.size}개") { select(true) }
        selectionSummary(before)
        button("수정 후 / B 선택 · ${after.size}개") { select(false) }
        selectionSummary(after)
        if (before.intersect(after).isNotEmpty()) text("A/B에 같은 원본이 포함되어 있습니다. 한쪽에서 제외하세요.")
        checkbox("다른 기기 간 비교", options.differentDevices) {
            options = options.copy(differentDevices = it, sameDeviceConfirmed = false); persist(); render()
        }
        if (!options.differentDevices) checkbox("재설치·ID 누락 자료를 포함해 동일 물리 기기임을 확인", options.sameDeviceConfirmed) {
            options = options.copy(sameDeviceConfirmed = it); persist(); render()
        }
        checkbox("독립 반복 실행 · 동일 장면·조명 확인", options.independentRunsConfirmed) {
            options = options.copy(independentRunsConfirmed = it); persist(); render()
        }
        if (options.differentDevices) text("기기 간 차이는 SW 수정 효과로 해석할 수 없습니다. 렌즈 역할·화각을 확인하세요.")
        button("선택한 묶음 분석", before.isNotEmpty() && after.isNotEmpty()) {
            val a = entries.filter { it.key in before }; val b = entries.filter { it.key in after }; val selectedOptions = options
            work({
                val result = ProfileComparison.compare(a, b, selectedOptions).render()
                synchronized(ProfileLibrary::class.java) { AtomicFiles.write(library.resultFile, result) }
                result
            }) { resultText = it; render(); showResult(it) }
        }
        button("최근 분석 결과 다시 열기") {
            work({ check(library.resultFile.isFile) { "저장된 분석 결과가 없습니다." }; library.resultFile.readText() }) { showResult(it) }
        }
        body.addView(Look.disclosure(this, "비교 조건·기기 ID 안내", Look.text(this,
            "묶음별 한 기기·빌드로 구성합니다. 필터를 바꿔도 선택은 유지됩니다.\n\n기기 ID는 앱 설치 단위이며 재설치·데이터 삭제 후 달라질 수 있습니다. 외부 JSON의 ID는 인증된 하드웨어 식별자가 아닙니다.\n\n가져온 자료는 기존 baseline과 분리됩니다. 다른 기기에서는 같은 카메라 ID가 같은 렌즈를 뜻하지 않습니다.",
            13, Look.onDarkMuted)), lp())
        button("자료 목록 새로고침") { reload() }
        resultText?.let { text("분석 저장 완료 · 원본 해시·방법·제외 사유 포함") }
        button("선택 초기화") { before.clear(); after.clear(); options = ProfileComparison.Options(); persist(); render() }
        errors.forEach { text("확인 필요: $it") }
        text("${visible().size}개 자료 · 아래 행을 눌러 원시 지표를 확인합니다.")
        visible().take(100).forEach { e -> button(e.label + "\n원본 ${e.sha256.take(12)}") { showEntry(e) } }
        if (visible().size > 100) text("목록은 최근 100개까지 표시합니다. 묶음 선택 창에서는 현재 필터의 전체 자료를 선택할 수 있습니다.")
        button("실행 이력으로 돌아가기") { finish() }
    }

    private fun selectionSummary(keys: Set<String>) {
        val selected = entries.filter { it.key in keys }
        if (selected.isEmpty()) return
        val groups = selected.groupBy { entry ->
            "${entry.deviceLabel} · ${entry.instanceId?.take(8) ?: "기기 ID 없음"}\n" +
                "${entry.run.subject.subjectBuildLabel ?: entry.run.device.buildDisplay} · ${entry.run.subject.subjectCommit ?: "commit 없음"}"
        }
        text(groups.entries.joinToString("\n") { (identity, runs) -> "$identity · ${runs.size}회" }, 13)
        if (groups.size > 1) text("여러 기기·빌드가 선택되어 있습니다. 묶음 구성을 확인하세요.", 13)
    }

    private fun select(isBefore: Boolean) {
        val rows = visible()
        val original = if (isBefore) before else after
        val choices = original.toMutableSet()
        AlertDialog.Builder(this).setTitle(if (isBefore) "수정 전 / A · 최대 50개" else "수정 후 / B · 최대 50개")
            .setMultiChoiceItems(rows.map { it.label + "\n${it.sha256.take(12)}" }.toTypedArray(), BooleanArray(rows.size) { rows[it].key in choices }) { _, i, checked ->
                if (checked) choices.add(rows[i].key) else choices.remove(rows[i].key)
            }.setNegativeButton("취소", null).setPositiveButton("선택 적용") { _, _ ->
                if (choices.size > RepeatStatistics.MAX_RUNS) message("묶음별 최대 50개까지 선택하세요.")
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
            appendLine(e.label); appendLine("SHA-256 ${e.sha256}"); appendLine("원래 run ID: ${r.runId}")
            appendLine("설치 ID: ${e.instanceId ?: "알 수 없음"}\n${r.device}\n${r.app}\n${r.subject}")
            appendLine("계약: ${r.contract.comparisonContractId}\n${r.profile}\n${r.endpoint}\n${r.effectiveConditions}\n${r.env}\n${r.validity}")
            r.metrics.forEach { appendLine("${it.id}: ${it.value ?: "—"} ${it.unit}, n=${it.sampleCount}, timeout=${it.timeout}") }
            appendLine("저장된 점수·회귀 판정은 이 화면의 비교 근거로 사용하지 않습니다.")
        }
        val dialog = AlertDialog.Builder(this).setTitle("측정 원본 정보").setMessage(detail).setPositiveButton("닫기", null)
        if (e.key.startsWith("import:")) dialog.setNeutralButton("가져온 사본 삭제") { _, _ ->
            AlertDialog.Builder(this).setTitle("가져온 사본을 삭제할까요?").setMessage("원본 파일과 로컬 실행·baseline은 유지됩니다.")
                .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ ->
                    work({ library.deleteImported(e); library.load() }) { applyLoaded(it); render() }
                }.show()
        }
        dialog.show()
    }

    private fun showResult(value: String) {
        val text = Look.text(this, value, 13, Look.ink, mono = true).apply { setPadding(dp(16), dp(12), dp(16), dp(12)); setTextIsSelectable(true) }
        AlertDialog.Builder(this).setTitle("반복 측정 분석 결과")
            .setView(ScrollView(this).apply { setBackgroundColor(Look.canvas); addView(text) })
            .setPositiveButton("닫기", null).setNeutralButton("결과 내보내기") { _, _ ->
                work({
                    val directory = File(cacheDir, "benchmark-exports").apply { mkdirs() }
                    File.createTempFile("profile-comparison-", ".txt", directory).apply { writeText(value) }
                }) { file ->
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); clipData = ClipData.newRawUri("comparison", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "분석 결과 내보내기"))
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
                result.fold(done) { errors = listOf(it.message ?: "처리 실패"); message(errors.first()) }
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
