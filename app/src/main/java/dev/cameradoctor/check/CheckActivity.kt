package dev.cameradoctor.check

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import dev.cameradoctor.baseline.BaselineStore
import dev.cameradoctor.camera.Camera2Engine
import dev.cameradoctor.diagnosis.DiagnosisRules
import dev.cameradoctor.diagnosis.HealthLevelV2
import dev.cameradoctor.diagnosis.MetricCatalog
import dev.cameradoctor.diagnosis.State
import dev.cameradoctor.diagnosis.UnknownReason
import dev.cameradoctor.diagnosis.jsonName
import dev.cameradoctor.report.HealthReport
import dev.cameradoctor.telemetry.Event
import dev.cameradoctor.telemetry.FlightRecorder
import dev.cameradoctor.telemetry.Telemetry
import dev.cameradoctor.telemetry.nowNs
import dev.cameradoctor.ui.Look
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Auto Check screen (docs/PRODUCT-v0.2.md chapter 10 and 11.3). Three phases: guidance, running (preview + progress),
 * and the three-layer result (L1 verdict, L2 evidence, L3 raw behind "상세 분석 보기").
 * With EXTRA_SHOW_LATEST the latest stored run is rendered without running the camera.
 */
class CheckActivity : ComponentActivity() {
    companion object { const val EXTRA_SHOW_LATEST = "show_latest" }

