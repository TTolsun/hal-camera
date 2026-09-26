package dev.halcamera

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.halcamera.benchmark.BenchmarkActivity
import dev.halcamera.benchmark.HistoryActivity
import dev.halcamera.cts.CtsEntryActivity
import dev.halcamera.ui.AboutSheet
import dev.halcamera.ui.DeviceIdentity
import dev.halcamera.ui.IconButton
import dev.halcamera.ui.Look

/** An optional camera-free workspace, reached from LIVE's tools menu. */
class WorkbenchActivity : ComponentActivity() {
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    private fun render() {
        body.removeAllViews()
        val header = Look.row(this)
        header.addView(IconButton(this, R.drawable.ic_action_back, "프리뷰로 돌아가기") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(Look.text(this, "HAL CAMERA", 14, Look.onDark, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(IconButton(this, R.drawable.ic_action_info, "앱 정보") { AboutSheet.show(this) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        body.addView(header)
        body.addView(Look.text(this, "카메라 작업실", 24, Look.onDark, bold = true), lp(8))

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

        body.addView(Look.primaryButton(this, "Live · 카메라 열기") { open(MainActivity::class.java) }, lp(16))
        body.addView(Look.text(this, "프리뷰 · 사진·동영상 · 실시간 진단 · Incident ZIP", 12, Look.onDarkMuted), lp(8))
        body.addView(Look.text(this, "검사 도구", 14, Look.onDark, bold = true), lp(20))
        destination("01", "Probe · 사양 확인", "지원 기능 · 해상도 · 스트림 사양") { open(CameraProbeActivity::class.java) }
        destination("02", "CTS · 동작 검증", "검사 선택·단계별 판정·실패 원인") { open(CtsEntryActivity::class.java) }
        destination("03", "Benchmark · 성능 측정", "반복 측정·지연·안정성 비교") { open(BenchmarkActivity::class.java) }
        body.addView(Look.text(this, "저장된 결과", 14, Look.onDark, bold = true), lp(24))
        body.addView(Look.ghostButton(this, "실행 기록 · 비교·내보내기", dark = true) { open(HistoryActivity::class.java) }, lp(12))
        body.addView(Look.ghostButton(this, "갤러리 · 사진·동영상", dark = true) { open(GalleryActivity::class.java) }, lp(8))
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
            contentDescription = "이 기기의 작업실 이름"
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
        if (screen == MainActivity::class.java) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    })
    private fun dp(value: Int) = Look.dp(this, value)
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
}
