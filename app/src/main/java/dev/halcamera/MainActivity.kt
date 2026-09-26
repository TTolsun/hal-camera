package dev.halcamera

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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import dev.halcamera.camera.*
import dev.halcamera.cli.LiveController
import dev.halcamera.telemetry.*
import dev.halcamera.ui.LiveReadout
import dev.halcamera.ui.DiagnosticsPanel
import dev.halcamera.ui.ExpandingZoomControl
import dev.halcamera.ui.LiveControlBar
import dev.halcamera.ui.LiveIndicator
import dev.halcamera.ui.RecentMediaButton
import dev.halcamera.ui.ShutterButton
import dev.halcamera.ui.IconButton
import dev.halcamera.ui.Look
import dev.halcamera.ui.showActionPopup
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
            override fun busy() = recordingVideo || stoppingRecording || pendingMediaAction != null || pendingPermissionAction != null || (engine as? MediaCapture)?.mediaBusy == true
            override fun prepare(camera: String) {
                showDiagnostics(false)
                cameraId = camera; engineName = "Camera2"; paused = false; zoomRatio = 1f
                resetControls(); updateCameraChoices(); restartCamera()
            }
            override fun capture(id: String, done: (Result<PhotoResult>) -> Unit) {
                val camera = engine as? MediaCapture
                if (camera == null) done(Result.failure(IllegalStateException("Media capture unavailable; switch to Camera2"))) else camera.capturePhoto(id, done)
                updateMediaControls()
            }
            override fun record(audio: Boolean, started: () -> Unit, done: (Result<android.net.Uri>) -> Unit) {
                videoMode = true
                val camera = engine as? MediaCapture
                if (camera == null) done(Result.failure(IllegalStateException("Media capture unavailable; switch to Camera2")))
                else camera.startRecording(audio, started, done)
                updateMediaControls()
            }
            override fun stopRecording() {
                stoppingRecording = true
                (engine as? MediaCapture)?.stopRecording()
                updateMediaControls()
            }
            override fun stopPreview(done: () -> Unit) {
                paused = true; ready = false
                val old = engine; engine = null
                closing = old != null
                val closed = {
                    closing = false
                    setStatus("일시정지 · 재개 버튼으로 측정을 시작하세요", false)
                    done()
                }
                if (old == null) closed() else old.close(closed)
            }
            override fun cts(command: dev.halcamera.cli.CliCommand) = handOver(command) {
                Intent(this@MainActivity, dev.halcamera.cts.suite.CtsSuiteRunActivity::class.java)
                    .putExtra(dev.halcamera.cts.suite.CtsSuiteRunActivity.EXTRA_KEYS, command.cases.orEmpty().toTypedArray())
            }
            /** Closes the Live camera first: the next screen must open a free camera, and close(done) is the only way to know. */
            private fun handOver(command: dev.halcamera.cli.CliCommand, intent: () -> Intent) {
                val old = engine; engine = null; closing = true; ready = false
                val open = {
                    closing = false
                    if (resumed && cli.active?.id == command.id) startActivity(intent().putExtra("cli_request_id", command.id))
                    else cli.fail(command.id, "APP_NOT_FOREGROUND", "App left foreground before ${command.command}")
                }
                if (old == null) open() else old.close { open() }
            }
            override fun stopPreparing() { paused = true; restartCamera() }
        })
    }
    companion object {
        /** 8.1: one word, because the button records a moment and no longer claims anything about it. */
        const val MARK_LABEL = "Mark"
    }
    private lateinit var timelineView: dev.halcamera.ui.TimelineView
    // Camera UI follows docs/design/APP-UI.md; shared dark surfaces and active states use ui/Look.
    private val bg = Look.cameraSurface
    private val panel = Look.cameraCard
    private val muted = dev.halcamera.ui.Look.onDarkMuted
    private val coral = dev.halcamera.ui.Look.statusFail
    private val glass = Look.cameraGlass
    private val main = Handler(Looper.getMainLooper())
    /** Buttons refuse taps while an ADB command drives the camera. */
    private val widgets by lazy { dev.halcamera.ui.CameraWidgets(this) { cli.active == null } }
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
    /** Sits over the frozen frame while the preview is paused. */
    private lateinit var pausedOverlay: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var diagnostics: ScrollView
    private lateinit var zoomControl: ExpandingZoomControl
    private lateinit var controlBar: LiveControlBar
    private lateinit var cameraNotice: TextView
    private var savedNoticeShown = false
    private val clearNotice = Runnable { savedNoticeShown = false; if (ready) cameraNotice.visibility = View.GONE }
    private lateinit var recentMedia: RecentMediaThumbnail
    private lateinit var liveIndicator: LiveIndicator
    private var lastPreviewFrameNs = 0L
    private var cameraXStreaming = false
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
    /** The same two controls as the capture row, kept in the panel header while the panel covers it. */
    private lateinit var panelRecordingTime: TextView
    private lateinit var panelStopButton: IconButton
    private lateinit var galleryButton: RecentMediaButton
    private lateinit var cameraShortcut: IconButton
    private var cameraIds = emptyList<String>()
    /** Logical id to endpoint, so [cameraLabel] can name the lens without re-reading CameraCharacteristics. */
    private var cameraEndpoints = emptyMap<String, CameraEndpoint>()
    private lateinit var toolsButton: Button
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
                val elapsed = "● REC  %02d:%02d".format(Locale.US, seconds / 60, seconds % 60)
                recordingTime.text = elapsed
                panelRecordingTime.text = elapsed
            }
            val events = recorder.snapshot(10_000_000_000L)
            val frames = events.filter { it.session == sessionId && it.kind == "capture_result" }
            val previewAt = if (engineName == "Camera2") lastPreviewFrameNs
                else if (cameraXStreaming) frames.lastOrNull()?.atNs ?: 0L else 0L
            liveIndicator.bind(resumed && !paused && !closing && engine != null &&
                previewAt > 0L && time - previewAt < 1_500_000_000L)
            // The only cursor left is the incident trigger. The other one marked the frames that produced a
            // WARNING, and there is no longer anything issuing one.
            val markers = events.filter { it.kind == "incident_trigger" }.map { Triple(it.atNs, "Mark", true) }
            scope.update(frames, time, markers)
            updateReadout(events, frames, time)
            updateReadings(events, frames, time)
            if (time-lastSystemNs >= 1_000_000_000L) { sampleSystem(); lastSystemNs=time }
            recorder.finish()?.let { export(it) }
            val remaining = recorder.remainingNs()
            reportButton.isEnabled = remaining == null && ready && !paused && cli.active == null
            updateMediaControls()
            reportButton.text = if (remaining != null) "저장까지 ${"%.1f".format(Locale.US, remaining/1e9)}s" else "이벤트 저장 · ZIP"
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
        main.removeCallbacks(clearNotice); savedNoticeShown = false
        pendingMediaAction = null
        pendingPermissionAction = null
        resumed = false; main.removeCallbacks(tick)
        liveIndicator.bind(false)
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
        liveIndicator.bind(false)
        lastPreviewFrameNs = 0L
        cameraXStreaming = false
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
        setStatus("$engineName · ${CameraLabel.short(cameraId)} 연결 중…", false)
        (previewHost.getChildAt(0) as? PreviewView)?.previewStreamState?.removeObservers(this)
        previewHost.removeAllViews()
        engine = if (engineName == "CameraX") {
            val view = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER }
            previewHost.addView(view, FrameLayout.LayoutParams(-1,-1))
            view.previewStreamState.observe(this) { state ->
                if (thisSession == sessionId && resumed && !closing) {
                    cameraXStreaming = state == PreviewView.StreamState.STREAMING
                    if (!cameraXStreaming) liveIndicator.bind(false)
                }
            }
            CameraXEngine(this, this, view, cameraId, sessionId, telemetry, cameraWorker) { text, ok ->
                if (thisSession == sessionId && resumed && !closing) setStatus(text,ok)
            }
        } else {
            val view = TextureView(this)
            previewHost.addView(view, FrameLayout.LayoutParams(-1,-1))
            Camera2Engine(this, view, cameraId, sessionId, telemetry, previewReady = {
                if (thisSession == sessionId && resumed && !closing) liveCli.previewReady()
            }, previewFrame = {
                if (thisSession == sessionId && resumed && !closing && !paused) lastPreviewFrameNs = nowNs()
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
        // A save notice stays for its 2.5 s even when the engine's "· LIVE" report follows it. Stopping a recording
        // rebuilds the preview session, and that report used to arrive right after the video notice and hide it.
        // Only that one routine report waits; a failure or any other notice still replaces the save notice.
        val saved = ok && text.contains("저장했습니다")
        if (!(savedNoticeShown && ok && text.endsWith("· LIVE"))) {
            main.removeCallbacks(clearNotice)
            savedNoticeShown = saved
            cameraNotice.text = text
            cameraNotice.visibility = if ((!ok && !recordingVideo) || text.contains("실패") || saved) View.VISIBLE else View.GONE
            if (saved) main.postDelayed(clearNotice, 2500)
        }
        ready=ok; reportButton.isEnabled=ok && recorder.remainingNs()==null
        if (ok || recordingVideo) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateMediaControls()
        // Re-apply the chosen zoom once the new session is live so engine and camera switches keep the same framing.
        if (ok && !zoomApplied) {
            zoomApplied = true; if (zoomRatio != 1f) engine?.setZoom(zoomRatio)
            // A pause or a return from another screen reopens the same camera, so its chips still apply.
            if (controlBar.controls != LiveControls()) (engine as? LiveTuning)?.setControls(controlBar.controls)
        }
        if (ok && engine is MediaCapture) pendingMediaAction?.also { pendingMediaAction = null; main.post { if (resumed && ready) it() } }
    }

    @Suppress("DEPRECATION")
    private fun withMediaPermissions(video: Boolean, action: () -> Unit) {
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT <= 28) required += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (video) required += Manifest.permission.RECORD_AUDIO
        val missing = required.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else { pendingPermissionAction = action; mediaPermissions.launch(missing.toTypedArray()) }
    }

    private fun runMediaCaptureAction(action: () -> Unit) {
        if (engine is MediaCapture) action() else {
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
        // A paused preview keeps its last frame, which looks exactly like a preview that has stopped updating
        // on its own. The scrim says which of the two it is, over the frame rather than above it in the top
        // bar, because that frame is what raises the question.
        pausedOverlay=label("일시정지됨\n재개 버튼을 누르면 측정을 다시 시작합니다",14,Look.onDark).apply {
            gravity=Gravity.CENTER
            setBackgroundColor(Color.argb(150,0,0,0))
            visibility=View.GONE
            setShadowLayer(dp(2).toFloat(),0f,0f,Color.BLACK)
        }
        root.addView(pausedOverlay,FrameLayout.LayoutParams(-1,-1))

        // Keep API selection and the live readout visible; detailed measurement tools live in the panel.
        topBar=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(8),dp(12),dp(10)) }
        topBar.background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.argb(140,0,0,0),Color.TRANSPARENT))
        root.addView(topBar,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        val controls=row().apply { gravity=Gravity.CENTER_VERTICAL }; topBar.addView(controls)
        cameraIds=try { manager.cameraIdList.toList().sortedBy { manager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] != CameraCharacteristics.LENS_FACING_BACK } } catch (_:Exception) { emptyList() }
        // Logical endpoints only: the picker lists what LIVE can open, and physical ids belong to PROBE.
        cameraEndpoints=try { CameraEndpointResolver(manager).resolve().filter { it.physicalCameraId==null }.associateBy { it.logicalCameraId } } catch (_:Exception) { emptyMap() }
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
        // Standalone tools stay in the menu; Mark remains on the live preview.
        toolsButton=button("도구") { showToolsMenu(toolsButton) }.apply { contentDescription="도구 메뉴: 사양 확인, 동작 검증, 성능 측정, 실행 기록, 작업실" }
        val panelButton=button("진단") { showDiagnostics(true) }.apply { contentDescription="진단 패널 열기" }
        listOf(engineButton,toolsButton,panelButton).forEach { it.background=cameraChrome(Color.TRANSPARENT); it.setTextColor(Color.WHITE); it.setPadding(dp(12),0,dp(12),0) }
        liveIndicator=LiveIndicator(this)
        val leadingSlot=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; gravity=Gravity.START
            addView(engineButton,LinearLayout.LayoutParams(-2,dp(48)))
            addView(liveIndicator,LinearLayout.LayoutParams(-2,dp(16)))
        }
        val trailingSlot=row().apply {
            gravity=Gravity.END or Gravity.CENTER_VERTICAL
            addView(toolsButton,LinearLayout.LayoutParams(-2,dp(48)))
            addView(panelButton,LinearLayout.LayoutParams(-2,dp(48)).apply { marginStart=dp(4) })
        }
        // Equal-width slots keep the expander at the screen centre, aligned with the engine button.
        controlBar=LiveControlBar(this,object : LiveControlBar.Host {
            override fun controlsChanged(controls: LiveControls) { (engine as? LiveTuning)?.setControls(controls) }
            override fun needsCamera2():Boolean {
                if(recordingVideo) { toast("촬영 설정을 바꾸려면 녹화를 마쳐 주세요"); return false }
                toast("촬영 설정을 위해 Camera2로 전환합니다"); chooseEngine("Camera2"); return true
            }
            override fun notice(text: String) = toast(text)
        })
        controls.gravity=Gravity.TOP
        controls.addView(leadingSlot,LinearLayout.LayoutParams(0,-2,1f))
        controls.addView(FrameLayout(this).apply {
            addView(controlBar.handle,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        },LinearLayout.LayoutParams(0,dp(48),1f))
        controls.addView(trailingSlot,LinearLayout.LayoutParams(0,dp(48),1f))
        topBar.addView(controlBar.view,lp(top=4))
        resetControls()
        cameraNotice=label("카메라 준비 중…",12,Look.onDark).apply {
            gravity=Gravity.CENTER
            accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        topBar.addView(cameraNotice,lp(top=4))

        // The zoom rail expands horizontally without moving the shutter or the readout.
        bottomBar=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_HORIZONTAL; setPadding(dp(16),dp(14),dp(16),dp(14)) }
        // A single soft scrim spans capture controls and Mark without a separate footer band.
        val captureChrome=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            background=GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,intArrayOf(Color.argb(180,0,0,0),Color.TRANSPARENT))
        }
        root.addView(captureChrome,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        captureChrome.addView(bottomBar,LinearLayout.LayoutParams(-1,-2))
        metrics=label("FPS — · ISO — · Exp —\nAE — · AF —",12,Look.onDark).apply {
            maxLines=2
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
                if(recordingVideo) stopRecording()
                else if(videoMode) {
                    withMediaPermissions(true) { runMediaCaptureAction { (engine as? MediaCapture)?.startRecording() } }
                } else withMediaPermissions(false) { runMediaCaptureAction { engine?.capture() } }
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

        val mainRow=row().apply {
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(16),dp(8),dp(16),dp(8))
            setBackgroundColor(Color.TRANSPARENT)
        }
        reportButton=button(MARK_LABEL) {
            val id="incident_"+SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(Date())+"_"+UUID.randomUUID().toString().take(8)
            // The reading is captured here, not when the dialog opens. The dialog is at least five seconds later
            // and after an asynchronous write, by which point lastReading has been replaced many times over and
            // may even belong to a different camera. What the dialog shows has to be the evidence for that ZIP.
            if(recorder.trigger(id)) { markedReadings[id]=lastReading; toast("5초 후 incident ZIP을 저장합니다") }
        }.apply { setTextColor(Color.WHITE); background=cameraChrome(Color.TRANSPARENT); contentDescription="Mark: 직전 10초와 이후 5초를 ZIP으로 저장" }
        reportButton.minHeight=dp(48); reportButton.minimumHeight=dp(48)
        reportButton.contentDescription="문제 시점 기록: 직전 10초와 이후 5초의 이벤트를 ZIP으로 저장"
        mainRow.addView(reportButton,LinearLayout.LayoutParams(0,-2,1f))
        reportButton.setShadowLayer(dp(2).toFloat(),0f,0f,Color.BLACK)
        captureChrome.addView(mainRow,LinearLayout.LayoutParams(-1,-2))

        val panelView=DiagnosticsPanel(this,widgets,cameraButton,pauseButton,MARK_LABEL,object : DiagnosticsPanel.Actions {
            override fun close() = showDiagnostics(false)
            override fun stopRecording() = this@MainActivity.stopRecording()
            override fun about() = dev.halcamera.ui.AboutSheet.show(this@MainActivity)
            override fun shareLatest() { latestFile?.let { share(it) } }
            override fun incidents() = showIncidents()
            override fun retryPermission() {
                if(hasPermission()) restartCamera()
                else if(shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) permission.launch(Manifest.permission.CAMERA)
                else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")))
            }
            override fun notes() = showNotes()
            override var cliEnabled: Boolean
                get() = cli.enabled
                set(value) = cli.setEnabled(value)
        })
        diagnostics=panelView.view; panelRecordingTime=panelView.recordingTime; panelStopButton=panelView.stopButton
        readoutCard=panelView.readoutCard; strip=panelView.strip; stripText=panelView.stripText; scope=panelView.scope
        timelineView=panelView.timelineView; timeline=panelView.timeline; system=panelView.system
        recorderText=panelView.recorderText; shareButton=panelView.shareButton
        val body=panelView.body
        root.addView(diagnostics,FrameLayout.LayoutParams(-1,0,Gravity.BOTTOM))
        // Keep the preview and incident action visible while inspecting live graphs.
        root.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ ->
            val footerHeight=mainRow.height
            val panelParams=diagnostics.layoutParams as FrameLayout.LayoutParams
            val panelHeight=((root.height-footerHeight)*0.6f).toInt()
            if(panelParams.height!=panelHeight || panelParams.bottomMargin!=footerHeight) {
                panelParams.height=panelHeight; panelParams.bottomMargin=footerHeight
                diagnostics.layoutParams=panelParams
            }
        }

        root.setOnApplyWindowInsetsListener { _,insets ->
            val (l,t,r,b)=if (Build.VERSION.SDK_INT >= 30) {
                val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                listOf(bars.left,bars.top,bars.right,bars.bottom)
            } else { @Suppress("DEPRECATION") listOf(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom) }
            topBar.setPadding(dp(12)+l,dp(8)+t,dp(12)+r,dp(10))
            bottomBar.setPadding(dp(16)+l,dp(14),dp(16)+r,dp(14))
            mainRow.setPadding(dp(16)+l,dp(8),dp(16)+r,dp(8)+b)
            body.setPadding(dp(18)+l,dp(12),dp(18)+r,dp(24))
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
                resetControls(); updateCameraChoices(); restartCamera()
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
    /**
     * "Camera · 0 (Wide · Rear)". The roles come from the shared enumeration so LIVE names a lens exactly as
     * BENCHMARK, PROBE and the run history do; an id the resolver did not reach still gets the short label.
     */
    private fun cameraLabel(id:String):String {
        if(id.isEmpty()) return "카메라 없음"
        return cameraEndpoints[id]?.let(CameraLabel::full) ?: CameraLabel.short(id)
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
        if (!resumed || paused || closing || engine == null) liveIndicator.bind(false)
        cli.setUiBusy(liveCli, recordingVideo || stoppingRecording || pendingMediaAction != null || pendingPermissionAction != null || (engine as? MediaCapture)?.mediaBusy == true)
        listOf(photoModeButton,videoModeButton).forEachIndexed { index, button ->
            val selected=(index==1)==videoMode
            button.isSelected=selected
            button.isEnabled=ready && !recordingVideo
            button.setTextColor(if(selected) Look.onDark else Look.onDarkMuted)
            button.setTypeface(null,if(selected) Typeface.BOLD else Typeface.NORMAL)
            button.contentDescription=if(index==0) "사진 모드, YUV와 JPEG 두 장 저장" else "동영상 모드, 소리 포함"
            ViewCompat.setStateDescription(button,if(selected) "선택됨" else null)
        }
        pausedOverlay.visibility=if(paused) View.VISIBLE else View.GONE
        modeControls.visibility=if(recordingVideo) View.INVISIBLE else View.VISIBLE
        recordingTime.visibility=if(recordingVideo) View.VISIBLE else View.GONE
        panelRecordingTime.visibility=recordingTime.visibility
        panelStopButton.visibility=recordingTime.visibility
        panelStopButton.isEnabled=recordingVideo && !stoppingRecording
        panelStopButton.alpha=if(panelStopButton.isEnabled) 1f else 0.4f
        mediaButton.setCaptureState(videoMode,recordingVideo)
        if(stoppingRecording) {
            mediaButton.contentDescription="동영상 저장 중"
            ViewCompat.setStateDescription(mediaButton,"저장 중")
        }
        mediaButton.isEnabled=(ready || recordingVideo) && !stoppingRecording
        engineButton.isEnabled=!recordingVideo
        cameraButton.isEnabled=!recordingVideo && cameraId.isNotEmpty()
        cameraShortcut.isEnabled=cameraButton.isEnabled
        // Zoom stays live while recording (#174): the engine changes the recording request in place.
        // The engine reports "REC" as not-ready, so a running recording counts as ready here, as for the shutter.
        zoomControl.isEnabled=(ready || recordingVideo) && !stoppingRecording
        controlBar.bind(engine is LiveTuning || (engine==null && engineName=="Camera2"),videoMode,(ready || recordingVideo) && !stoppingRecording && cli.active==null)
        pauseButton.isEnabled=!recordingVideo
        galleryButton.isEnabled=!recordingVideo
        toolsButton.isEnabled=!recordingVideo && !stoppingRecording && !closing
        if (cli.active != null) {
            listOf(mediaButton, engineButton, cameraButton, cameraShortcut, photoModeButton, videoModeButton, zoomControl, pauseButton, galleryButton, toolsButton, reportButton).forEach { it.isEnabled = false }
        }
        listOf(engineButton,cameraButton,cameraShortcut,photoModeButton,videoModeButton,mediaButton,pauseButton,galleryButton,toolsButton).forEach {
            it.alpha=if(it.isEnabled) 1f else 0.4f
        }
    }
    private fun chooseEngine(name:String) {
        if(engineName==name) return
        recorder.finish("engine_changed")?.let { export(it) }
        engineName=name; resetControls(); updateCameraChoices(); restartCamera()
    }
    /** Locks, EV and flash start over for every camera and engine; the new session opens with the defaults. */
    private fun resetControls()=controlBar.reset(if(cameraId.isEmpty()) LiveControlSupport.NONE else liveControlSupport(manager,cameraId))
    private fun updateReadings(events:List<Event>,frames:List<Event>,time:Long) {
        val frame=frames.lastOrNull()?.takeIf { time-it.atNs < 1_500_000_000L }
        fun num(key:String)= (frame?.values?.get(key) as? Number)?.toDouble()
        fun fmt(value:Double?,pattern:String)=value?.let { pattern.format(Locale.US,it) } ?: "—"
        // Two lines at most over the preview: the measurement, then the camera's state. Values the screen already shows
        // are left out (the zoom rail's ratio, the buttons' EV), and the extras join line 2 in priority order only while
        // they fit its width. With a flash mode on, the flash state leads, since no button can show it. An applied EV or
        // zoom that differs from the request appears only once the last ten results all differ, not for the few frames
        // the pipeline lags behind every change. Lens position and everything else stay in the incident ZIP.
        val recent=frames.takeLast(10)
        fun differs(key:String,want:Double,tolerance:Double)=recent.size==10 &&
            recent.all { e -> (e.values[key] as? Number)?.toDouble()?.let { kotlin.math.abs(it-want)>tolerance } == true }
        val controls=controlBar.controls
        val extras=listOfNotNull(
            LiveControlBar.afState(num("af")?.toInt()),
            LiveControlBar.evApplied(num("evApplied")?.toInt()?.takeIf { differs("evApplied",controls.evIndex.toDouble(),0.5) },controls,controlBar.support),
            num("zoomRatio")?.takeIf { differs("zoomRatio",zoomRatio.toDouble(),0.01*zoomRatio) }?.let { "Zoom ${"%.2f".format(Locale.US,it)}x applied" },
            frame?.values?.get("physicalId")?.let { "Phys $it" })
        val room=(metrics.width-metrics.paddingLeft-metrics.paddingRight).toFloat()
        var state=listOfNotNull(LiveControlBar.aeState(num("ae")?.toInt()),LiveControlBar.flashState(num("flashState")?.toInt(),controls)).joinToString(" · ")
        for(extra in extras) { val next="$state · $extra"; if(room>0f && metrics.paint.measureText(next)<=room) state=next else break }
        metrics.text="FPS ${fmt(num("resultFps"),"%.1f")} · ISO ${num("iso")?.toInt() ?: "—"} · Exp ${fmt(num("exposureNs")?.div(1e6),"%.2fms")}\n$state"
        if(frame==null) { timeline.text="수신 중인 프레임 없음"; stripText.text="Partial —   Buffer — ms"; return }
        val imageEvents=events.filter { it.session==sessionId && it.kind=="image_available" }
        val matched=frames.asReversed().firstOrNull { r -> r.sensorNs!=null && imageEvents.any { it.sensorNs==r.sensorNs } } ?: frame
        val start=events.lastOrNull { it.session==sessionId && it.kind=="capture_started" && it.frame==matched.frame }
        val image=imageEvents.lastOrNull { it.sensorNs==matched.sensorNs }
        fun offset(e:Event?)=if(e!=null && start!=null) "%+.2f ms".format(Locale.US,(e.atNs-start.atNs)/1e6) else "—"
        fun short(e:Event?)=if(e!=null && start!=null) "%+.1f".format(Locale.US,(e.atNs-start.atNs)/1e6) else "—"
        timeline.text="Frame #${matched.frame} · observed callbacks\nStart    ${if(start!=null) "+0.00 ms" else "—"}\nPartial  ${offset(matched)}\nBuffer   ${offset(image)}\n센서 시각으로 연결 · HAL 처리 시간과 다름"
        fun ms(e:Event?)=if(e!=null && start!=null) (e.atNs-start.atNs)/1e6 else null
        timelineView.update(matched.frame,ms(matched),ms(image),lastReading?.baselinePartialMs,lastReading?.baselineBufferMs)
        fun offset(value: Double?) = value?.let { "%+.1f".format(Locale.US,it) } ?: "—"
        stripText.text="Partial ${offset(ms(matched))}   Buffer ${offset(ms(image))} ms"
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
        system.text="App CPU ${"%.1f".format(Locale.US,percent)}% (1코어=100%)\nPSS ${"%.0f".format(Locale.US,memory)} MB · Thermal $thermalName"
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
                AlertDialog.Builder(this).setTitle(file.name).setItems(arrayOf("공유","다른 위치에 저장","삭제")) { _,action ->
                    when(action) {
                        0 -> share(file)
                        1 -> { saveFile=file; saveDocument.launch(file.name) }
                        2 -> AlertDialog.Builder(this).setMessage("${file.name}을 기기에서 삭제할까요?").setNegativeButton("취소",null).setPositiveButton("삭제") { _,_ ->
                            if(file.delete()) { latestFile=incidentFiles().firstOrNull(); shareButton.isEnabled=latestFile!=null; toast("삭제했습니다") }
                        }.show()
                    }
                }.setNegativeButton("취소",null).show()
            }.setNegativeButton("닫기",null).show()
    }
    private fun share(file:File) {
        val uri=FileProvider.getUriForFile(this,"$packageName.files",file)
        val intent=Intent(Intent.ACTION_SEND).apply {
            type="application/zip"; putExtra(Intent.EXTRA_STREAM,uri)
            clipData=ClipData.newRawUri("incident",uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent,"Incident 공유"))
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
            "Mark를 누른 시점의 값입니다.",
            "",
            // The dialog body is proportional, so padding with spaces never lined the columns up; one value per line
            // reads the same on every font.
            "interval: ${ms(marked.intervalMs)}",
            "  기준 p50: ${ms(marked.intervalRefMs)}",
            "partial: ${ms(marked.partialMs)}",
            "  기준 p50: ${ms(marked.baselinePartialMs)}",
            "stall (10s): ${marked.stalls}회"
        ).joinToString("\n")
        AlertDialog.Builder(this).setTitle(file.name)
            .setMessage(body)
            .setPositiveButton("공유") { _,_ -> share(file) }
            .setNegativeButton("닫기",null).show()
    }
    private fun showNotes() {
        AlertDialog.Builder(this).setTitle("측정 안내")
            .setMessage("• Result FPS는 센서 타임스탬프 간격으로 계산합니다. 화면 표시 FPS가 아닙니다.\n\n• 앱 CPU 100%는 CPU 코어 하나의 사용량에 해당하며 100%를 넘을 수 있습니다. HAL 프로세스 CPU는 측정하지 않습니다.\n\n• 줌 버튼은 요청 배율입니다. 실제 적용 배율은 capture result의 CONTROL_ZOOM_RATIO로 ZIP에 기록되며, 논리 카메라의 물리 렌즈 전환은 HAL이 결정합니다.\n\n• CameraX와 Camera2의 실제 스트림 크기는 ZIP에 기록됩니다. 동일 조건 A/B 벤치마크는 후속 기능입니다.\n\n• 앱을 나가거나 카메라를 변경하면 진행 중인 incident를 partial 사유와 함께 저장합니다.\n\n• 사진·동영상 모드를 선택한 뒤 실행 버튼을 누르면 갤러리에 저장합니다. 사진은 YUV·JPEG 두 장이며 동영상에는 소리가 포함됩니다. 녹화 중에도 줌은 바꿀 수 있지만 엔진·카메라·모드는 바꿀 수 없습니다.\n\n• 위쪽의 플래시·AF·AE·EV 버튼과 줌 레일은 요청값입니다. 아래 두 줄은 capture result에서 읽으며, 화면에 이미 보이는 값은 생략합니다. 둘째 줄에는 AE·AF 상태 뒤에 플래시 상태(플래시를 켰을 때), 요청과 다른 EV·줌, 물리 렌즈 순서로 폭이 허락하는 만큼만 붙습니다. 렌즈 위치 같은 나머지 값은 ZIP에 있습니다. AE 잠금 중에도 EV는 적용됩니다. 버튼은 Camera2에서만 동작하고 카메라나 엔진을 바꾸면 초기화됩니다. Incident ZIP과 벤치마크에는 이미지 픽셀을 저장하지 않습니다.")
            .setPositiveButton("확인",null).show()
    }
    private fun showToolsMenu(anchor: View) {
        if (closing) return
        // PROBE, CTS, BENCHMARK is the order a developer meets the tools in: read what the HAL claims, check whether
        // it passes, then measure how long it takes. The guide's tabs carry the same order.
        showActionPopup(anchor, listOf("Probe · 사양 확인", "CTS · 동작 검증", "Benchmark · 성능 측정", "실행 기록 · 비교", "작업실로 이동")) { index ->
            when (index) {
                // PROBE reads CameraCharacteristics only and never opens a camera, so it starts without waiting for
                // close(done); onStop closes the LIVE camera as it does for any screen change.
                0 -> startActivity(Intent(this, CameraProbeActivity::class.java).putExtra(CameraProbeActivity.EXTRA_CAMERA_ID, cameraId))
                1 -> openAfterClose("cts_started") { Intent(this, dev.halcamera.cts.CtsEntryActivity::class.java) }
                2 -> openAfterClose("benchmark_started") {
                    Intent(this, dev.halcamera.benchmark.BenchmarkActivity::class.java)
                        .putExtra(dev.halcamera.benchmark.BenchmarkActivity.EXTRA_ENGINE, engineName)
                        .putExtra(dev.halcamera.benchmark.BenchmarkActivity.EXTRA_CAMERA_ID, cameraId)
                }
                3 -> startActivity(Intent(this, dev.halcamera.benchmark.HistoryActivity::class.java))
                4 -> openAfterClose("workbench_opened") {
                    Intent(this, WorkbenchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            }
        }
    }
    /**
     * CTS and Benchmark open their own camera, so the live session must be closed and its close(done) received
     * before the next screen starts. Same sequence as the CLI CTS path; onStop's restartCamera() sees
     * `closing` and stays out of the way, and onStart reopens the camera when the user comes back.
     */
    private fun openAfterClose(reason: String, intent: () -> Intent) {
        if (closing) return
        recorder.finish(reason)?.let { export(it) }
        showDiagnostics(false)
        val old = engine; engine = null; closing = true; ready = false
        setStatus("카메라 세션 종료 중…", false)
        updateMediaControls()
        val open = {
            closing = false
            if (resumed && !destroyed) startActivity(intent()) else updateMediaControls()
        }
        if (old == null) open() else old.close { open() }
    }
    /** Ends the recording and says so on both stop buttons, whichever one the user reached. */
    private fun stopRecording() {
        if (!recordingVideo || stoppingRecording) return
        stoppingRecording = true
        recordingTime.text = "저장 중…"
        panelRecordingTime.text = "저장 중…"
        updateMediaControls()
        (engine as? MediaCapture)?.stopRecording()
    }
    private fun showDiagnostics(show: Boolean) {
        if (show) zoomControl.collapse(animate = false)
        diagnostics.visibility = if (show) View.VISIBLE else View.GONE
        panelBack.isEnabled = show
        bottomBar.visibility = if (show) View.GONE else View.VISIBLE
    }
    private fun label(text:String,size:Int,color:Int,bold:Boolean=false)=widgets.label(text,size,color,bold)
    private fun button(text:String,action:()->Unit)=widgets.button(text,action)
    private fun cameraChrome(fill:Int=glass,outlined:Boolean=false)=widgets.chrome(fill,outlined)
    private fun row()=widgets.row()
    private fun rounded(color:Int)=widgets.rounded(color)
    private fun lp(height:Int=-2,top:Int=0)=widgets.lp(height,top)
    private fun dp(value:Int)=widgets.dp(value)
    private fun toast(text:String)=Toast.makeText(this,text,Toast.LENGTH_LONG).show()
}
