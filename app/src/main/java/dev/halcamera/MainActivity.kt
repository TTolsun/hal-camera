package dev.halcamera

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import dev.halcamera.camera.*
import dev.halcamera.telemetry.*
import dev.halcamera.ui.LiveReadout
import dev.halcamera.ui.ExpandingZoomControl
import dev.halcamera.ui.RecentMediaButton
import dev.halcamera.ui.ShutterButton
import dev.halcamera.ui.IconButton
import dev.halcamera.ui.Look
import dev.halcamera.ui.showSelectionPopup
import dev.halcamera.ui.LiveReading
import dev.halcamera.ui.ScopeView
import dev.halcamera.ui.StripView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/**
 * LIVE (docs/PLAN-BenchMarker-v0.3.md 8.1), and the launcher since M3.
 *
 * The screen shows what the camera is doing right now and nothing more. It used to also diagnose: a health banner
 * with OK / WATCH / WARNING, a verdict card, a DIAGNOSIS card naming a rule and a cause layer, and a consumer mode
 * that said all of it in plain words. Every one of those is gone. The judgement the app can defend is a run
 * compared against a baseline the developer chose, and that is what BENCHMARK does.
 */
class MainActivity : ComponentActivity() {
    private val cli by lazy { dev.halcamera.cli.CommandCoordinator.get(this) }
    private val liveCli by lazy {
        LiveController(cli, object : LiveController.Driver {
            override fun busy() = recordingVideo || stoppingRecording || pendingMediaAction != null || pendingPermissionAction != null || (engine as? Camera2Engine)?.mediaBusy == true
            override fun prepare(camera: String) {
                showDiagnostics(false)
                cameraId = camera; engineName = "Camera2"; paused = false; zoomRatio = 1f
                updateCameraChoices(); restartCamera()
            }
            override fun capture(id: String, done: (Result<PhotoResult>) -> Unit) {
                val camera = engine as? Camera2Engine
                if (camera == null) done(Result.failure(IllegalStateException("Camera2 unavailable"))) else camera.capturePhoto(id, done)
                updateMediaControls()
            }
            override fun benchmark(command: dev.halcamera.cli.CliCommand) {
                val old = engine; engine = null; closing = true; ready = false
                val open = {
                    closing = false
                    if (resumed && cli.active?.id == command.id) {
                        startActivity(Intent(this@MainActivity, dev.halcamera.benchmark.BenchmarkActivity::class.java)
                            .putExtra("camera_id", command.camera).putExtra("engine", "Camera2").putExtra("cli_request_id", command.id))
                    } else cli.fail(command.id, "APP_NOT_FOREGROUND", "App left foreground before benchmark")
                }
                if (old == null) open() else old.close { open() }
            }
            override fun stopPreparing() { paused = true; restartCamera() }
        })
    }
    companion object {
        /** 8.1: one word, because the button records a moment and no longer claims anything about it. */
        const val MARK_LABEL = "MARK"
    }
    private lateinit var timelineView: dev.halcamera.ui.TimelineView
    // Camera UI follows docs/design/APP-UI.md; shared dark surfaces and active states use ui/Look.
    private val bg = Look.cameraSurface
    private val panel = Look.cameraCard
    private val muted = dev.halcamera.ui.Look.onDarkMuted
    private val coral = dev.halcamera.ui.Look.statusFail
    private val glass = Look.cameraGlass
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
    private lateinit var zoomControl: ExpandingZoomControl
    private lateinit var cameraNotice: TextView
    private val clearNotice = Runnable { if (ready) cameraNotice.visibility = View.GONE }
    private lateinit var recentMedia: RecentMediaThumbnail
    private lateinit var statusText: TextView
    private lateinit var strip: StripView
    private lateinit var stripText: TextView
    private lateinit var readoutCard: TextView
    private val readout = LiveReadout()
    private var lastReading: LiveReading? = null
    /** The reading at each MARK, held by incident id until that incident's ZIP is written. Main thread only. */
    private val markedReadings = mutableMapOf<String, LiveReading?>()
    private lateinit var metrics: TextView
    private lateinit var timeline: TextView
    private lateinit var system: TextView
    private lateinit var scope: ScopeView
    private lateinit var reportButton: Button
    private lateinit var mediaButton: ShutterButton
    private lateinit var photoModeButton: Button
    private lateinit var videoModeButton: Button
    private lateinit var modeControls: LinearLayout
    private lateinit var recordingTime: TextView
    private lateinit var galleryButton: RecentMediaButton
    private lateinit var cameraShortcut: IconButton
    private var cameraIds = emptyList<String>()
    private lateinit var benchButton: Button
    private var videoMode = false
    private var recordingVideo = false
    private var stoppingRecording = false
    private var recordingStartedAt = 0L
    private var pendingMediaAction: (() -> Unit)? = null
    private var pendingPermissionAction: (() -> Unit)? = null
    private val mediaPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val action = pendingPermissionAction.also { pendingPermissionAction = null }
        if (grants.values.all { it }) action?.invoke() else toast("저장하려면 요청한 권한을 허용해 주세요")
    }
    private lateinit var engineButton: Button
    private lateinit var cameraButton: Button
    private lateinit var pauseButton: IconButton
    private lateinit var recorderText: TextView
    private lateinit var shareButton: Button
    private val panelBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showDiagnostics(false)
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) restartCamera() else setStatus("카메라 권한이 필요합니다 · 측정 패널의 권한 버튼으로 재시도", false)
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
            if (recordingVideo && !stoppingRecording) {
                val seconds = (SystemClock.elapsedRealtime() - recordingStartedAt) / 1000
                recordingTime.text = "● REC  %02d:%02d".format(Locale.US, seconds / 60, seconds % 60)
            }
            val events = recorder.snapshot(10_000_000_000L)
            val frames = events.filter { it.session == sessionId && it.kind == "capture_result" }
            // The only cursor left is the incident trigger. The other one marked the frames that produced a
            // WARNING, and there is no longer anything issuing one.
            val markers = events.filter { it.kind == "incident_trigger" }.map { Triple(it.atNs, "MARK", true) }
            scope.update(frames, time, markers)
            updateReadout(events, frames, time)
            updateReadings(events, frames, time)
            if (time-lastSystemNs >= 1_000_000_000L) { sampleSystem(); lastSystemNs=time }
            recorder.finish()?.let { export(it) }
            val remaining = recorder.remainingNs()
            reportButton.isEnabled = remaining == null && ready && !paused && cli.active == null
            updateMediaControls()
            reportButton.text = if (remaining != null) "저장까지 ${"%.1f".format(Locale.US, remaining/1e9)}s" else "$MARK_LABEL · ZIP 저장"
            val span = events.firstOrNull()?.let { (time-it.atNs)/1e9 } ?: 0.0
            recorderText.text = if (exporting > 0) "ZIP 저장 중…" else if (remaining != null) "기록 중 · 이후 ${"%.1f".format(Locale.US, remaining/1e9)}초 남음" else "30s 순환 버퍼  ·  ${"%.1f".format(Locale.US, span.coerceAtMost(10.0))}s / 10s 사전 기록 준비"
            main.postDelayed(this, 100)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        engineName = savedInstanceState?.getString("engine") ?: "Camera2"
        cameraId = savedInstanceState?.getString("camera") ?: ""
        paused = savedInstanceState?.getBoolean("paused") ?: false
        zoomRatio = savedInstanceState?.getFloat("zoom") ?: 1f
        videoMode = savedInstanceState?.getBoolean("videoMode") ?: false
        manager = getSystemService(CameraManager::class.java)
        buildUi()
        recentMedia = RecentMediaThumbnail(this) { bitmap, video -> galleryButton.setThumbnail(bitmap, video) }
        onBackPressedDispatcher.addCallback(this, panelBack)
        latestFile = incidentFiles().firstOrNull()
        shareButton.isEnabled = latestFile != null
        recorder.record("app", "clock_anchor", values = mapOf("wallTimeMs" to System.currentTimeMillis(), "uptimeMs" to SystemClock.uptimeMillis()))
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("engine", engineName); outState.putString("camera", cameraId); outState.putBoolean("paused", paused); outState.putFloat("zoom", zoomRatio)
        outState.putBoolean("videoMode", videoMode)
        super.onSaveInstanceState(outState)
    }
    override fun onStart() {
        super.onStart(); resumed = true
        cli.attach(liveCli)
        recentMedia.start()
        main.post(tick)
        if (hasPermission()) restartCamera()
        else { setStatus("카메라 접근을 허용하면 측정이 시작됩니다", false); permission.launch(Manifest.permission.CAMERA) }
    }
    override fun onStop() {
        cli.detach(liveCli)
        zoomControl.collapse(animate = false)
        recentMedia.stop()
        main.removeCallbacks(clearNotice)
        pendingMediaAction = null
        pendingPermissionAction = null
        resumed = false; main.removeCallbacks(tick)
        telemetry.event(sessionId.ifEmpty { "app" }, "activity_stopped")
        recorder.finish("activity_stopped")?.let { export(it) }
        restartCamera()
        super.onStop()
    }
    override fun onDestroy() {
        destroyed = true
        recentMedia.close()
        io.shutdown()
        if (!closing && engine == null) cameraWorker.shutdown()
        super.onDestroy()
    }
    private fun hasPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun restartCamera() {
        zoomControl.collapse(animate = false)
        if (closing) return
        ready=false; mediaButton.isEnabled=false; reportButton.isEnabled=false
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
            if (paused) setStatus("일시정지 · 재개 버튼으로 측정을 시작하세요", false)
            return
        }
        if (cameraId.isEmpty()) { setStatus("사용 가능한 카메라가 없습니다", false); return }
        sessionId = UUID.randomUUID().toString()
        val thisSession = sessionId
        zoomApplied = false
        updateCameraChoices()
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
            Camera2Engine(this, view, cameraId, sessionId, telemetry, previewReady = {
                if (thisSession == sessionId && resumed && !closing) liveCli.previewReady()
            }, recordingState = { recording ->
                if (thisSession == sessionId) {
                    recordingVideo = recording
                    if (!recording) stoppingRecording = false
                    if (recording) {
                        recordingStartedAt = SystemClock.elapsedRealtime()
                        recordingTime.text = "● REC  00:00"
                    }
                    updateMediaControls()
                    window.apply { if (recording || ready) addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
            }) { text, ok ->
                if (thisSession == sessionId && resumed && !closing) setStatus(text,ok)
            }
        }
        try { engine?.start() } catch (e: Exception) { setStatus("시작 실패: ${e.message}",false) }
        updateCameraChoices()
    }
    private fun setStatus(text: String, ok: Boolean) {
        statusText.text="$engineName · ${if(recordingVideo) "REC" else if(ok) "LIVE" else "대기"}"
        statusText.setTextColor(Look.onDarkMuted)
        main.removeCallbacks(clearNotice)
        cameraNotice.text = text
        cameraNotice.visibility = if ((!ok && !recordingVideo) || text.contains("실패") || text.contains("저장했습니다")) View.VISIBLE else View.GONE
        if (ok && text.contains("저장했습니다")) main.postDelayed(clearNotice, 2500)
        ready=ok; reportButton.isEnabled=ok && recorder.remainingNs()==null
        if (ok || recordingVideo) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateMediaControls()
        // Re-apply the chosen zoom once the new session is live so engine and camera switches keep the same framing.
        if (ok && !zoomApplied) { zoomApplied = true; if (zoomRatio != 1f) engine?.setZoom(zoomRatio) }
        if (ok && engine is Camera2Engine) pendingMediaAction?.also { pendingMediaAction = null; main.post { if (resumed && ready) it() } }
    }

    @Suppress("DEPRECATION")
    private fun withMediaPermissions(video: Boolean, action: () -> Unit) {
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT <= 28) required += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (video) required += Manifest.permission.RECORD_AUDIO
        val missing = required.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else { pendingPermissionAction = action; mediaPermissions.launch(missing.toTypedArray()) }
    }

    private fun runCamera2Action(action: () -> Unit) {
        if (engine is Camera2Engine) action() else {
            pendingMediaAction = action
            toast("촬영과 녹화를 위해 Camera2로 전환합니다")
            chooseEngine("Camera2")
        }
    }
    private fun buildUi() {
        val root=FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        previewHost=FrameLayout(this).apply { setBackgroundColor(Color.BLACK); contentDescription="실시간 카메라 프리뷰" }
        root.addView(previewHost,FrameLayout.LayoutParams(-1,-1))

        // Keep API selection and the live readout visible; detailed measurement tools live in the panel.
        topBar=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(8),dp(12),dp(10)) }
        topBar.background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.argb(190,0,0,0),Color.TRANSPARENT))
        root.addView(topBar,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        val controls=row().apply { gravity=Gravity.CENTER_VERTICAL }; topBar.addView(controls)
        cameraIds=try { manager.cameraIdList.toList().sortedBy { manager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] != CameraCharacteristics.LENS_FACING_BACK } } catch (_:Exception) { emptyList() }
        if (cameraId !in cameraIds) cameraId=cameraIds.firstOrNull().orEmpty()
        engineButton=button("") {
            pendingMediaAction=null; pendingPermissionAction=null
            chooseEngine(if(engineName=="Camera2") "CameraX" else "Camera2")
        }
        cameraButton=button("") { selectCamera(cameraButton) }
        pauseButton=IconButton(this,if(paused) R.drawable.ic_action_play else R.drawable.ic_action_pause,if(paused) "프리뷰 재개" else "프리뷰 일시정지") {
            if (cli.active != null) return@IconButton
            paused=!paused
            pauseButton.setIcon(if(paused) R.drawable.ic_action_play else R.drawable.ic_action_pause,if(paused) "프리뷰 재개" else "프리뷰 일시정지")
            if(paused) { pendingMediaAction=null; pendingPermissionAction=null; recorder.finish("user_paused")?.let { export(it) } }
            restartCamera()
        }
        val panelButton=button("Benchmark") { showDiagnostics(true) }
        engineButton.apply { background=cameraChrome(Color.TRANSPARENT); setTextColor(Color.WHITE) }
        panelButton.apply { background=cameraChrome(Color.TRANSPARENT); setTextColor(Color.WHITE); setPadding(dp(12),0,dp(12),0) }
        statusText=label("카메라 준비 중…",12,Look.onDarkMuted).apply {
            gravity=Gravity.CENTER
            maxLines=2
        }
        controls.addView(engineButton,LinearLayout.LayoutParams(0,dp(48),1f))
        controls.addView(statusText,LinearLayout.LayoutParams(0,dp(48),1f))
        controls.addView(panelButton,LinearLayout.LayoutParams(0,dp(48),1f))
        cameraNotice=label("카메라 준비 중…",12,Look.onDark).apply {
            gravity=Gravity.CENTER
            accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        topBar.addView(cameraNotice,lp(top=4))

        // The zoom rail expands horizontally without moving the shutter or the readout.
        bottomBar=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_HORIZONTAL; setPadding(dp(16),dp(14),dp(16),dp(14)) }
        bottomBar.background=GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,intArrayOf(Color.argb(215,0,0,0),Color.TRANSPARENT))
        root.addView(bottomBar,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        metrics=label("FPS —  ·  ISO —  ·  Exp —\nLens —  ·  Zoom —",12,Look.onDark).apply {
            gravity=Gravity.CENTER
            typeface=Look.mono
            setShadowLayer(dp(2).toFloat(),0f,0f,Color.BLACK)
        }
        bottomBar.addView(metrics,lp())
        zoomControl=ExpandingZoomControl(this) { ratio ->
            if (cli.active != null) return@ExpandingZoomControl
            zoomRatio=ratio; engine?.setZoom(ratio)
        }
        val zoomViewport=object : HorizontalScrollView(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) zoomControl.setTouchInProgress(true)
                val handled = super.dispatchTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL ||
                    (event.actionMasked == MotionEvent.ACTION_DOWN && !handled)) {
                    zoomControl.setTouchInProgress(false)
                }
                return handled
            }
        }.apply {
            isHorizontalScrollBarEnabled=false
            overScrollMode=View.OVER_SCROLL_NEVER
            addView(zoomControl,FrameLayout.LayoutParams(-2,dp(52)))
        }
        bottomBar.addView(zoomViewport,LinearLayout.LayoutParams(-2,dp(52)).apply { topMargin=dp(10) })
        val captureRow=row().apply { gravity=Gravity.CENTER_VERTICAL }
        bottomBar.addView(captureRow,lp(top=16))
        galleryButton=RecentMediaButton(this) {
            if (cli.active != null) return@RecentMediaButton
            withMediaPermissions(false) { startActivity(Intent(this, GalleryActivity::class.java)) }
        }
        val gallerySlot=FrameLayout(this).apply { addView(galleryButton,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER)) }
        captureRow.addView(gallerySlot,LinearLayout.LayoutParams(0,dp(72),1f))
        mediaButton=ShutterButton(this).apply {
            setOnClickListener {
                if (cli.active != null) return@setOnClickListener
                if(recordingVideo) {
                    stoppingRecording=true
                    recordingTime.text="저장 중…"
                    updateMediaControls()
                    (engine as? Camera2Engine)?.stopRecording()
                } else if(videoMode) {
                    withMediaPermissions(true) { runCamera2Action { (engine as? Camera2Engine)?.startRecording() } }
                } else withMediaPermissions(false) { runCamera2Action { engine?.capture() } }
            }
        }
        captureRow.addView(mediaButton,LinearLayout.LayoutParams(dp(72),dp(72)).apply { marginStart=dp(12); marginEnd=dp(12) })
        cameraShortcut=IconButton(this,R.drawable.ic_camera_select,"카메라 선택",filled=true) { selectCamera(cameraShortcut) }
        val cameraSlot=FrameLayout(this).apply { addView(cameraShortcut,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER)) }
        captureRow.addView(cameraSlot,LinearLayout.LayoutParams(0,dp(72),1f))

        val modeRow=FrameLayout(this)
        bottomBar.addView(modeRow,lp(height=48,top=16))
        modeControls=row().apply { gravity=Gravity.CENTER }
        photoModeButton=button("사진") { selectMode(false) }
        videoModeButton=button("동영상") { selectMode(true) }
        listOf(photoModeButton,videoModeButton).forEach {
            it.background=cameraChrome(Color.TRANSPARENT)
            it.textSize=14f
            modeControls.addView(it,LinearLayout.LayoutParams(dp(96),dp(48)))
        }
        modeRow.addView(modeControls,FrameLayout.LayoutParams(-2,-1,Gravity.CENTER))
        recordingTime=label("● REC  00:00",14,coral,true).apply { gravity=Gravity.CENTER; typeface=Look.mono; visibility=View.GONE }
        modeRow.addView(recordingTime,FrameLayout.LayoutParams(-1,-1))

        val mainRow=row().apply { gravity=Gravity.CENTER_VERTICAL }
        reportButton=button(MARK_LABEL) {
            val id="incident_"+SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(Date())+"_"+UUID.randomUUID().toString().take(8)
            // The reading is captured here, not when the dialog opens. The dialog is at least five seconds later
            // and after an asynchronous write, by which point lastReading has been replaced many times over and
            // may even belong to a different camera. What the dialog shows has to be the evidence for that ZIP.
            if(recorder.trigger(id)) { markedReadings[id]=lastReading; toast("5초 후 incident ZIP을 저장합니다") }
        }.apply { setTextColor(Color.WHITE); background=cameraChrome(Color.TRANSPARENT); contentDescription="MARK: 직전 10초와 이후 5초를 ZIP으로 저장" }
        benchButton=button("벤치마크") {
            recorder.finish("benchmark_started")?.let { export(it) }
            startActivity(android.content.Intent(this,dev.halcamera.benchmark.BenchmarkActivity::class.java).apply {
                putExtra(dev.halcamera.benchmark.BenchmarkActivity.EXTRA_ENGINE,engineName)
                putExtra(dev.halcamera.benchmark.BenchmarkActivity.EXTRA_CAMERA_ID,cameraId)
            })
        }.apply { background=cameraChrome(Color.TRANSPARENT); setTextColor(Color.WHITE); contentDescription="이 카메라로 벤치마크 실행" }
        listOf(reportButton,benchButton).forEach { it.minHeight=dp(48); it.minimumHeight=dp(48) }
        mainRow.addView(reportButton,LinearLayout.LayoutParams(0,-2,1f))
        mainRow.addView(benchButton,LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(8) })

        // Detailed tools and graphs use flat, outlined cards; core readings stay on the preview.
        diagnostics=ScrollView(this).apply { setBackgroundColor(bg); visibility=View.GONE; isFillViewport=true; isClickable=true }
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(12),dp(18),dp(24)) }
        diagnostics.addView(body)
        root.addView(diagnostics,FrameLayout.LayoutParams(-1,-1))
        val head=row().apply { gravity=Gravity.CENTER_VERTICAL }; body.addView(head)
        head.addView(label("Benchmark",22,Color.WHITE,true),LinearLayout.LayoutParams(0,-2,1f))
        head.addView(IconButton(this,R.drawable.ic_action_close,"Benchmark 패널 닫기") { showDiagnostics(false) },LinearLayout.LayoutParams(dp(48),dp(48)))
        val diagnosticControls=row()
        diagnosticControls.addView(cameraButton,LinearLayout.LayoutParams(0,dp(48),1f))
        diagnosticControls.addView(pauseButton,LinearLayout.LayoutParams(dp(48),dp(48)).apply { marginStart=dp(8) })
        body.addView(diagnosticControls,lp(top=12))
        body.addView(mainRow,lp(top=12))

        @Suppress("UseSwitchCompatOrMaterialCode")
        val cliSwitch = Switch(this).apply {
            text = "ADB CLI 허용"; textSize = 14f; setTextColor(Look.onDark)
            minHeight = dp(48); isChecked = cli.enabled
            setOnCheckedChangeListener { _, checked -> cli.setEnabled(checked) }
        }
        body.addView(cliSwitch, lp(top=12))
        body.addView(label("ADB 연결을 승인한 PC에서 촬영과 벤치마크를 실행할 수 있습니다.",12,muted),lp(top=4))
        // 8.1: raw numbers only. The DIAGNOSIS card that used to lead this panel named a rule and a cause layer
        // from a two-second window, which the app could not actually establish; BENCHMARK answers that properly.
        body.addView(label("LIVE READOUT",14,muted,true),lp(top=18))
        body.addView(label("같은 세션의 직전 프레임에서 읽은 값. 기준 p50은 최근 창을 제외한 나머지 프레임의 중앙값",10,muted),lp(top=4))
        readoutCard=label("프레임을 기다리는 중…",12,Color.WHITE).apply { typeface=dev.halcamera.ui.Look.mono; setPadding(dp(12),dp(14),dp(12),dp(14)); background=rounded(panel) }
        body.addView(readoutCard,lp(top=10))
        val stripBox=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=rounded(panel); setPadding(dp(12),dp(12),dp(12),dp(12)) }
        stripBox.addView(label("FRAME INTERVAL · 최근 10초",12,muted,true),lp())
        strip=StripView(this).apply { contentDescription="최근 10초 센서 프레임 간격. 실선은 기준, 점선은 1.5배 임계" }
        stripBox.addView(strip,lp(height=40,top=8))
        stripText=label("PARTIAL —   BUFFER — ms",12,muted).apply { gravity=Gravity.CENTER; typeface=Look.mono }
        stripBox.addView(stripText,lp(top=8))
        body.addView(stripBox,lp(top=10))
        body.addView(label("3A OSCILLOSCOPE",14,muted,true),lp(top=18))
        body.addView(label("최근 10초 · 3A 상태는 단계값, 연속값 그래프는 자동 스케일",10,muted),lp(top=4))
        scope=ScopeView(this).apply { background=rounded(panel); contentDescription="AE, AF, AWB 상태와 노출, ISO, 센서 프레임 간격 그래프" }
        body.addView(scope,lp(height=342,top=10))
        body.addView(label("FRAME CALLBACK TIMELINE",14,muted,true),lp(top=20))
        timelineView=dev.halcamera.ui.TimelineView(this).apply { background=rounded(panel); contentDescription="최근 프레임과 세션 평균의 START, PARTIAL, BUFFER 도착 시각 비교" }
        body.addView(timelineView,lp(height=96,top=10))
        timeline=label("프레임 콜백을 기다리는 중…",12,Color.WHITE).apply { typeface=dev.halcamera.ui.Look.mono; setPadding(dp(12),dp(14),dp(12),dp(14)); background=rounded(panel) }
        body.addView(timeline,lp(top=8))
        system=label("APP CPU —  ·  PSS —  ·  THERMAL —",11,muted)
        body.addView(system,lp(top=12))
        body.addView(label("INCIDENT ZIP",14,muted,true),lp(top=20))
        body.addView(label("$MARK_LABEL: 직전 10초 + 이후 5초의 이벤트를 저장합니다.",12,Color.WHITE),lp(top=8))
        recorderText=label("30s 순환 버퍼",11,muted); body.addView(recorderText,lp(top=8))
        val exports=row(); body.addView(exports,lp(top=8))
        shareButton=button("최근 ZIP 공유") { latestFile?.let { share(it) } }
        exports.addView(shareButton,LinearLayout.LayoutParams(0,dp(48),1f))
        exports.addView(button("기록 목록") { showIncidents() },LinearLayout.LayoutParams(0,dp(48),1f))
        val tools=row(); body.addView(tools,lp(top=8))
        tools.addView(button("권한 / 재시도") {
            if(hasPermission()) restartCamera()
            else if(shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) permission.launch(Manifest.permission.CAMERA)
            else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")))
        },LinearLayout.LayoutParams(0,dp(48),1f))
        tools.addView(button("측정 안내") { showNotes() },LinearLayout.LayoutParams(0,dp(48),1f))
        // The baseline reset that used to sit here cleared the Auto Check store. The benchmark baseline is a
        // pointer to one run and is cleared from the result screen, where the run it points at is on the screen.
        body.addView(label("사진 · 동영상: DCIM/HALCamera\nIncident ZIP에는 이미지 픽셀이 포함되지 않습니다",10,muted),lp(top=18))

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
        updateCameraChoices()
        updateMediaControls()
    }
    private fun selectChoice(anchor:View,items:List<String>,selected:Int,onSelect:(Int)->Unit) {
        showSelectionPopup(anchor,items,selected) { index ->
            if(!recordingVideo && resumed && cli.active == null) onSelect(index)
        }
    }
    private fun selectCamera(anchor: View) {
        if (cli.active != null) return
        if (recordingVideo) return
        selectChoice(anchor,cameraIds.map(::cameraLabel),cameraIds.indexOf(cameraId)) { index ->
            val chosen=cameraIds[index]
            if (cameraId!=chosen) {
                pendingMediaAction=null; pendingPermissionAction=null
                recorder.finish("camera_changed")?.let { export(it) }
                cameraId=chosen; zoomRatio=1f
                updateCameraChoices(); restartCamera()
            }
        }
    }
    private fun selectMode(video: Boolean) {
        if (cli.active != null) return
        if (recordingVideo || !ready || videoMode==video) return
        pendingMediaAction=null; pendingPermissionAction=null
        videoMode=video
        updateMediaControls()
    }
    private fun cameraLabel(id:String):String {
        if(id.isEmpty()) return "카메라 없음"
        val facing=manager.getCameraCharacteristics(id)[CameraCharacteristics.LENS_FACING]
        return "${when(facing) { CameraCharacteristics.LENS_FACING_BACK -> "후면"; CameraCharacteristics.LENS_FACING_FRONT -> "전면"; else -> "외부" }} · $id"
    }
    private fun updateCameraChoices() {
        if(cameraId.isNotEmpty()) {
            val presets=zoomPresets(zoomRange(manager,cameraId))
            if(zoomRatio !in presets) zoomRatio=presets.minByOrNull { kotlin.math.abs(it-zoomRatio) } ?: 1f
        }
        engineButton.text=engineName
        engineButton.contentDescription="현재 $engineName, 누르면 ${if(engineName=="Camera2") "CameraX" else "Camera2"}로 전환"
        cameraButton.text="${cameraLabel(cameraId)} ▾"
        cameraButton.contentDescription="카메라 선택, 현재 ${cameraLabel(cameraId)}"
        cameraShortcut.contentDescription="카메라 선택 목록 열기, 현재 ${cameraLabel(cameraId)}"
        cameraShortcut.tooltipText=cameraShortcut.contentDescription
        zoomControl.setChoices(if(cameraId.isEmpty()) listOf(1f) else zoomPresets(zoomRange(manager,cameraId)),zoomRatio)
    }
    private fun updateMediaControls() {
        cli.setUiBusy(liveCli, recordingVideo || stoppingRecording || pendingMediaAction != null || pendingPermissionAction != null || (engine as? Camera2Engine)?.mediaBusy == true)
        listOf(photoModeButton,videoModeButton).forEachIndexed { index, button ->
            val selected=(index==1)==videoMode
            button.isSelected=selected
            button.isEnabled=ready && !recordingVideo
            button.setTextColor(if(selected) Look.onDark else Look.onDarkMuted)
            button.setTypeface(null,if(selected) Typeface.BOLD else Typeface.NORMAL)
            button.contentDescription=if(index==0) "사진 모드, YUV와 JPEG 두 장 저장" else "동영상 모드, 소리 포함"
            ViewCompat.setStateDescription(button,if(selected) "선택됨" else null)
        }
        modeControls.visibility=if(recordingVideo) View.INVISIBLE else View.VISIBLE
        recordingTime.visibility=if(recordingVideo) View.VISIBLE else View.GONE
        mediaButton.setCaptureState(videoMode,recordingVideo)
        if(stoppingRecording) {
            mediaButton.contentDescription="동영상 저장 중"
            ViewCompat.setStateDescription(mediaButton,"저장 중")
        }
        mediaButton.isEnabled=(ready || recordingVideo) && !stoppingRecording
        engineButton.isEnabled=!recordingVideo
        cameraButton.isEnabled=!recordingVideo && cameraId.isNotEmpty()
        cameraShortcut.isEnabled=cameraButton.isEnabled
        zoomControl.isEnabled=ready && !recordingVideo
        pauseButton.isEnabled=!recordingVideo
        galleryButton.isEnabled=!recordingVideo
        benchButton.isEnabled=!recordingVideo
        if (cli.active != null) {
            listOf(mediaButton, engineButton, cameraButton, cameraShortcut, photoModeButton, videoModeButton, zoomControl, pauseButton, galleryButton, benchButton, reportButton).forEach { it.isEnabled = false }
        }
        listOf(engineButton,cameraButton,cameraShortcut,photoModeButton,videoModeButton,mediaButton,pauseButton,galleryButton,benchButton).forEach {
            it.alpha=if(it.isEnabled) 1f else 0.4f
        }
    }
    private fun chooseEngine(name:String) {
        if(engineName==name) return
        recorder.finish("engine_changed")?.let { export(it) }
        engineName=name; updateCameraChoices(); restartCamera()
    }
    private fun updateReadings(events:List<Event>,frames:List<Event>,time:Long) {
        val frame=frames.lastOrNull()?.takeIf { time-it.atNs < 1_500_000_000L }
        fun num(key:String)= (frame?.values?.get(key) as? Number)?.toDouble()
        fun fmt(value:Double?,pattern:String)=value?.let { pattern.format(Locale.US,it) } ?: "—"
        val zoom=num("zoomRatio")?.let { "Zoom ${"%.2f".format(Locale.US,it)}x" } ?: "Zoom —"
        metrics.text="FPS ${fmt(num("resultFps"),"%.1f")} · ISO ${num("iso")?.toInt() ?: "—"} · Exp ${fmt(num("exposureNs")?.div(1e6),"%.2fms")}\nLens ${fmt(num("focusDiopters"),"%.2fD")} · $zoom"
        if(frame==null) { timeline.text="수신 중인 프레임 없음"; stripText.text="PARTIAL —   BUFFER — ms"; return }
        val imageEvents=events.filter { it.session==sessionId && it.kind=="image_available" }
        val matched=frames.asReversed().firstOrNull { r -> r.sensorNs!=null && imageEvents.any { it.sensorNs==r.sensorNs } } ?: frame
        val start=events.lastOrNull { it.session==sessionId && it.kind=="capture_started" && it.frame==matched.frame }
        val image=imageEvents.lastOrNull { it.sensorNs==matched.sensorNs }
        fun offset(e:Event?)=if(e!=null && start!=null) "%+.2f ms".format(Locale.US,(e.atNs-start.atNs)/1e6) else "—"
        fun short(e:Event?)=if(e!=null && start!=null) "%+.1f".format(Locale.US,(e.atNs-start.atNs)/1e6) else "—"
        timeline.text="Frame #${matched.frame} · observed callbacks\nSTART    ${if(start!=null) "+0.00 ms" else "—"}\nPARTIAL  ${offset(matched)}\nBUFFER   ${offset(image)}\n센서 시각으로 연결 · HAL 처리 시간과 다름"
        fun ms(e:Event?)=if(e!=null && start!=null) (e.atNs-start.atNs)/1e6 else null
        timelineView.update(matched.frame,ms(matched),ms(image),lastReading?.baselinePartialMs,lastReading?.baselineBufferMs)
        fun offset(value: Double?) = value?.let { "%+.1f".format(Locale.US,it) } ?: "—"
        stripText.text="PARTIAL ${offset(ms(matched))}   BUFFER ${offset(ms(image))} ms"
    }
    /**
     * The live numbers, read once per tick and nothing more. The old version of this also recorded a
     * `health_assessment` event on every change of verdict; the flight recorder now carries only what was
     * observed, which is the only thing a ZIP opened months later can still be checked against.
     */
    private fun updateReadout(events:List<Event>,frames:List<Event>,time:Long) {
        val r=readout.read(events,sessionId,time)
        strip.update(frames,r.intervalRefMs,time)
        val note=when {
            !r.hasCurrentFrame -> "수신 중인 프레임 없음"
            !r.hasReference -> "기준 수집 중 (${r.baselineFrames}프레임)"
            else -> null
        }
        readoutCard.text=listOfNotNull(note,LiveReadout.panelText(r)).joinToString("\n")
        lastReading=r
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
                    val marked=markedReadings.remove(incident.id)
                    if(!destroyed) { latestFile=file; shareButton.isEnabled=true; toast("저장 완료 · ${file.name}"); if(incident.finishReason=="completed") showSaved(file,marked) }
                }
            } catch(e:Exception) { main.post { exporting--; markedReadings.remove(incident.id); if(!destroyed) toast("ZIP 저장 실패: ${e.message}") } }
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
    /**
     * 8.1: the dialog after a MARK keeps raw values only. It used to open with a verdict sentence and evidence
     * lines in consumer words, which claimed more about the recording than the app had measured.
     */
    private fun showSaved(file:File,marked:LiveReading?) {
        if(destroyed || isFinishing) return
        fun ms(v:Double?)=v?.let { String.format(Locale.US,"%.1f ms",it) } ?: "—"
        val body=if(marked==null) "직전 10초와 이후 5초를 저장했습니다." else listOf(
            "직전 10초와 이후 5초를 저장했습니다.",
            "MARK을 누른 시점의 값입니다.",
            "",
            "interval        ${ms(marked.intervalMs)}   (기준 p50 ${ms(marked.intervalRefMs)})",
            "partial         ${ms(marked.partialMs)}   (기준 p50 ${ms(marked.baselinePartialMs)})",
            "stall (10s)     ${marked.stalls}회"
        ).joinToString("\n")
        AlertDialog.Builder(this).setTitle(file.name)
            .setMessage(body)
            .setPositiveButton("공유") { _,_ -> share(file) }
            .setNegativeButton("닫기",null).show()
    }
    private fun showNotes() {
        AlertDialog.Builder(this).setTitle("측정 안내")
            .setMessage("• Result FPS는 센서 타임스탬프 간격으로 계산합니다. 화면 표시 FPS가 아닙니다.\n\n• *앱 CPU 100%는 CPU 코어 하나의 사용량에 해당하며 100%를 넘을 수 있습니다. HAL 프로세스 CPU는 측정하지 않습니다.\n\n• 줌 버튼은 요청 배율입니다. 실제 적용 배율은 capture result의 CONTROL_ZOOM_RATIO로 ZIP에 기록되며, 논리 카메라의 물리 렌즈 전환은 HAL이 결정합니다.\n\n• CameraX와 Camera2의 실제 스트림 크기는 ZIP에 기록됩니다. 동일 조건 A/B 벤치마크는 후속 기능입니다.\n\n• 앱을 나가거나 카메라를 변경하면 진행 중인 incident를 partial 사유와 함께 저장합니다.\n\n• 사진·동영상 모드를 선택한 뒤 실행 버튼을 누르면 갤러리에 저장합니다. 사진은 YUV·JPEG 두 장이며 동영상에는 소리가 포함됩니다. 녹화 중에는 엔진·카메라·줌·모드 변경을 할 수 없습니다. Incident ZIP과 벤치마크에는 이미지 픽셀을 저장하지 않습니다.")
            .setPositiveButton("확인",null).show()
    }
    private fun showDiagnostics(show: Boolean) {
        if (show) zoomControl.collapse(animate = false)
        diagnostics.visibility = if (show) View.VISIBLE else View.GONE
        panelBack.isEnabled = show
        topBar.importantForAccessibility = if (show) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        bottomBar.importantForAccessibility = topBar.importantForAccessibility
    }
    private fun label(text:String,size:Int,color:Int,bold:Boolean=false)=TextView(this).apply { this.text=text; textSize=size.coerceAtLeast(12).toFloat(); setTextColor(color); if(bold) setTypeface(typeface,Typeface.BOLD) }
    private fun button(text:String,action:()->Unit)=Button(this).apply { this.text=text; isAllCaps=false; textSize=12f; setTextColor(Look.onDark); background=cameraChrome(panel,true); backgroundTintList=null; stateListAnimator=null; setPadding(0,0,0,0); minWidth=0; minimumWidth=0; minHeight=0; minimumHeight=0; setOnClickListener { if (cli.active == null) action() } }
    private fun cameraChrome(fill:Int=glass,outlined:Boolean=false)=RippleDrawable(ColorStateList.valueOf(0x40FFFFFF),GradientDrawable().apply { setColor(fill); cornerRadius=dp(4).toFloat(); if(outlined) setStroke(dp(1),Look.cameraOutline) },rounded(Color.WHITE))
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
    private fun rounded(color:Int)=GradientDrawable().apply { setColor(color); cornerRadius=dp(4).toFloat(); setStroke(dp(1),Look.cameraOutline) }
    private fun lp(height:Int=-2,top:Int=0)=LinearLayout.LayoutParams(-1,if(height<0) height else dp(height)).apply { topMargin=dp(top) }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun toast(text:String)=Toast.makeText(this,text,Toast.LENGTH_LONG).show()
}
