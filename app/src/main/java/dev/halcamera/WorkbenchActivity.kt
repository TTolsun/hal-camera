package dev.halcamera

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.halcamera.benchmark.BenchmarkActivity
import dev.halcamera.benchmark.HistoryActivity
import dev.halcamera.cts.CtsEntryActivity
import dev.halcamera.ui.AboutSheet
import dev.halcamera.ui.DeviceIdentity
import dev.halcamera.ui.IconButton
import dev.halcamera.ui.Look
import dev.halcamera.ui.IncidentActions
import dev.halcamera.cli.CommandCoordinator
import java.io.File
import java.util.concurrent.Executors

/** Camera-free home for inspection tools and saved results, reached from LIVE. */
class WorkbenchActivity : ComponentActivity() {
    private lateinit var body: LinearLayout
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var saveFile: File? = null
    private val cli by lazy { CommandCoordinator.get(this) }
    private val incidents by lazy {
        IncidentActions(this, io, main, object : IncidentActions.Host {
            override val sessions = emptyMap<String, Map<String, Any?>>()
            override val destroyed get() = isDestroyed
            override fun latestChanged(file: File?) = Unit
            override fun saveAs(file: File) { saveFile = file; saveDocument.launch(file.name) }
        })
    }
    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = saveFile
        if (uri != null && file != null) io.execute {
            val message = runCatching {
                contentResolver.openOutputStream(uri)?.use { target -> file.inputStream().use { it.copyTo(target) } }
                    ?: error("저장 위치를 열 수 없습니다")
                "선택한 위치에 저장했습니다"
            }.getOrElse { "저장 실패: ${it.message}" }
            main.post { if (!isDestroyed) Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("save_zip", saveFile?.name)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString("save_zip")?.let { saveFile = File(File(filesDir, "incidents"), it) }
        val scroll = ScrollView(this).apply { setBackgroundColor(Look.cameraSurface); isFillViewport = true }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            // Readable on tablets and in landscape; recompute from the actual window, not physical display size.
            val available = view.width - bars.left - bars.right
            val margin = maxOf(dp(20), (available - dp(840)) / 2)
            view.setPadding(bars.left + margin, bars.top + dp(16), bars.right + margin, bars.bottom + dp(24))
            insets
        }
        scroll.addOnLayoutChangeListener { view, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l != oldR - oldL) ViewCompat.requestApplyInsets(view)
        }
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    private fun render() {
        body.removeAllViews()
        val header = Look.row(this)
        header.addView(IconButton(this, R.drawable.ic_action_back, "프리뷰로 돌아가기") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(Look.text(this, "HAL CAMERA", 14, Look.onDark, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(header)
        body.addView(Look.text(this, "Lab", 24, Look.onDark, bold = true), lp(8))

        val device = Look.card(this, dark = true).apply {
            background = Look.touchBackground(this@WorkbenchActivity, Look.cameraCard, Look.cameraOutline)
            isFocusable = true
            contentDescription = "기기 이름·빌드 정보, ${DeviceIdentity.label(this@WorkbenchActivity)}"
            setOnClickListener { showDevice() }
        }
        device.addView(Look.text(this, "DEVICE · 기기 정보  ›", 12, Look.primaryOnDark, bold = true))
        device.addView(Look.text(this, DeviceIdentity.label(this), 20, Look.onDark, bold = true), lp(4))
        device.addView(Look.text(this, "${DeviceIdentity.chipset()} · ${DeviceIdentity.platform()}", 12, Look.onDarkMuted), lp(4))
        device.addView(Look.text(this, "Build · ${Build.DISPLAY}", 12, Look.onDarkMuted, mono = true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, lp(4))
        body.addView(device, lp(16))

        body.addView(Look.primaryButton(this, "Live · 프리뷰로 돌아가기") { finish() }, lp(16))
        body.addView(Look.text(this, "검사 도구", 14, Look.onDark, bold = true), lp(20))
        destination("01", "Probe · 사양 확인", "지원 기능 · 해상도 · 스트림 사양") { open(CameraProbeActivity::class.java) }
        destination("02", "CTS · 동작 검증", "검사 선택·단계별 판정·실패 원인") { open(CtsEntryActivity::class.java) }
        destination("03", "Benchmark · 성능 측정", "반복 측정·지연·안정성 비교") { open(BenchmarkActivity::class.java) }
        body.addView(Look.text(this, "저장된 결과", 14, Look.onDark, bold = true), lp(24))
        body.addView(Look.ghostButton(this, "실행 기록 · 비교·내보내기", dark = true) { open(HistoryActivity::class.java) }, lp(12))
        body.addView(Look.ghostButton(this, "갤러리 · 사진·동영상", dark = true) { open(GalleryActivity::class.java) }, lp(8))
        body.addView(Look.ghostButton(this, "ZIP 기록 · 공유·저장·삭제", dark = true) { incidents.showList() }, lp(8))
        body.addView(Look.text(this, "설정 · 도움말", 14, Look.onDark, bold = true), lp(24))
        body.addView(Look.ghostButton(this, "ADB CLI 설정", dark = true) {
            AlertDialog.Builder(this).setTitle("ADB CLI 설정")
                .setMultiChoiceItems(arrayOf("ADB CLI 허용"), booleanArrayOf(cli.enabled)) { _, _, checked -> cli.setEnabled(checked) }
                .setPositiveButton("닫기", null).show()
        }, lp(12))
        body.addView(Look.ghostButton(this, "카메라 다시 연결", dark = true) { returnToLive(ACTION_RECONNECT) }, lp(8))
        body.addView(Look.ghostButton(this, if (intent.getBooleanExtra(EXTRA_PAUSED, false)) "프리뷰 재개" else "프리뷰 일시정지", dark = true) {
            returnToLive(ACTION_TOGGLE_PREVIEW)
        }, lp(8))
        body.addView(Look.ghostButton(this, "앱 정보", dark = true) { AboutSheet.show(this) }, lp(8))
    }

    private fun returnToLive(action: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_LIVE_ACTION, action).putExtra(EXTRA_PAUSED, !intent.getBooleanExtra(EXTRA_PAUSED, false)))
        finish()
    }

    private fun destination(number: String, title: String, detail: String, action: () -> Unit) {
        val row = Look.row(this).apply {
            gravity = Gravity.TOP
            background = Look.touchBackground(this@WorkbenchActivity, Look.cameraSurface, Look.cameraOutline)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isFocusable = true
            contentDescription = "$title. $detail"
            setOnClickListener { action() }
        }
        row.addView(Look.text(this, number, 12, Look.primaryOnDark, mono = true), LinearLayout.LayoutParams(dp(36), -2).apply { topMargin = dp(4) })
        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        words.addView(Look.text(this, title, 17, Look.onDark, bold = true))
        words.addView(Look.text(this, detail, 13, Look.onDarkMuted), lp(4))
        row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Look.text(this, "›", 20, Look.onDarkMuted).apply { importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(20), -2))
        body.addView(row, lp(8))
    }

    private fun showDevice() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
        val name = EditText(this).apply {
            setText(DeviceIdentity.alias(this@WorkbenchActivity))
            hint = "예: EVT2 · Lab 03"
            contentDescription = "이 기기의 식별 이름"
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(40))
        }
        content.addView(Look.text(this, "같은 모델을 구별할 이름입니다. 이 앱에만 저장됩니다.", 14, Look.onDarkMuted))
        content.addView(name, lp(8))
        content.addView(Look.text(this, DeviceIdentity.report(this), 12, Look.onDarkMuted, mono = true).apply { setTextIsSelectable(true) }, lp(16))
        AlertDialog.Builder(this).setTitle("기기 이름·빌드 정보")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("이름 저장") { _, _ -> DeviceIdentity.setAlias(this, name.text.toString()); render() }
            .setNeutralButton("기기 정보 복사") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("HAL CAMERA device", DeviceIdentity.report(this)))
                Toast.makeText(this, "기기·빌드 정보를 복사했습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("닫기", null).show()
    }

    private fun open(screen: Class<out android.app.Activity>) = startActivity(Intent(this, screen).apply {
        if (screen == CameraProbeActivity::class.java || screen == BenchmarkActivity::class.java) {
            this@WorkbenchActivity.intent.getStringExtra(EXTRA_CAMERA_ID)?.let {
                putExtra(CameraProbeActivity.EXTRA_CAMERA_ID, it)
            }
        }
        if (screen == BenchmarkActivity::class.java) {
            this@WorkbenchActivity.intent.getStringExtra(EXTRA_ENGINE)?.let {
                putExtra(BenchmarkActivity.EXTRA_ENGINE, it)
            }
        }
    })
    companion object {
        const val EXTRA_PAUSED = "preview_paused"
        const val EXTRA_LIVE_ACTION = "live_action"
        const val ACTION_RECONNECT = "reconnect"
        const val ACTION_TOGGLE_PREVIEW = "toggle_preview"
        const val EXTRA_CAMERA_ID = "camera_id"
        const val EXTRA_ENGINE = "engine"
    }

    private fun dp(value: Int) = Look.dp(this, value)
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
}
