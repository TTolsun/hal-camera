package dev.cameradoctor.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import dev.cameradoctor.MainActivity
import dev.cameradoctor.check.CheckActivity
import dev.cameradoctor.diagnosis.DiagnosisRules
import dev.cameradoctor.ui.Look
import java.text.SimpleDateFormat
import java.util.Locale

/** Consumer Home (docs/PRODUCT-v0.2.md 11.2). Light canvas, one blue action, judged state from the latest run JSON. */
class HomeActivity : ComponentActivity() {
    private lateinit var body: LinearLayout
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startActivity(Intent(this, CheckActivity::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Look.canvas
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        val scroll = ScrollView(this).apply { setBackgroundColor(Look.canvas); isFillViewport = true }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(28)) }
        scroll.addView(body)
        setContentView(scroll)
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        body.removeAllViews()
        body.addView(Look.text(this, "CAMERA DOCTOR", 12, Look.inkMuted, bold = true).apply { letterSpacing = 0.12f })
        body.addView(Look.text(this, "내 카메라는 건강할까요?", 34, Look.ink, bold = true), lp(top = 8))

        val latest = RunSummary.load(this)
        val status = Look.card(this)
        if (latest == null) {
            status.addView(Look.text(this, "아직 검사하지 않았습니다", 21, Look.ink, bold = true))
            status.addView(Look.text(this, "60초 검사로 카메라 켜기, 첫 화면, 프레임 흐름, 초점·노출, 사진 촬영을 자동으로 확인합니다.", 14, Look.inkMuted), lp(top = 6))
        } else {
            val head = Look.row(this)
            head.addView(dot(latest.level))
            head.addView(Look.text(this, levelText(latest.level), 34, Look.ink, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            status.addView(head)
            status.addView(Look.text(this, "마지막 검사 ${runDate(latest.runId)}", 14, Look.inkMuted), lp(top = 4))
            if (latest.aborted != null) status.addView(Look.text(this, "이 검사는 끝나기 전에 중단되었습니다(${abortText(latest.aborted)}). 다시 검사해 주세요.", 14, Look.statusWarn), lp(top = 8))
            latest.newerAbortedRunId?.let { status.addView(Look.text(this, "${runDate(it)} 검사는 끝나기 전에 중단되어 결과에 반영하지 않았습니다.", 13, Look.inkMuted), lp(top = 8)) }
            if (latest.baselineCreated) status.addView(Look.text(this, "이 결과를 기준으로 저장했습니다. 다음 검사부터 변화도 함께 확인합니다.", 14, Look.ink), lp(top = 8))
            latest.endpoints.forEach { e ->
                val r = Look.row(this)
                r.addView(Look.text(this, roleText(e.role), 17, Look.ink), LinearLayout.LayoutParams(0, -2, 1f))
                r.addView(Look.text(this, mark(e.level), 17, Look.statusColor(e.level), bold = true))
                status.addView(r, lp(top = 10))
                val text = DiagnosisRules.CONSUMER_TEXT[e.diagnosis]
                if (text != null && e.diagnosis != "normal") status.addView(Look.text(this, text, 13, Look.inkMuted), lp(top = 2))
            }
            status.addView(Look.ghostButton(this, "결과 자세히 보기") {
                startActivity(Intent(this, CheckActivity::class.java).putExtra(CheckActivity.EXTRA_SHOW_LATEST, true).putExtra(CheckActivity.EXTRA_RUN_FILE, latest.file.absolutePath))
            }, lp(top = 14))
        }
        body.addView(status, lp(top = 20))

        body.addView(Look.primaryButton(this, "60초 카메라 검사") { startCheck() }, lp(top = 20, height = 56))

        val incident = Look.card(this)
        incident.addView(Look.text(this, "카메라가 이상했나요?", 21, Look.ink, bold = true))
        incident.addView(Look.text(this, "카메라 화면이 열리면 아래 버튼으로 직전 10초와 이후 5초를 저장하고 분석합니다.", 14, Look.inkMuted), lp(top = 6))
        incident.addView(Look.ghostButton(this, "방금 이상했어요") {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_CONSUMER, true))
        }, lp(top = 12))
        body.addView(incident, lp(top = 16))

        val expert = Look.row(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(10), dp(4), dp(10)); setOnClickListener { startActivity(Intent(this@HomeActivity, MainActivity::class.java)) } }
        expert.addView(Look.text(this, "Expert Diagnostics", 17, Look.primary), LinearLayout.LayoutParams(0, -2, 1f))
        expert.addView(Look.text(this, "›", 21, Look.primary))
        body.addView(expert, lp(top = 20))
        body.addView(Look.text(this, "검사 결과는 이 기기에만 저장됩니다. 사진은 저장하지 않습니다.", 12, Look.inkMuted), lp(top = 12))
    }

    private fun startCheck() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startActivity(Intent(this, CheckActivity::class.java))
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun dot(level: String) = View(this).apply {
        background = Look.pill(this@HomeActivity, Look.statusColor(level))
        layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
    }
    private fun mark(level: String) = when (level.lowercase()) { "normal" -> "✓"; "warning" -> "△"; "issue" -> "✗"; else -> "○" }
    private fun levelText(level: String) = when (level.lowercase()) { "normal" -> "정상"; "warning" -> "주의"; "issue" -> "문제 발견"; else -> "판정 불가" }
    private fun abortText(reason: String) = when (reason) { "background" -> "앱이 화면에서 벗어남"; "no_camera" -> "사용할 카메라 없음"; "permission" -> "권한 없음"; else -> "검사 중단" }
    private fun roleText(role: String) = when (role) {
        "MAIN" -> "후면 메인"; "ULTRA_WIDE" -> "후면 초광각"; "TELE" -> "후면 망원"; "FRONT" -> "전면"; "EXTERNAL" -> "외부"; else -> "후면"
    }
    private fun runDate(runId: String) = try {
        SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(runId)!!)
    } catch (_: Exception) { runId }
    private fun lp(top: Int = 0, height: Int = -2) = LinearLayout.LayoutParams(-1, if (height > 0) dp(height) else height).apply { topMargin = dp(top) }
    private fun dp(v: Int) = Look.dp(this, v)
}
