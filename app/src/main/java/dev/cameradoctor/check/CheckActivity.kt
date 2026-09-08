package dev.cameradoctor.check

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import dev.cameradoctor.baseline.BaselineStore
import dev.cameradoctor.camera.Camera2Engine
import dev.cameradoctor.diagnosis.DiagnosisRules
import dev.cameradoctor.diagnosis.HealthLevelV2
import dev.cameradoctor.diagnosis.MetricCatalog
import dev.cameradoctor.diagnosis.State
import dev.cameradoctor.diagnosis.jsonName
import dev.cameradoctor.report.HealthReport
import dev.cameradoctor.telemetry.Event
import dev.cameradoctor.telemetry.FlightRecorder
import dev.cameradoctor.telemetry.Telemetry
import dev.cameradoctor.telemetry.nowNs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * M2 Auto Check screen (docs/PRODUCT-v0.2.md chapter 10). Deliberately plain: preview, progress, then the L1/L2 result
 * text and the run JSON path. Consumer Home and the designed result card are M3/M4.
 */
class CheckActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val recorder = FlightRecorder(::nowNs, retentionNs = 120_000_000_000L, maxEvents = 60_000, preNs = 0, postNs = 0)
    private val telemetry = Telemetry(recorder)
    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var startButton: Button
    private var runner: AutoCheckRunner? = null
    private var engine: Camera2Engine? = null
    private val conditions = "engine=camera2,preview=1280x720,yuv=640x480,fmt=jpeg,res=1920x1080,zsl=off,trigger=off"
    private var firstStartedSeen = false
    private var firstYuvSeen = false
    private val envStart = HashMap<String, Any?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        preview = TextureView(this)
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(40), dp(16), dp(16)); setBackgroundColor(Color.argb(170, 12, 19, 26)) }
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        status = text("카메라 검사 준비", 16, true)
        panel.addView(status)
        panel.addView(text("밝은 곳에서 글자나 물건을 향해 폰을 들고 60초 동안 움직이지 마세요.", 13, false).apply { setPadding(0, dp(8), 0, dp(8)) })
        val scroll = ScrollView(this)
        result = text("", 12, false).apply { typeface = Typeface.MONOSPACE }
        scroll.addView(result)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        startButton = Button(this).apply { text = "검사 시작"; isAllCaps = false; setOnClickListener { begin() } }
        buttons.addView(startButton, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(Button(this).apply { text = "닫기"; isAllCaps = false; setOnClickListener { finish() } }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(buttons)
        recorder.listener = { e -> main.post { onEvent(e) } }
    }

    override fun onStop() {
        runner?.abort("background")
        super.onStop()
    }

    private fun hasPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun begin() {
        if (!hasPermission()) { status.text = "카메라 권한이 필요합니다. 메인 화면에서 권한을 허용한 뒤 다시 시도하세요."; return }
        if (runner != null) return
        val thermal = thermalStatus()
        if (thermal != null && thermal >= PowerManager.THERMAL_STATUS_SEVERE) { status.text = "기기가 뜨겁습니다. 식힌 뒤 검사하세요."; return }
        startButton.isEnabled = false
        result.text = ""
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
            override fun close(session: String) { val e = engine; engine = null; if (e == null) runner?.signal(session, AutoCheckRunner.Signal.CLOSED) else e.close { } }
        }
        val listener = object : AutoCheckRunner.Listener {
            override fun onStep(endpoint: CameraEndpoint, index: Int, total: Int, step: AutoCheckRunner.Step) {
                status.text = "${index + 1}/$total  ${roleText(endpoint.role)}  ·  ${stepText(step)}"
            }
            override fun onEndpointDone(result: AutoCheckRunner.EndpointResult) {
                this@CheckActivity.result.append("${roleText(result.endpoint.role)}: " +
                    (result.hardFailure?.let { "${stepText(AutoCheckRunner.Step.valueOf(it))} 단계에서 실패" }
                        ?: "카메라 켜기 ${ms(result.openMs)} · 화면 ${ms(result.previewTotalMs)} · 사진 ${result.stillLatenciesMs.joinToString("/") { ms(it) }}") + "\n")
            }
            override fun onFinished(results: List<AutoCheckRunner.EndpointResult>, aborted: String?) { finishRun(results, aborted, endpoints) }
        }
        runner = AutoCheckRunner(driver, scheduler, ::nowNs, AutoCheckRunner.Config(), listener).also { it.start(endpoints) }
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
            if (entry == null && ev.baselineCandidate != null && qualifiesAsBaseline()) {
                store.put(r.endpoint.key, conditions, runId, ev.baselineCandidate)
                refs[r.endpoint.key] = mapOf("run_id" to runId, "valid" to true, "created_now" to true)
            }
            ev
        }
        val overall = if (aborted != null) HealthLevelV2.INSUFFICIENT else evaluator.overall(evaluations)
        val env = envStart + environment().mapKeys { "end_" + it.key } + mapOf("aborted" to aborted, "endpoints_enumerated" to endpoints.size)
        val file = try { HealthReport(this).write(runId, results, evaluations, overall.jsonName, aborted, env, events, refs) } catch (e: Exception) { null }

        val sb = StringBuilder()
        sb.append("카메라 상태  ● ${levelText(overall)}\n")
        if (aborted != null) sb.append("검사가 중단되었습니다: $aborted\n")
        val allStates = evaluations.flatMap { it.states }
        val judged = allStates.filter { it.final != State.UNKNOWN || it.unknownReason != dev.cameradoctor.diagnosis.UnknownReason.NOT_RUN }
        val counts = State.values().associateWith { s -> judged.count { it.final == s } }
        sb.append("${judged.size}개 항목 검사 · 정상 ${counts[State.PASS]} · 주의 ${counts[State.WARN]} · 이상 ${counts[State.FAIL]} · 미판정 ${counts[State.UNKNOWN]}\n")
        val createdNow = refs.values.any { it["created_now"] == true }
        if (createdNow) sb.append("첫 검사가 완료되었습니다. 이 결과를 기준으로 저장했습니다. 다음 검사부터 변화도 함께 확인합니다.\n")
        sb.append("\n")
        evaluations.forEach { ev ->
            sb.append("■ ${roleText(ev.endpoint.role)}  ${levelText(ev.health.level)}\n")
            sb.append("  ${DiagnosisRules.CONSUMER_TEXT[ev.diagnosis.rule] ?: ev.diagnosis.rule}\n")
            ev.states.filter { it.final != State.UNKNOWN || it.unknownReason != dev.cameradoctor.diagnosis.UnknownReason.NOT_RUN }
                .sortedBy { -it.final.ordinal.let { o -> if (it.final == State.UNKNOWN) -1 else o } }
                .forEach { s -> sb.append("  ${MetricCatalog.consumerLine(s)}\n") }
            sb.append("\n")
        }
        sb.append("상세 데이터는 run JSON에 있습니다: ${file?.name ?: "저장 실패"}\n")
        result.text = sb.toString()
        status.text = "검사 완료"
        startButton.isEnabled = true
        runner = null
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
    private fun ms(v: Double?) = v?.let { String.format(Locale.US, "%.0f ms", it) } ?: "—"
    private fun levelText(l: HealthLevelV2) = when (l) {
        HealthLevelV2.NORMAL -> "정상"; HealthLevelV2.WARNING -> "주의"; HealthLevelV2.ISSUE -> "문제 발견"; HealthLevelV2.INSUFFICIENT -> "판정 불가"
    }
    private fun stepText(s: AutoCheckRunner.Step) = when (s) {
        AutoCheckRunner.Step.OPEN -> "카메라 켜는 중"; AutoCheckRunner.Step.CONFIGURE -> "준비 중"; AutoCheckRunner.Step.FIRST_FRAME -> "첫 화면 기다리는 중"
        AutoCheckRunner.Step.OBSERVE -> "10초 관찰 중"; AutoCheckRunner.Step.STILL -> "사진 촬영 중"; AutoCheckRunner.Step.CLOSE -> "카메라 끄는 중"
        AutoCheckRunner.Step.DONE -> "완료"; AutoCheckRunner.Step.ABORTED -> "중단"; AutoCheckRunner.Step.IDLE -> "대기"
    }
    private fun roleText(r: LensRole) = when (r) {
        LensRole.MAIN -> "후면 메인 카메라"; LensRole.ULTRA_WIDE -> "후면 초광각"; LensRole.TELE -> "후면 망원"
        LensRole.FRONT -> "전면 카메라"; LensRole.EXTERNAL -> "외부 카메라"; LensRole.UNKNOWN -> "후면 카메라"
    }
    private fun text(s: String, size: Int, bold: Boolean) = TextView(this).apply { text = s; textSize = size.toFloat(); setTextColor(Color.WHITE); if (bold) typeface = Typeface.DEFAULT_BOLD }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
