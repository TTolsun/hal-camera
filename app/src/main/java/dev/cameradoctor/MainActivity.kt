package dev.cameradoctor

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import dev.cameradoctor.camera.*
import dev.cameradoctor.diagnosis.Assessment
import dev.cameradoctor.diagnosis.HealthLevel
import dev.cameradoctor.diagnosis.HealthMonitor
import dev.cameradoctor.telemetry.*
import dev.cameradoctor.ui.ScopeView
import dev.cameradoctor.ui.StripView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_CONSUMER = "consumer" }
    /** Consumer incident mode from Home: same engine and recorder, plain-language button and summary. */
    private var consumer = false
    private var incidentAssessment: Assessment? = null
    private lateinit var summaryCard: TextView
    private lateinit var verdictCard: TextView
    private lateinit var timelineView: dev.cameradoctor.ui.TimelineView
    // Expert palette from docs/design/DESIGN.md via ui/Look (PRODUCT-v0.2 11.6): dark tiles, one blue accent, status colours only for state marks.
    private val bg = dev.cameradoctor.ui.Look.expertTile
    private val panel = dev.cameradoctor.ui.Look.expertTile2
    private val mint = dev.cameradoctor.ui.Look.primaryOnDark
    private val muted = dev.cameradoctor.ui.Look.onDarkMuted
    private val coral = dev.cameradoctor.ui.Look.statusFail
    private val glass = Color.argb(150,39,39,41)
    private val main = Handler(Looper.getMainLooper())
    private val cameraWorker = Executors.newSingleThreadExecutor()
    private val io = Executors.newSingleThreadExecutor()
    private val recorder = FlightRecorder(::nowNs)
    private val telemetry = Telemetry(recorder)
    private var engine: CameraEngine? = null
    private var closing = false
    private var resumed = false
    private var destroyed = false
    private var paused = false
    private var engineName = "CameraX"
    private var cameraId = ""
    private var sessionId = ""
    private var exporting = 0
    private var ready = false
    private var zoomRatio = 1f
    private var zoomApplied = false
    private var latestFile: File? = null
    private var saveFile: File? = null
    private var previousCpu = Process.getElapsedCpuTime()
    private var previousSample = SystemClock.elapsedRealtime()
    private var lastSystemNs = 0L
    private lateinit var manager: CameraManager
    private lateinit var previewHost: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var diagnostics: ScrollView
    private lateinit var zoomRow: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var healthBanner: TextView
    private lateinit var healthCard: TextView
    private lateinit var strip: StripView
    private lateinit var stripText: TextView
    private val health = HealthMonitor()
    private var lastHealth: Assessment? = null
    private lateinit var metrics: TextView
    private lateinit var timeline: TextView
    private lateinit var system: TextView
    private lateinit var scope: ScopeView
    private lateinit var reportButton: Button
    private lateinit var captureButton: Button
    private lateinit var engineX: Button
    private lateinit var engine2: Button
    private lateinit var pauseButton: Button
    private lateinit var recorderText: TextView
    private lateinit var shareButton: Button
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) restartCamera() else setStatus("카메라 권한이 필요합니다 · 진단 패널의 권한 버튼으로 재시도", false)
    }
    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = saveFile
        if (uri != null && file != null) io.execute {
            try {
                contentResolver.openOutputStream(uri)?.use { target -> file.inputStream().use { it.copyTo(target) } }
                    ?: error("저장 위치를 열 수 없습니다")
                main.post { if (!destroyed) toast("선택한 위치에 저장했습니다") }
            } catch (e: Exception) { main.post { if (!destroyed) toast("저장 실패: ${e.message}") } }
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!resumed) return
            val time = nowNs()
            val events = recorder.snapshot(10_000_000_000L)
            val frames = events.filter { it.session == sessionId && it.kind == "capture_result" }
            // Vertical cursors on every scope track: incident triggers (red) and the frames that produced a WARNING (orange).
            val markers = events.filter { it.kind == "incident_trigger" }.map { Triple(it.atNs, "ISSUE", true) } +
                events.filter { it.kind == "health_assessment" && it.values["level"] == "WARNING" && it.values["held"] != true }
                    .map { Triple(it.atNs, (it.values["case"] as? String)?.uppercase(Locale.US)?.replace('_', ' ') ?: "WARN", false) }
            scope.update(frames, time, markers)
            updateReadings(events, frames, time)
            updateHealth(events, frames, time)
            if (time-lastSystemNs >= 1_000_000_000L) { sampleSystem(); lastSystemNs=time }
            recorder.finish()?.let { export(it) }
            val remaining = recorder.remainingNs()
            reportButton.isEnabled = remaining == null && ready && !paused
            reportButton.text = if (remaining != null) "${"%.1f".format(Locale.US, remaining/1e9)}s" else incidentLabel()
            val span = events.firstOrNull()?.let { (time-it.atNs)/1e9 } ?: 0.0
            recorderText.text = if (exporting > 0) "ZIP 저장 중…" else if (remaining != null) "기록 중 · 이후 ${"%.1f".format(Locale.US, remaining/1e9)}초 남음" else "30s 순환 버퍼  ·  ${"%.1f".format(Locale.US, span.coerceAtMost(10.0))}s / 10s 사전 기록 준비"
            main.postDelayed(this, 100)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        consumer = intent.getBooleanExtra(EXTRA_CONSUMER, false)
        engineName = savedInstanceState?.getString("engine") ?: if (consumer) "Camera2" else "CameraX"
        cameraId = savedInstanceState?.getString("camera") ?: ""
        paused = savedInstanceState?.getBoolean("paused") ?: false
        zoomRatio = savedInstanceState?.getFloat("zoom") ?: 1f
        manager = getSystemService(CameraManager::class.java)
        buildUi()
        latestFile = incidentFiles().firstOrNull()
        shareButton.isEnabled = latestFile != null
        recorder.record("app", "clock_anchor", values = mapOf("wallTimeMs" to System.currentTimeMillis(), "uptimeMs" to SystemClock.uptimeMillis()))
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("engine", engineName); outState.putString("camera", cameraId); outState.putBoolean("paused", paused); outState.putFloat("zoom", zoomRatio)
        super.onSaveInstanceState(outState)
    }
    override fun onStart() {
        super.onStart(); resumed = true
        main.post(tick)
        if (hasPermission()) restartCamera()
        else { setStatus("카메라 접근을 허용하면 측정이 시작됩니다", false); permission.launch(Manifest.permission.CAMERA) }
    }
    override fun onStop() {
        resumed = false; main.removeCallbacks(tick)
        telemetry.event(sessionId.ifEmpty { "app" }, "activity_stopped")
        recorder.finish("activity_stopped")?.let { export(it) }
        restartCamera()
        super.onStop()
    }
    override fun onDestroy() {
        destroyed = true
        io.shutdown()
        if (!closing && engine == null) cameraWorker.shutdown()
        super.onDestroy()
    }
    private fun hasPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun restartCamera() {
        if (closing) return
        ready=false; captureButton.isEnabled=false; reportButton.isEnabled=false
        val old = engine; engine = null
        if (old != null) {
            closing = true; setStatus("카메라 세션 종료 중…", false)
            old.close {
                closing = false
                if (destroyed) cameraWorker.shutdown() else openCamera()
            }
        } else openCamera()
    }
    private fun openCamera() {
        if (!resumed || destroyed || paused || !hasPermission()) {
            if (paused) setStatus("PAUSED · ▶ 버튼으로 측정을 재개하세요", false)
            return
        }
        if (cameraId.isEmpty()) { setStatus("사용 가능한 카메라가 없습니다", false); return }
        sessionId = UUID.randomUUID().toString()
        val thisSession = sessionId
        zoomApplied = false
        rebuildZoomRow()
        setStatus("$engineName · 카메라 $cameraId 연결 중…", false)
        previewHost.removeAllViews()
        engine = if (engineName == "CameraX") {
            val view = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER }
            previewHost.addView(view, FrameLayout.LayoutParams(-1,-1))
            CameraXEngine(this, this, view, cameraId, sessionId, telemetry, cameraWorker) { text, ok ->
                if (thisSession == sessionId && resumed && !closing) setStatus(text,ok)
            }
        } else {
            val view = TextureView(this)
            previewHost.addView(view, FrameLayout.LayoutParams(-1,-1))
            Camera2Engine(this, view, cameraId, sessionId, telemetry) { text, ok ->
                if (thisSession == sessionId && resumed && !closing) setStatus(text,ok)
            }
        }
        try { engine?.start() } catch (e: Exception) { setStatus("시작 실패: ${e.message}",false) }
        styleEngines()
    }
    private fun setStatus(text: String, ok: Boolean) {
        statusText.text=text; statusText.setTextColor(if(ok) mint else muted)
        ready=ok; captureButton.isEnabled=ok; reportButton.isEnabled=ok && recorder.remainingNs()==null
        // Re-apply the chosen zoom once the new session is live so engine and camera switches keep the same framing.
        if (ok && !zoomApplied) { zoomApplied = true; if (zoomRatio != 1f) engine?.setZoom(zoomRatio) }
    }
    private fun buildUi() {
        val root=FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        previewHost=FrameLayout(this).apply { setBackgroundColor(Color.BLACK); contentDescription="실시간 카메라 프리뷰" }
        root.addView(previewHost,FrameLayout.LayoutParams(-1,-1))
        previewHost.setOnClickListener { if(diagnostics.visibility==View.VISIBLE) diagnostics.visibility=View.GONE }

        // Top: engine selector, camera picker, pause. Kept thin so the preview stays dominant.
        topBar=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(8),dp(12),dp(10)) }
        topBar.background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.argb(190,0,0,0),Color.TRANSPARENT))
        root.addView(topBar,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        val controls=row(); topBar.addView(controls)
        engineX=button("CameraX") { chooseEngine("CameraX") }
        engine2=button("Camera2") { chooseEngine("Camera2") }
        controls.addView(engineX,LinearLayout.LayoutParams(dp(88),dp(38)))
        controls.addView(engine2,LinearLayout.LayoutParams(dp(88),dp(38)).apply { marginStart=dp(6) })
        val ids=try { manager.cameraIdList.toList().sortedBy { manager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] != CameraCharacteristics.LENS_FACING_BACK } } catch (_:Exception) { emptyList() }
        if (cameraId !in ids) cameraId=ids.firstOrNull().orEmpty()
        val spinner=Spinner(this).apply { background=rounded(glass); setPadding(dp(10),0,dp(10),0) }
        val entries=ids.map { id ->
            val facing=manager.getCameraCharacteristics(id)[CameraCharacteristics.LENS_FACING]
            "Cam $id · ${when(facing) { CameraCharacteristics.LENS_FACING_BACK -> "Back"; CameraCharacteristics.LENS_FACING_FRONT -> "Front"; else -> "Ext" }}"
        }
        spinner.adapter=object:ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,entries) {
            override fun getView(position:Int,convertView:View?,parent:ViewGroup):View=(super.getView(position,convertView,parent) as TextView).apply { setTextColor(Color.WHITE); textSize=13f }
        }
        spinner.setSelection(ids.indexOf(cameraId).coerceAtLeast(0))
        spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {
                val chosen=ids.getOrNull(position) ?: return
                if(cameraId!=chosen) { recorder.finish("camera_changed")?.let { export(it) }; cameraId=chosen; zoomRatio=1f; restartCamera() }
            }
            override fun onNothingSelected(parent:AdapterView<*>?)=Unit
        }
        controls.addView(spinner,LinearLayout.LayoutParams(0,dp(38),1f).apply { marginStart=dp(6) })
        pauseButton=button(if(paused) "▶" else "❚❚") {
            paused=!paused; pauseButton.text=if(paused) "▶" else "❚❚"
            if(paused) recorder.finish("user_paused")?.let { export(it) }
            restartCamera()
        }.apply { background=rounded(glass); setTextColor(Color.WHITE) }
        controls.addView(pauseButton,LinearLayout.LayoutParams(dp(44),dp(38)).apply { marginStart=dp(6) })
        val checkButton=button("검사") {
            recorder.finish("check_started")?.let { export(it) }
            startActivity(android.content.Intent(this,dev.cameradoctor.check.CheckActivity::class.java))
        }.apply { background=rounded(glass); setTextColor(Color.WHITE) }
        controls.addView(checkButton,LinearLayout.LayoutParams(dp(52),dp(38)).apply { marginStart=dp(6) })
        // Consumer incident mode (11.4): the engine picker, camera spinner and check entry are expert chrome; hide them.
        if(consumer) controls.visibility=View.GONE
        statusText=label("INITIALIZING",11,mint,true); topBar.addView(statusText,lp(top=6))
        healthBanner=label("HEALTH · 대기",11,Color.WHITE,true).apply {
            // Fixed two-line height: the banner must not grow or shrink as headlines change length.
            setLines(2); gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(10),dp(6),dp(10),dp(6)); background=rounded(glass); contentDescription="카메라 상태 판정. 누르면 근거를 표시"
            setOnClickListener { diagnostics.visibility=View.VISIBLE }
        }
        topBar.addView(healthBanner,lp(top=6))

        // Bottom: zoom presets, live numbers, then the three round controls like a stock camera app.
        bottomBar=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_HORIZONTAL; setPadding(dp(16),dp(14),dp(16),dp(14)) }
        bottomBar.background=GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,intArrayOf(Color.argb(215,0,0,0),Color.TRANSPARENT))
        root.addView(bottomBar,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        // Live strip: interval sparkline with baseline guides, then the callback offsets of the latest matched frame.
        val stripBox=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=rounded(glass); setPadding(dp(10),dp(6),dp(10),dp(6)) }
        strip=StripView(this).apply { contentDescription="최근 10초 센서 프레임 간격. 실선은 기준, 점선은 1.5배 임계" }
        stripBox.addView(strip,lp(height=40))
        stripText=label("START —   PARTIAL —   BUFFER —",10,Color.WHITE).apply { typeface=Typeface.MONOSPACE }
        stripBox.addView(stripText,lp(top=2))
        bottomBar.addView(stripBox,lp())
        zoomRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER; background=pill(glass); setPadding(dp(6),dp(4),dp(6),dp(4)) }
        bottomBar.addView(zoomRow,LinearLayout.LayoutParams(-2,-2).apply { topMargin=dp(10) })
        metrics=label("FPS —  ·  ISO —  ·  Exp —\nLens —  ·  Zoom —",11,Color.WHITE).apply { gravity=Gravity.CENTER; typeface=Typeface.MONOSPACE }
        bottomBar.addView(metrics,lp(top=10))
        if(consumer) { zoomRow.visibility=View.GONE; metrics.visibility=View.GONE }
        val mainRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        bottomBar.addView(mainRow,lp(top=12))
        reportButton=button(incidentLabel()) {
            val id="incident_"+SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(Date())+"_"+UUID.randomUUID().toString().take(8)
            if(recorder.trigger(id)) { incidentAssessment=lastHealth; toast(if(consumer) "이후 5초를 더 기록한 뒤 분석합니다" else "5초 후 incident ZIP을 저장합니다") }
        }.apply { setTextColor(bg); textSize=if(consumer) 9f else 10f; setTypeface(typeface,Typeface.BOLD); background=circle(coral); contentDescription="문제 순간 기록: 직전 10초와 이후 5초를 저장" }
        captureButton=button("") { engine?.capture() }.apply { background=circle(Color.WHITE,ring=bg); contentDescription="셔터 측정" }
        val panelButton=button("진단") { diagnostics.visibility=if(diagnostics.visibility==View.VISIBLE) View.GONE else View.VISIBLE }
            .apply { background=circle(glass); setTextColor(Color.WHITE); textSize=11f }
        mainRow.addView(FrameLayout(this).apply { addView(reportButton,FrameLayout.LayoutParams(dp(60),dp(60),Gravity.CENTER)) },LinearLayout.LayoutParams(0,-2,1f))
        mainRow.addView(captureButton,LinearLayout.LayoutParams(dp(78),dp(78)))
        mainRow.addView(FrameLayout(this).apply { addView(panelButton,FrameLayout.LayoutParams(dp(60),dp(60),Gravity.CENTER)) },LinearLayout.LayoutParams(0,-2,1f))

        // Diagnostics panel: everything that used to be below the preview, now an overlay toggled from the bottom bar.
        diagnostics=ScrollView(this).apply { setBackgroundColor(bg); visibility=View.GONE; isFillViewport=true; isClickable=true }
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(12),dp(18),dp(24)) }
        diagnostics.addView(body)
        root.addView(diagnostics,FrameLayout.LayoutParams(-1,-1))
        val head=row().apply { gravity=Gravity.CENTER_VERTICAL }; body.addView(head)
        head.addView(LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; addView(label("CAMERA DOCTOR",22,Color.WHITE,true)); addView(label("EXPERT DIAGNOSTICS  /  0.2",10,muted)) },LinearLayout.LayoutParams(0,-2,1f))
        head.addView(button("닫기") { diagnostics.visibility=View.GONE },LinearLayout.LayoutParams(dp(72),dp(40)))
        // L1 verdict + L2 evidence first (11.1): what the 진단 button answers before any raw data.
        verdictCard=label("진단 준비 중…",15,Color.WHITE).apply { setPadding(dp(14),dp(16),dp(14),dp(16)); background=rounded(panel); setLineSpacing(0f,1.25f) }
        body.addView(verdictCard,lp(top=16))
        // Diagnosis Summary (12.2 item 1): rule, expected vs observed, per-layer state, cause layer. Always above the raw card.
        body.addView(label("DIAGNOSIS",12,muted,true),lp(top=18))
        summaryCard=label("baseline 수집 중…",12,Color.WHITE).apply { typeface=Typeface.MONOSPACE; setPadding(dp(12),dp(14),dp(12),dp(14)); background=rounded(panel) }
        body.addView(summaryCard,lp(top=10))
        body.addView(label("00   CAMERA HEALTH",12,muted,true),lp(top=18))
        body.addView(label("같은 세션의 직전 10초를 기준으로 판정. 앱 콜백 관측이며 HAL 원인은 판정하지 않음",10,muted),lp(top=4))
        healthCard=label("baseline 수집 중…",12,Color.WHITE).apply { typeface=Typeface.MONOSPACE; setPadding(dp(12),dp(14),dp(12),dp(14)); background=rounded(panel) }
        body.addView(healthCard,lp(top=10))
        body.addView(label("01   3A OSCILLOSCOPE",12,muted,true),lp(top=18))
        body.addView(label("최근 10초 · 3A 상태는 단계값, 연속값 그래프는 자동 스케일",10,muted),lp(top=4))
        scope=ScopeView(this).apply { background=rounded(panel); contentDescription="AE, AF, AWB 상태와 노출, ISO, 센서 프레임 간격 그래프" }
        body.addView(scope,lp(height=342,top=10))
        body.addView(label("02   FRAME CALLBACK TIMELINE",12,muted,true),lp(top=20))
        timelineView=dev.cameradoctor.ui.TimelineView(this).apply { background=rounded(panel); contentDescription="최근 프레임과 세션 평균의 START, PARTIAL, BUFFER 도착 시각 비교" }
        body.addView(timelineView,lp(height=96,top=10))
        timeline=label("프레임 콜백을 기다리는 중…",12,Color.WHITE).apply { typeface=Typeface.MONOSPACE; setPadding(dp(12),dp(14),dp(12),dp(14)); background=rounded(panel) }
        body.addView(timeline,lp(top=8))
        system=label("APP CPU —  ·  PSS —  ·  THERMAL —",11,muted)
        body.addView(system,lp(top=12))
        body.addView(label("03   FLIGHT RECORDER",12,muted,true),lp(top=20))
        body.addView(label("프리뷰 화면의 붉은 ${incidentLabel()} 버튼을 누르면 직전 10초 + 이후 5초를 저장합니다.",12,Color.WHITE),lp(top=8))
        recorderText=label("30s 순환 버퍼",11,muted); body.addView(recorderText,lp(top=8))
        val exports=row(); body.addView(exports,lp(top=8))
        shareButton=button("최근 ZIP 공유") { latestFile?.let { share(it) } }
        exports.addView(shareButton,LinearLayout.LayoutParams(0,dp(48),1f))
        exports.addView(button("기록 목록") { showIncidents() },LinearLayout.LayoutParams(0,dp(48),1f))
        body.addView(label("04   설정",12,muted,true),lp(top=20))
        val tools=row(); body.addView(tools,lp(top=8))
        tools.addView(button("권한 / 재시도") {
            if(hasPermission()) restartCamera()
            else if(shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) permission.launch(Manifest.permission.CAMERA)
            else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")))
        },LinearLayout.LayoutParams(0,dp(48),1f))
        tools.addView(button("측정 안내") { showNotes() },LinearLayout.LayoutParams(0,dp(48),1f))
        // 12.3: baseline reset is an expert-only action. Clears every endpoint's stored reference run.
        tools.addView(button("기준 초기화") {
            AlertDialog.Builder(this).setTitle("검사 기준 초기화").setMessage("저장된 기준(baseline)을 모두 지웁니다. 다음 60초 검사가 새 기준이 됩니다.")
                .setNegativeButton("취소",null).setPositiveButton("초기화") { _,_ -> dev.cameradoctor.baseline.BaselineStore(this).clear(); toast("기준을 지웠습니다") }.show()
        },LinearLayout.LayoutParams(0,dp(48),1f).apply { marginStart=dp(6) })
        body.addView(label("LOCAL RECORDING · NO IMAGE PIXELS SAVED",10,muted),lp(top=18))

        root.setOnApplyWindowInsetsListener { _,insets ->
            val (l,t,r,b)=if (Build.VERSION.SDK_INT >= 30) {
                val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                listOf(bars.left,bars.top,bars.right,bars.bottom)
            } else { @Suppress("DEPRECATION") listOf(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom) }
            topBar.setPadding(dp(12)+l,dp(8)+t,dp(12)+r,dp(10))
            bottomBar.setPadding(dp(16)+l,dp(14),dp(16)+r,dp(14)+b)
            body.setPadding(dp(18)+l,dp(12)+t,dp(18)+r,dp(24)+b)
            insets
        }
        root.requestApplyInsets()
        rebuildZoomRow()
        styleEngines()
    }
    private fun rebuildZoomRow() {
        zoomRow.removeAllViews()
        if (cameraId.isEmpty()) return
        val range=zoomRange(manager,cameraId)
        val presets=zoomPresets(range)
        if (zoomRatio !in presets) zoomRatio = presets.minByOrNull { kotlin.math.abs(it - zoomRatio) } ?: 1f
        for (ratio in presets) {
            val b=button(zoomLabel(ratio,ratio==zoomRatio)) { selectZoom(ratio) }
            b.textSize=12f
            zoomRow.addView(b,LinearLayout.LayoutParams(dp(42),dp(42)).apply { marginStart=dp(3); marginEnd=dp(3) })
        }
        styleZoom()
    }
    private fun zoomLabel(ratio:Float,selected:Boolean):String {
        val text=if(ratio<1f) "%.1f".format(Locale.US,ratio).removePrefix("0") else if(ratio==ratio.toInt().toFloat()) "${ratio.toInt()}" else "%.1f".format(Locale.US,ratio)
        return if(selected) "${text}x" else text
    }
    private fun selectZoom(ratio:Float) {
        zoomRatio=ratio
        engine?.setZoom(ratio)
        styleZoom()
    }
    private fun styleZoom() {
        val presets=zoomPresets(zoomRange(manager,cameraId))
        for (i in 0 until zoomRow.childCount) {
            val ratio=presets.getOrNull(i) ?: continue
            val b=zoomRow.getChildAt(i) as Button
            val selected=ratio==zoomRatio
            b.text=zoomLabel(ratio,selected)
            b.setTextColor(if(selected) bg else Color.WHITE)
            b.background=circle(if(selected) mint else Color.TRANSPARENT)
        }
    }
    private fun chooseEngine(name:String) {
        if(engineName==name) return
        recorder.finish("engine_changed")?.let { export(it) }
        engineName=name; styleEngines(); restartCamera()
    }
    private fun styleEngines() {
        for((button,name) in listOf(engineX to "CameraX",engine2 to "Camera2")) {
            button.setTextColor(if(engineName==name) bg else Color.WHITE)
            button.background=rounded(if(engineName==name) mint else glass)
        }
    }
    private fun updateReadings(events:List<Event>,frames:List<Event>,time:Long) {
        val frame=frames.lastOrNull()?.takeIf { time-it.atNs < 1_500_000_000L }
        fun num(key:String)= (frame?.values?.get(key) as? Number)?.toDouble()
        fun fmt(value:Double?,pattern:String)=value?.let { pattern.format(Locale.US,it) } ?: "—"
        val zoom=num("zoomRatio")?.let { "Zoom ${"%.2f".format(Locale.US,it)}x" } ?: "Zoom —"
        metrics.text="FPS ${fmt(num("resultFps"),"%.1f")}  ·  ISO ${num("iso")?.toInt() ?: "—"}  ·  Exp ${fmt(num("exposureNs")?.div(1e6),"%.2f ms")}\nLens ${fmt(num("focusDiopters"),"%.2f D")}  ·  $zoom"
        if(frame==null) { timeline.text="수신 중인 프레임 없음"; stripText.text="START —   PARTIAL —   BUFFER —"; return }
        val imageEvents=events.filter { it.session==sessionId && it.kind=="image_available" }
        val matched=frames.asReversed().firstOrNull { r -> r.sensorNs!=null && imageEvents.any { it.sensorNs==r.sensorNs } } ?: frame
        val start=events.lastOrNull { it.session==sessionId && it.kind=="capture_started" && it.frame==matched.frame }
        val image=imageEvents.lastOrNull { it.sensorNs==matched.sensorNs }
        fun offset(e:Event?)=if(e!=null && start!=null) "%+.2f ms".format(Locale.US,(e.atNs-start.atNs)/1e6) else "—"
        fun short(e:Event?)=if(e!=null && start!=null) "%+.1f".format(Locale.US,(e.atNs-start.atNs)/1e6) else "—"
        timeline.text="Frame #${matched.frame} · observed callbacks\nSTART    ${if(start!=null) "+0.00 ms" else "—"}\nPARTIAL  ${offset(matched)}\nBUFFER   ${offset(image)}\n센서 시각으로 연결 · HAL 처리 시간과 다름"
        fun ms(e:Event?)=if(e!=null && start!=null) (e.atNs-start.atNs)/1e6 else null
        timelineView.update(matched.frame,ms(matched),ms(image),(lastHealth?.values?.get("baselineGapMs") as? Number)?.toDouble(),(lastHealth?.values?.get("baselineBufferMs") as? Number)?.toDouble())
        stripText.text="#${matched.frame}  START +0  PARTIAL ${short(matched)}  BUFFER ${short(image)} ms"
    }
    private fun updateHealth(events:List<Event>,frames:List<Event>,time:Long) {
        val a=health.assess(events,sessionId,time)
        strip.update(frames,a.tRefMs,time)
        healthBanner.text=a.headline
        when(a.level) {
            HealthLevel.WARNING -> { healthBanner.background=rounded(coral); healthBanner.setTextColor(bg) }
            HealthLevel.WATCH -> { healthBanner.background=rounded(dev.cameradoctor.ui.Look.statusWarn); healthBanner.setTextColor(bg) }
            HealthLevel.OK -> { healthBanner.background=rounded(glass); healthBanner.setTextColor(mint) }
            HealthLevel.NO_DATA -> { healthBanner.background=rounded(glass); healthBanner.setTextColor(muted) }
        }
        healthCard.text=if(a.evidence.isEmpty()) a.headline else (listOf(a.headline)+a.evidence).joinToString("\n")
        summaryCard.text=diagnosisSummary(a)
        verdictCard.text=consumerSummary(a)
        val previous=lastHealth
        if(previous==null || previous.level!=a.level || previous.headline!=a.headline) {
            recorder.record(sessionId.ifEmpty { "app" },"health_assessment",values=a.values+mapOf("level" to a.level.name,"headline" to a.headline))
        }
        lastHealth=a
    }
    private fun sampleSystem() {
        val elapsed=SystemClock.elapsedRealtime(); val cpu=Process.getElapsedCpuTime()
        val percent=if(elapsed>previousSample) 100.0*(cpu-previousCpu)/(elapsed-previousSample) else 0.0
        previousSample=elapsed; previousCpu=cpu
        val memory=Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss/1024.0
        val thermal=if(Build.VERSION.SDK_INT>=29) getSystemService(PowerManager::class.java).currentThermalStatus else null
        val thermalName=thermal?.let { listOf("NONE","LIGHT","MODERATE","SEVERE","CRITICAL","EMERGENCY","SHUTDOWN").getOrNull(it) ?: "$it" } ?: "N/A"
        system.text="APP CPU ${"%.1f".format(Locale.US,percent)}%* · PSS ${"%.0f".format(Locale.US,memory)} MB · $thermalName"
        recorder.record("app","system_sample",values=mapOf("appCpuPercentOneCore" to percent,"pssMb" to memory,"thermalStatus" to thermal,"thermalName" to thermalName))
    }
    private fun export(incident:Incident) {
        exporting++
        val app=applicationContext; val sessions=telemetry.sessions.toMap()
        io.execute {
            try {
                val file=IncidentExporter(app).export(incident,sessions)
                main.post {
                    exporting--
                    if(!destroyed) { latestFile=file; shareButton.isEnabled=true; toast("저장 완료 · ${file.name}"); if(incident.finishReason=="completed") showIncidentSummary(file) }
                }
            } catch(e:Exception) { main.post { exporting--; if(!destroyed) toast("ZIP 저장 실패: ${e.message}") } }
        }
    }
    private fun incidentFiles()=File(filesDir,"incidents").listFiles()?.filter { it.extension=="zip" }?.sortedByDescending { it.lastModified() }.orEmpty()
    private fun showIncidents() {
        val files=incidentFiles()
        if(files.isEmpty()) { toast("저장된 incident가 없습니다"); return }
        AlertDialog.Builder(this).setTitle("Incident ZIP · ${files.size}개")
            .setItems(files.map { "${it.name}\n${it.length()/1024} KB" }.toTypedArray()) { _,index ->
                val file=files[index]
                AlertDialog.Builder(this).setTitle(file.name).setItems(arrayOf("공유 / Google Drive","다른 위치에 저장","삭제")) { _,action ->
                    when(action) {
                        0 -> share(file)
                        1 -> { saveFile=file; saveDocument.launch(file.name) }
                        2 -> AlertDialog.Builder(this).setMessage("${file.name}을 기기에서 삭제할까요?").setNegativeButton("취소",null).setPositiveButton("삭제") { _,_ ->
                            if(file.delete()) { latestFile=incidentFiles().firstOrNull(); shareButton.isEnabled=latestFile!=null; toast("삭제했습니다") }
                        }.show()
                    }
                }.show()
            }.setNegativeButton("닫기",null).show()
    }
    private fun share(file:File) {
        val uri=FileProvider.getUriForFile(this,"$packageName.files",file)
        val intent=Intent(Intent.ACTION_SEND).apply {
            type="application/zip"; putExtra(Intent.EXTRA_STREAM,uri)
            clipData=ClipData.newRawUri("incident",uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent,"Incident 공유 · Google Drive 선택"))
    }
    private fun incidentLabel()=if(consumer) "방금\n이상했어요" else "MARK\nINCIDENT"
    /** 12.2 item 1: the same diagnosis the consumer sees, with expected/observed and per-layer state for the expert. */
    private fun diagnosisSummary(a:Assessment):String {
        val d=a.diagnosis ?: return a.headline
        val v=a.values
        fun n(k:String)=(v[k] as? Number)?.toDouble()
        fun f(x:Double?)=x?.let { "%.1f".format(Locale.US,it) } ?: "—"
        val rule=d.rule
        val cadence=if(v["cadenceChanged"]==true) "CADENCE CHANGE" else "NORMAL"
        val callback=if(v["gapAnomaly"]==true) "ABNORMAL" else "NORMAL"
        val sensor=if(v["intervalAnomaly"]==true) "ABNORMAL" else "NORMAL"
        val threeA=if(v["threeAStable"]==true) "NORMAL" else "SEARCHING"
        val mark=if(rule=="normal") "●" else "⚠"
        val (expected,observed)=when(rule) {
            "callback_delay","pipeline_stall" -> "partial +${f(n("baselineGapMs"))} ms (baseline p50)" to "partial +${f(n("partialGapMs"))} ms"
            "sensor_stall" -> "interval ${f(n("tRefMs"))} ms (baseline p50)" to "interval ${f(n("intervalMs"))} ms"
            "cadence_change" -> "duration ${f(n("baselineFrameDurationMs"))} ms" to "duration ${f(n("recentFrameDurationMs"))} ms"
            else -> "interval ${f(n("tRefMs"))} ms" to "interval ${f(n("intervalMs"))} ms"
        }
        return "$mark $rule\n\nExpected   $expected\nObserved   $observed\n\n3A         $threeA\nCadence    $cadence\nSensor     $sensor\nCallback   $callback\n\ncause_layer  ${d.causeLayer.name.lowercase(Locale.US)}"
    }
    /** L1 verdict line + L2 evidence lines in consumer words (11.1). Shared by the panel verdict card and the incident dialog. */
    private fun consumerLines(a:Assessment?):Triple<String,String,List<String>> {
        val rule=a?.diagnosis?.rule ?: "insufficient_evidence"
        val v=a?.values.orEmpty()
        fun n(k:String)=(v[k] as? Number)?.toDouble()
        fun f(x:Double?)=x?.let { "%.0f ms".format(Locale.US,it) } ?: "—"
        val l1=dev.cameradoctor.diagnosis.DiagnosisRules.CONSUMER_TEXT[rule] ?: rule
        val l2=buildList {
            if(rule=="callback_delay"||rule=="pipeline_stall") add("프레임 응답  평소 ${f(n("baselineGapMs"))} → 지금 ${f(n("partialGapMs"))}")
            if(rule=="sensor_stall"||rule=="pipeline_stall") add("프레임 간격  평소 ${f(n("tRefMs"))} → 지금 ${f(n("intervalMs"))}")
            if(a!=null) { add("최근 10초 화면 끊김  ${(v["stallCount10s"] as? Number)?.toInt() ?: 0}회"); add("초점·노출  ${if(v["threeAStable"]==true) "정상" else "맞추는 중"}") }
        }
        val title=when { a==null -> "아직 판정할 데이터가 없습니다"; rule=="normal" -> "특별한 이상은 찾지 못했습니다"; a.level==HealthLevel.WATCH -> "지켜보는 중입니다"; else -> "문제를 발견했습니다" }
        return Triple(title,l1,l2)
    }
    private fun consumerSummary(a:Assessment):String {
        if(a.level==HealthLevel.NO_DATA) return "● 판정 준비 중\n${a.headline}"
        val (title,l1,l2)=consumerLines(a)
        val mark=when(a.level) { HealthLevel.WARNING -> "⚠"; HealthLevel.WATCH -> "◐"; else -> "●" }
        return "$mark $title\n$l1\n\n"+l2.joinToString("\n")
    }
    /** After MARK INCIDENT: L1 verdict and L2 evidence captured at the trigger (11.4). Consumer wording; details behind a button. */
    private fun showIncidentSummary(file:File) {
        if(destroyed || isFinishing) return
        val a=incidentAssessment; incidentAssessment=null
        val (title,l1,l2)=consumerLines(a)
        AlertDialog.Builder(this).setTitle(title)
            .setMessage(l1+"\n\n"+l2.joinToString("\n")+"\n\n앱 콜백 관측 결과이며 카메라 내부 원인은 단정하지 않습니다.")
            .setPositiveButton("상세 데이터") { _,_ -> diagnostics.visibility=View.VISIBLE }
            .setNeutralButton("공유") { _,_ -> share(file) }
            .setNegativeButton("닫기",null).show()
    }
    private fun showNotes() {
        AlertDialog.Builder(this).setTitle("측정 범위 / MVP")
            .setMessage("• Result FPS는 센서 타임스탬프 간격으로 계산합니다. 화면 표시 FPS가 아닙니다.\n\n• *앱 CPU 100%는 CPU 코어 하나의 사용량에 해당하며 100%를 넘을 수 있습니다. HAL 프로세스 CPU는 측정하지 않습니다.\n\n• 줌 버튼은 요청 배율입니다. 실제 적용 배율은 capture result의 CONTROL_ZOOM_RATIO로 ZIP에 기록되며, 논리 카메라의 물리 렌즈 전환은 HAL이 결정합니다.\n\n• CameraX와 Camera2의 실제 스트림 크기는 ZIP에 기록됩니다. 동일 조건 A/B 벤치마크는 후속 기능입니다.\n\n• 앱을 나가거나 카메라를 변경하면 진행 중인 incident를 partial 사유와 함께 저장합니다.\n\n• 이미지 픽셀, Perfetto, Simpleperf, AI 분석은 이 MVP에 포함하지 않았습니다.")
            .setPositiveButton("확인",null).show()
    }
    private fun label(text:String,size:Int,color:Int,bold:Boolean=false)=TextView(this).apply { this.text=text; textSize=size.toFloat(); setTextColor(color); if(bold) setTypeface(typeface,Typeface.BOLD) }
    private fun button(text:String,action:()->Unit)=Button(this).apply { this.text=text; isAllCaps=false; textSize=12f; setTextColor(mint); background=rounded(panel); setPadding(0,0,0,0); minWidth=0; minimumWidth=0; minHeight=0; minimumHeight=0; setOnClickListener { action() } }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
    private fun rounded(color:Int)=GradientDrawable().apply { setColor(color); cornerRadius=dp(10).toFloat() }
    private fun pill(color:Int)=GradientDrawable().apply { setColor(color); cornerRadius=dp(30).toFloat() }
    private fun circle(color:Int,ring:Int?=null)=GradientDrawable().apply { shape=GradientDrawable.OVAL; setColor(color); if(ring!=null) setStroke(dp(4),ring) }
    private fun lp(height:Int=-2,top:Int=0)=LinearLayout.LayoutParams(-1,if(height<0) height else dp(height)).apply { topMargin=dp(top) }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun toast(text:String)=Toast.makeText(this,text,Toast.LENGTH_LONG).show()
}