    private val main = Handler(Looper.getMainLooper())
    private val recorder = FlightRecorder(::nowNs, retentionNs = 120_000_000_000L, maxEvents = 60_000, preNs = 0, postNs = 0)
    private val telemetry = Telemetry(recorder)
    private lateinit var root: FrameLayout
    private lateinit var preview: TextureView
    private lateinit var runPanel: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: TextView
    private lateinit var resultScroll: ScrollView
    private var runner: AutoCheckRunner? = null
    private var engine: Camera2Engine? = null
    private val conditions = "engine=camera2,preview=1280x720,yuv=640x480,fmt=jpeg,res=1920x1080,zsl=off,trigger=off"
    private var firstStartedSeen = false
    private var firstYuvSeen = false
    private val envStart = HashMap<String, Any?>()
    private var destroyed = false
    private var expertVisible = false
    private var current: CheckResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        preview = TextureView(this)
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))

        runPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM; setPadding(dp(20), dp(48), dp(20), dp(28)) }
        runPanel.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(230, 0, 0, 0), Color.argb(60, 0, 0, 0)))
        root.addView(runPanel, FrameLayout.LayoutParams(-1, -1))
        val guide = Look.card(this, dark = true)
        status = Look.text(this, "검사 준비", 21, Look.onDark, bold = true)
        guide.addView(status)
        guide.addView(Look.text(this, "밝은 곳에서 글자나 물건을 향해 폰을 들고 60초 동안 움직이지 마세요.", 15, Look.onDarkMuted), lp(top = 6))
        progress = Look.text(this, "", 14, Look.onDarkMuted, mono = true)
        guide.addView(progress, lp(top = 10))
        runPanel.addView(guide)
        val buttons = Look.row(this)
        buttons.addView(Look.primaryButton(this, "검사 시작") { begin() }.also { startButton = it }, LinearLayout.LayoutParams(0, dp(56), 1f))
        buttons.addView(Look.ghostButton(this, "닫기", dark = true) { finish() }, LinearLayout.LayoutParams(-2, dp(56)).apply { marginStart = dp(10) })
        runPanel.addView(buttons, lp(top = 14))

        resultScroll = ScrollView(this).apply { setBackgroundColor(Look.canvas); isFillViewport = true; visibility = View.GONE }
        root.addView(resultScroll, FrameLayout.LayoutParams(-1, -1))
        recorder.listener = { e -> main.post { if (!destroyed) onEvent(e) } }

        if (intent.getBooleanExtra(EXTRA_SHOW_LATEST, false)) {
            val file = File(filesDir, "checks").listFiles()?.filter { it.extension == "json" }?.maxByOrNull { it.lastModified() }
            val r = file?.let { CheckResult.fromFile(it) }
            if (r != null) showResult(r) else status.text = "저장된 검사 결과가 없습니다"
        }
    }
    private lateinit var startButton: android.widget.Button

    override fun onStop() { runner?.abort("background"); super.onStop() }
    override fun onDestroy() { destroyed = true; recorder.listener = null; super.onDestroy() }

    private fun hasPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun begin() {
        if (!hasPermission()) { status.text = "카메라 권한이 필요합니다. 홈 화면에서 검사를 시작하면 권한을 요청합니다."; return }
        if (runner != null) return
        val thermal = thermalStatus()
        if (thermal != null && thermal >= PowerManager.THERMAL_STATUS_SEVERE) { status.text = "기기가 뜨겁습니다. 식힌 뒤 검사하세요."; return }
        resultScroll.visibility = View.GONE
        startButton.isEnabled = false
        progress.text = ""
        envStart.clear(); envStart += environment()
        val manager = getSystemService(CameraManager::class.java)
        val endpoints = LensRoles.checkOrder(CameraEndpointResolver(manager).resolve())
        recorder.record("app", "check_endpoints", values = mapOf("endpoints" to endpoints.map { it.toJsonMap() }))
        val scheduler = object : AutoCheckRunner.Scheduler {
            override fun after(delayMs: Long, action: () -> Unit): Any { val r = Runnable { action() }; main.postDelayed(r, delayMs); return r }
            override fun cancel(token: Any) { main.removeCallbacks(token as Runnable) }
        }
        val driver = object : AutoCheckRunner.Driver {
            override fun open(endpoint: CameraEndpoint, session: String) {
                firstStartedSeen = false; firstYuvSeen = false
                engine = Camera2Engine(this@CheckActivity, preview, endpoint.logicalCameraId, session, telemetry) { _, _ -> }.also { it.start() }
            }
            override fun still(session: String) { engine?.capture() }
            override fun close(session: String) {
                val e = engine; engine = null
                // Camera2Engine also records a "closed" event; a second CLOSED signal for the same session is ignored by the runner.
                if (e == null) runner?.signal(session, AutoCheckRunner.Signal.CLOSED) else e.close { runner?.signal(session, AutoCheckRunner.Signal.CLOSED) }
            }
        }
        val listener = object : AutoCheckRunner.Listener {
            override fun onStep(endpoint: CameraEndpoint, index: Int, total: Int, step: AutoCheckRunner.Step) {
                status.text = "${index + 1}/$total  ${roleText(endpoint.role)}"
                progress.text = bar(index, total, step) + "  " + stepText(step)
            }
            override fun onEndpointDone(result: AutoCheckRunner.EndpointResult) = Unit
            override fun onFinished(results: List<AutoCheckRunner.EndpointResult>, aborted: String?) { if (!destroyed) finishRun(results, aborted, endpoints) }
        }
        runner = AutoCheckRunner(driver, scheduler, ::nowNs, AutoCheckRunner.Config(), listener).also { it.start(endpoints) }
    }

    private fun bar(index: Int, total: Int, step: AutoCheckRunner.Step): String {
        val perStep = mapOf(AutoCheckRunner.Step.OPEN to 0.05, AutoCheckRunner.Step.CONFIGURE to 0.1, AutoCheckRunner.Step.FIRST_FRAME to 0.15,
            AutoCheckRunner.Step.OBSERVE to 0.3, AutoCheckRunner.Step.STILL to 0.85, AutoCheckRunner.Step.CLOSE to 0.95)
        val f = ((index + (perStep[step] ?: 1.0)) / total).coerceIn(0.0, 1.0)
        val n = (f * 20).toInt()
        return "█".repeat(n) + "░".repeat(20 - n)
    }

    /** Maps Camera2Engine telemetry events to runner signals (10.1). Runs on the main thread. */
    private fun onEvent(e: Event) {
        val r = runner ?: return
        val s = e.session
        when (e.kind) {
            "open_call" -> r.mark(s, "open_call", e.atNs, override = true)
            "opened" -> r.signal(s, AutoCheckRunner.Signal.OPENED, e.atNs)
            "configure_requested" -> r.mark(s, "configure_call", e.atNs)
            "session_configured" -> r.signal(s, AutoCheckRunner.Signal.CONFIGURED, e.atNs)
            "repeating_submit" -> r.mark(s, "repeating_call", e.atNs)
            "capture_started" -> if (!firstStartedSeen && s == r.currentSession) { firstStartedSeen = true; r.firstStarted(s, e.atNs) }
            "image_available" -> when (e.values["stream"]) {
                "still" -> r.signal(s, AutoCheckRunner.Signal.STILL_RECEIVED, e.atNs)
                else -> if (!firstYuvSeen && s == r.currentSession) { firstYuvSeen = true; r.signal(s, AutoCheckRunner.Signal.FIRST_FRAME, e.atNs) }
            }
            "capture_result" -> if ((e.values["requestTag"] as? String)?.startsWith("still-") == true) r.stillResult(s, e.atNs)
            "closed" -> r.signal(s, AutoCheckRunner.Signal.CLOSED, e.atNs)
            "camera_error", "configure_failed", "capture_timeout" -> r.signal(s, AutoCheckRunner.Signal.ERROR, e.atNs, e.kind)
        }
    }

    private fun finishRun(results: List<AutoCheckRunner.EndpointResult>, aborted: String?, endpoints: List<CameraEndpoint>) {
        val runId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val events = recorder.snapshot()
        val store = BaselineStore(this)
        val evaluator = CheckEvaluator()
        val mpc = if (Build.VERSION.SDK_INT >= 31) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0
        val refs = HashMap<String, Map<String, Any?>>()
        val evaluations = results.map { r ->
            val entry = store.get(r.endpoint.key, conditions)
            val ev = evaluator.evaluate(r, events, entry?.values, mpc)
            refs[r.endpoint.key] = mapOf("run_id" to entry?.runId, "valid" to (entry != null), "created_now" to false)
            if (entry == null && ev.baselineCandidate != null && aborted == null && qualifiesAsBaseline()) {
                store.put(r.endpoint.key, conditions, runId, ev.baselineCandidate)
                refs[r.endpoint.key] = mapOf("run_id" to runId, "valid" to true, "created_now" to true)
            }
            ev
        }
        val overall = if (aborted != null) HealthLevelV2.INSUFFICIENT else evaluator.overall(evaluations)
        val env = envStart + environment().mapKeys { "end_" + it.key } + mapOf("aborted" to aborted, "endpoints_enumerated" to endpoints.size)
        val file = try { HealthReport(this).write(runId, results, evaluations, overall.jsonName, aborted, env, events, refs) } catch (e: Exception) { null }
        runner = null
        startButton.isEnabled = true
        status.text = "검사 완료"
        showResult(CheckResult.fromEvaluations(runId, overall.jsonName, aborted, evaluations, results, refs.values.any { it["created_now"] == true }, file))
    }

    // ---- Result rendering: L1 verdict, L2 evidence, L3 raw (11.1, 11.3) ----

    private fun showResult(r: CheckResult) {
        current = r
        resultScroll.removeAllViews()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(40), dp(20), dp(28)) }
        resultScroll.addView(body)
        body.addView(Look.text(this, "CAMERA HEALTH", 12, Look.inkMuted, bold = true).apply { letterSpacing = 0.12f })

        val l1 = Look.card(this)
        val head = Look.row(this)
        head.addView(View(this).apply { background = Look.pill(this@CheckActivity, Look.statusColor(r.level)); layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)) })
        head.addView(Look.text(this, levelText(r.level), 34, Look.ink, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        l1.addView(head)
        val worst = r.endpoints.maxByOrNull { DiagnosisRules.PRIORITY.indexOf(it.rule).let { i -> if (i < 0) 99 else -i } }
        l1.addView(Look.text(this, DiagnosisRules.CONSUMER_TEXT[worst?.rule ?: "normal"] ?: "", 17, Look.ink), lp(top = 8))
        if (r.aborted != null) l1.addView(Look.text(this, "검사가 중단되었습니다 (${r.aborted}). 다시 검사해 주세요.", 14, Look.statusWarn), lp(top = 6))
        val judged = r.endpoints.flatMap { it.states }.filter { it.final != State.UNKNOWN || it.unknownReason != UnknownReason.NOT_RUN }
        val c = State.values().associateWith { s -> judged.count { it.final == s } }
        l1.addView(Look.text(this, "${judged.size}개 항목 검사\n정상 ${c[State.PASS]} · 주의 ${c[State.WARN]} · 이상 ${c[State.FAIL]} · 미판정 ${c[State.UNKNOWN]}", 14, Look.inkMuted), lp(top = 10))
        if (r.baselineCreated) l1.addView(Look.text(this, "첫 검사가 완료되었습니다. 이 결과를 기준으로 저장했습니다. 다음 검사부터 변화도 함께 확인합니다.", 14, Look.ink), lp(top = 10))
        body.addView(l1, lp(top = 12))

        r.endpoints.forEach { ep ->
            val card = Look.card(this)
            val hr = Look.row(this)
            hr.addView(Look.text(this, roleText(ep.role), 21, Look.ink, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            hr.addView(Look.text(this, levelText(ep.level), 15, Look.statusColor(ep.level), bold = true))
            card.addView(hr)
            card.addView(Look.text(this, DiagnosisRules.CONSUMER_TEXT[ep.rule] ?: ep.rule, 14, Look.inkMuted), lp(top = 4))
            // L2: judged metrics only, worst first, consumer vocabulary.
            ep.states.filter { it.final != State.UNKNOWN || it.unknownReason != UnknownReason.NOT_RUN }
                .sortedByDescending { if (it.final == State.UNKNOWN) -1 else it.final.ordinal }
                .forEach { s -> card.addView(Look.text(this, MetricCatalog.consumerLine(s), 15, if (s.final == State.WARN || s.final == State.FAIL) Look.ink else Look.inkMuted), lp(top = 6)) }
            recommendation(ep.rule)?.let { card.addView(Look.text(this, "권장  $it", 14, Look.ink), lp(top = 12)) }
            // L3: expert lines, hidden by default.
            val expert = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = if (expertVisible) View.VISIBLE else View.GONE; tag = "expert" }
            expert.addView(Look.text(this, "상세 분석", 12, Look.inkMuted, bold = true), lp(top = 14))
            ep.states.filter { it.unknownReason != UnknownReason.NOT_RUN }.forEach { s -> expert.addView(Look.text(this, MetricCatalog.expertLine(s), 12, Look.ink, mono = true), lp(top = 4)) }
            expert.addView(Look.text(this, ep.rawMs.entries.joinToString("\n") { (k, v) -> "$k: $v" } + (ep.hardFailure?.let { "\nhard failure at $it" } ?: ""), 11, Look.inkMuted, mono = true), lp(top = 8))
            card.addView(expert)
            body.addView(card, lp(top = 12))
        }

        val actions = Look.row(this)
        actions.addView(Look.ghostButton(this, if (expertVisible) "상세 분석 닫기" else "상세 분석 보기") { expertVisible = !expertVisible; showResult(r) }, LinearLayout.LayoutParams(0, dp(52), 1f))
        actions.addView(Look.ghostButton(this, "결과 공유") { r.file?.let(::share) }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        body.addView(actions, lp(top = 16))
        val actions2 = Look.row(this)
        actions2.addView(Look.primaryButton(this, "다시 검사") { resultScroll.visibility = View.GONE; status.text = "검사 준비"; begin() }, LinearLayout.LayoutParams(0, dp(56), 1f))
        actions2.addView(Look.ghostButton(this, "닫기") { finish() }, LinearLayout.LayoutParams(-2, dp(56)).apply { marginStart = dp(8) })
        body.addView(actions2, lp(top = 8))
        body.addView(Look.text(this, r.file?.let { "run ${r.runId} · ${it.name}" } ?: "run JSON 저장 실패", 11, Look.inkMuted), lp(top = 14))
        resultScroll.visibility = View.VISIBLE
        window.statusBarColor = Look.canvas
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
    }

    private fun recommendation(rule: String): String? = when (rule) {
        "normal" -> null
        "hard_failure" -> "다른 앱이 카메라를 사용 중인지 확인한 뒤 다시 검사하세요."
        "three_a_unstable", "three_a_searching" -> "렌즈를 닦고 밝은 곳에서 다시 검사하세요."
        "insufficient_evidence" -> "밝은 곳에서 다시 검사하세요."
        "cadence_change" -> "더 밝은 곳에서 검사하면 정확합니다."
        else -> "앱을 모두 종료하고 기기를 재시작한 뒤 다시 검사해 보세요. 반복되면 결과를 공유해 주세요."
    }

    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri); clipData = ClipData.newRawUri("check", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "검사 결과 공유"))
    }

    private fun qualifiesAsBaseline(): Boolean {
        val thermal = thermalStatus()
        val battery = batteryPercent()
        return (thermal == null || thermal <= PowerManager.THERMAL_STATUS_LIGHT) && (battery == null || battery >= 20)
    }

    private fun environment(): Map<String, Any?> = mapOf(
        "thermal" to thermalStatus(), "battery_pct" to batteryPercent(),
        "charging" to (getSystemService(BatteryManager::class.java)?.isCharging),
        "rotation" to rotation()
    )
    @Suppress("DEPRECATION")
    private fun rotation(): Int = if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: 0 else windowManager.defaultDisplay.rotation
    private fun thermalStatus(): Int? = if (Build.VERSION.SDK_INT >= 29) getSystemService(PowerManager::class.java)?.currentThermalStatus else null
    private fun batteryPercent(): Int? = getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it > 0 }
    private fun levelText(l: String) = when (l.lowercase()) { "normal" -> "정상"; "warning" -> "주의"; "issue" -> "문제 발견"; else -> "판정 불가" }
    private fun stepText(s: AutoCheckRunner.Step) = when (s) {
        AutoCheckRunner.Step.OPEN -> "카메라 켜는 중"; AutoCheckRunner.Step.CONFIGURE -> "준비 중"; AutoCheckRunner.Step.FIRST_FRAME -> "첫 화면 기다리는 중"
        AutoCheckRunner.Step.OBSERVE -> "10초 관찰 중"; AutoCheckRunner.Step.STILL -> "사진 촬영 중"; AutoCheckRunner.Step.CLOSE -> "카메라 끄는 중"
        AutoCheckRunner.Step.DONE -> "완료"; AutoCheckRunner.Step.ABORTED -> "중단"; AutoCheckRunner.Step.IDLE -> "대기"
    }
    private fun roleText(r: LensRole) = roleText(r.name)
    private fun roleText(r: String) = when (r) {
        "MAIN" -> "후면 메인 카메라"; "ULTRA_WIDE" -> "후면 초광각"; "TELE" -> "후면 망원"; "FRONT" -> "전면 카메라"; "EXTERNAL" -> "외부 카메라"; else -> "후면 카메라"
    }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(v: Int) = Look.dp(this, v)
}
