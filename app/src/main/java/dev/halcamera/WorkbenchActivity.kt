package dev.halcamera

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.ImageView
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
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
import androidx.core.view.WindowCompat
import dev.halcamera.ui.Look
import dev.halcamera.ui.LabDialog
import dev.halcamera.ui.IncidentActions
import dev.halcamera.cli.CommandCoordinator
import java.io.File
import java.util.concurrent.Executors

/** Camera-free home for inspection tools and saved results, reached from LIVE. */
class WorkbenchActivity : ComponentActivity() {
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
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
    private var pendingStreams: Intent? = null
    private val streamSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            pendingStreams = Intent(result.data).putExtra(EXTRA_LIVE_ACTION, ACTION_STREAMS)
                .putExtra(EXTRA_CAMERA_ID, intent.getStringExtra(EXTRA_CAMERA_ID))
            intent.putExtras(result.data!!)
            setResult(RESULT_OK, pendingStreams)
        }
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
        outState.putParcelable("pending_streams", pendingStreams)
        outState.putString("save_zip", saveFile?.name)
        outState.putInt("lab_scroll", scroll.scrollY)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.LabTheme)
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val savedStreams = savedInstanceState?.getParcelable<Intent>("pending_streams")
        if (savedStreams != null) {
            pendingStreams = savedStreams
            intent.putExtras(savedStreams)
            setResult(RESULT_OK, savedStreams)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        savedInstanceState?.getString("save_zip")?.let { saveFile = File(File(filesDir, "incidents"), it) }
        scroll = ScrollView(this).apply { setBackgroundColor(Look.canvas); isFillViewport = true }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            // Readable on tablets and in landscape; recompute from the actual window, not physical display size.
            val available = view.width - bars.left - bars.right
            val margin = maxOf(dp(20), (available - dp(680)) / 2)
            view.setPadding(bars.left + margin, bars.top + dp(16), bars.right + margin, bars.bottom + dp(24))
            insets
        }
        scroll.addOnLayoutChangeListener { view, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l != oldR - oldL) ViewCompat.requestApplyInsets(view)
        }
        render()
        savedInstanceState?.getInt("lab_scroll")?.let { y -> scroll.post { scroll.scrollTo(0, y) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    private fun render() {
        val scrollY = scroll.scrollY
        body.removeAllViews()
        val back = Look.row(this).apply {
            minimumHeight = dp(48)
            setPadding(dp(4), 0, dp(16), 0)
            background = touchSurface(Color.TRANSPARENT)
            contentDescription = "Live 프리뷰로 돌아가기"
            isFocusable = true
            setOnClickListener { finish() }
            asButton()
        }
        back.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_action_back)
            imageTintList = ColorStateList.valueOf(Look.primary)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        back.addView(Look.text(this, "Live", 17, Look.primary).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
        body.addView(back, LinearLayout.LayoutParams(-2, -2))
        body.addView(Look.text(this, "Lab", 34, Look.ink, bold = true).apply {
            ViewCompat.setAccessibilityHeading(this, true)
        }, lp(8))

        val device = group()
        entry(device, DeviceIdentity.label(this), DeviceIdentity.platform()) { showDevice() }
        body.addView(device, lp(24))

        section("Inspection") { tools ->
            entry(tools, "Probe", "사양 확인") { open(CameraProbeActivity::class.java) }
            entry(tools, "CTS", "동작 검증") { open(CtsEntryActivity::class.java) }
            entry(tools, "Benchmark", "성능 측정") { open(BenchmarkActivity::class.java) }
        }
        section("Results") { results ->
            entry(results, "Run History", "비교 · 내보내기") { open(HistoryActivity::class.java) }
            entry(results, "Gallery", "사진 · 동영상") { open(GalleryActivity::class.java) }
            entry(results, "ZIP Archives", "공유 · 저장 · 삭제") { incidents.showList() }
        }
        section("Settings") { settings ->
            entry(settings, "Live Streams", "해상도·출력·FPS와 녹화 설정") { streamSettings.launch(Intent(this, LiveStreamsActivity::class.java).putExtras(intent)) }
            entry(settings, "ADB CLI", if (cli.enabled) "허용됨" else "꺼짐") {
                LabDialog(this, "ADB CLI").apply {
                    group {
                        addView(android.widget.Switch(context).apply {
                            text = "ADB CLI 허용"; textSize = 17f; setTextColor(Look.ink)
                            minimumHeight = dp(48)
                            isChecked = cli.enabled
                            setOnCheckedChangeListener { _, checked -> cli.setEnabled(checked) }
                        }, LinearLayout.LayoutParams(-1, -2))
                        addView(Look.text(context, "PC에서 ADB로 검사 명령을 실행할 수 있습니다.", 13, Look.inkMuted))
                    }
                    action("Close", primary = true)
                    onDismiss { render() }
                    show()
                }
            }
            entry(settings, "Reconnect Camera", "카메라 연결 다시 시작") { returnToLive(ACTION_RECONNECT) }
            entry(settings, "About", "앱 버전 · 프로젝트 정보") { AboutSheet.show(this) }
        }
        scroll.post { scroll.scrollTo(0, scrollY) }
    }

    private fun group() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(Look.labSurface); cornerRadius = dp(18).toFloat() }
        clipToOutline = true
    }

    private fun section(title: String, build: (LinearLayout) -> Unit) {
        body.addView(Look.text(this, title, 20, Look.ink, bold = true).apply {
            ViewCompat.setAccessibilityHeading(this, true)
        }, lp(32))
        body.addView(group().also(build), lp(12))
    }

    private fun touchSurface(fill: Int) = RippleDrawable(
        ColorStateList.valueOf(0x180066CC),
        GradientDrawable().apply { setColor(fill) },
        GradientDrawable().apply { setColor(Color.WHITE) }
    )

    private fun View.asButton() {
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        }
    }

    private fun entry(group: LinearLayout, title: String, detail: String? = null, action: () -> Unit) {
        if (group.childCount > 0) {
            group.addView(View(this).apply {
                setBackgroundColor(Look.hairline)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(20); marginEnd = dp(20) })
        }
        val row = Look.row(this).apply {
            minimumHeight = dp(56)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = touchSurface(Color.TRANSPARENT)
            contentDescription = listOfNotNull(title, detail).joinToString(". ")
            isFocusable = true
            setOnClickListener { action() }
            asButton()
        }
        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(Look.text(this@WorkbenchActivity, title, 17, Look.ink))
            if (detail != null) addView(Look.text(this@WorkbenchActivity, detail, 13, Look.inkMuted), lp(4))
        }
        row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_action_next)
            imageTintList = ColorStateList.valueOf(Look.primary)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(12) })
        group.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
    private fun returnToLive(action: String) {
        setResult(RESULT_OK, Intent(pendingStreams ?: Intent()).putExtra(EXTRA_LIVE_ACTION, action))
        finish()
    }

    private fun showDevice() {
        val sheet = LabDialog(this, "Device Info")
        val name = EditText(this).apply {
            setText(DeviceIdentity.alias(this@WorkbenchActivity))
            hint = "예: EVT2 · Lab 03"
            contentDescription = "이 기기의 식별 이름"
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(40))
        }
        sheet.group {
            addView(Look.text(context, "같은 모델을 구별할 이름입니다. 이 앱에만 저장됩니다.", 14, Look.inkMuted))
            addView(name, lp(8))
        }
        sheet.group {
            addView(Look.text(context, DeviceIdentity.report(this@WorkbenchActivity), 12, Look.inkMuted, mono = true).apply { setTextIsSelectable(true) })
        }
        sheet.action("이름 저장", primary = true) { DeviceIdentity.setAlias(this, name.text.toString()); render() }
        sheet.action("기기 정보 복사") {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("HAL CAMERA device", DeviceIdentity.report(this)))
                Toast.makeText(this, "기기·빌드 정보를 복사했습니다.", Toast.LENGTH_SHORT).show()
        }
        sheet.action("Close")
        sheet.show()
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
        const val EXTRA_LIVE_ACTION = "live_action"
        const val ACTION_RECONNECT = "reconnect"
        const val ACTION_STREAMS = "streams"
        const val EXTRA_CAMERA_ID = "camera_id"
        const val EXTRA_ENGINE = "engine"
    }

    private fun dp(value: Int) = Look.dp(this, value)
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
}
