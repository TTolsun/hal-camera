package dev.halcamera

import android.Manifest
import android.app.AlertDialog
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
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import dev.halcamera.camera.*
import dev.halcamera.cli.LiveController
import dev.halcamera.telemetry.*
import dev.halcamera.ui.*
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
            override fun busy() = recordingVideo || stoppingRecording || pendingPermissionAction != null || (engine as? MediaCapture)?.mediaBusy == true
            override fun prepare(command: dev.halcamera.cli.CliCommand, ready: () -> Unit) {
                // Capability work can initialize CameraX; keep it off the main thread.
                io.execute {
                    val selected = runCatching {
                        val id = requireNotNull(command.camera)
                        if (command.streams == null) null else {
                            var support = liveStreamSupport(getSystemService(CameraManager::class.java).getCameraCharacteristics(id))
                            if (command.engine == "CameraX") support = cameraXStreamSupport(this@MainActivity, id, support)
                            command.streams.resolve(support)
                        }
                    }
                    main.post {
                        if (cli.active?.id != command.id || !resumed) return@post
                        selected.fold({ settings ->
                            if (command.command == "capture" && settings?.canCapture == false) {
                                cli.fail(command.id, "PREFLIGHT_FAILED", "Capture requires YUV or JPEG output")
                                return@fold
                            }
                            showCallbacks(false)
                            cameraId = requireNotNull(command.camera); engineName = command.engine ?: "Camera2"
                            paused = false; zoomRatio = 1f
                            if (settings == null) streamSettings.remove(streamKey()) else streamSettings[streamKey()] = settings
                            resetControls(); updateCameraChoices(); restartCamera(); ready()
                        }, { cli.fail(command.id, (it as? dev.halcamera.cli.CliFailure)?.code ?: "PREFLIGHT_FAILED", it.message ?: "Invalid stream settings") })
                    }
                }
            }
            override fun streamInfo(): Map<String, Any?> =
                (telemetry.sessions[sessionId]?.get("negotiatedStreams") as? Map<*, *>)?.entries
                    ?.associate { it.key.toString() to it.value }.orEmpty()
            override fun photoLabels(): List<String> {
                val settings = streamSettings[streamKey()]
                return listOfNotNull("YUV".takeIf { settings == null || settings.yuv != null },
                    "JPEG".takeIf { settings == null || settings.jpeg != null })
            }
            override fun capture(id: String, done: (Result<PhotoResult>) -> Unit) {
                val camera = engine as? MediaCapture
                if (camera == null) done(Result.failure(IllegalStateException("Media capture unavailable; camera not ready"))) else camera.capturePhoto(id, done)
                updateMediaControls()
            }
            override fun record(audio: Boolean, started: () -> Unit, done: (Result<android.net.Uri>) -> Unit) {
                videoMode = true
                val camera = engine as? MediaCapture
                if (camera == null) done(Result.failure(IllegalStateException("Media capture unavailable; camera not ready")))
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
                    setStatus("프리뷰 중지 · Lab에서 카메라를 다시 연결하세요", false)
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
    private val streamSettings = mutableMapOf<String, LiveStreamSettings>()
    private val goodStreams = mutableMapOf<String, LiveStreamSettings?>()
    private val streamState = mutableMapOf<String, String>()
    private val lastStreamFps = mutableMapOf<String, String>()
    private var ready = false
    private var zoomRatio = 1f
    private var zoomApplied = false
    private var saveFile: File? = null
    private lateinit var manager: CameraManager
    private lateinit var previewHost: FrameLayout
    /** Sits over the frozen frame while the preview is paused. */
    private lateinit var pausedOverlay: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var graphButton: Button
    private lateinit var callbackGraph: ResultCallbackGraph
    private lateinit var zoomControl: ExpandingZoomControl
    private lateinit var controlBar: LiveControlBar
    private lateinit var cameraNotice: TextView
    private var savedNoticeShown = false
    private val clearNotice = Runnable { savedNoticeShown = false; if (ready || recordingVideo) cameraNotice.visibility = View.GONE }
    private lateinit var recentMedia: RecentMediaThumbnail
    private lateinit var liveIndicator: LiveIndicator
    private var lastPreviewFrameNs = 0L
    private var cameraXStreaming = false
    private lateinit var metrics: TextView
    private lateinit var readings: LiveReadings
    private val incidents by lazy {
        IncidentActions(this, io, main, object : IncidentActions.Host {
            override val sessions get() = telemetry.sessions.toMap()
            override val destroyed get() = this@MainActivity.destroyed
            override fun latestChanged(file: File?) = Unit
            override fun saveAs(file: File) { saveFile = file; saveDocument.launch(file.name) }
        })
    }

    private lateinit var reportButton: Button
    private lateinit var mediaButton: ShutterButton
    private lateinit var photoModeButton: Button
    private lateinit var videoModeButton: Button
    private lateinit var modeControls: LinearLayout
    private lateinit var recordingTime: TextView
    private lateinit var galleryButton: RecentMediaButton
    private lateinit var cameraShortcut: IconButton
    private var cameraIds = emptyList<String>()
    /** Logical id to endpoint, so [cameraLabel] can name the lens without re-reading CameraCharacteristics. */
    private var cameraEndpoints = emptyMap<String, CameraEndpoint>()
    private lateinit var labButton: Button
    private var videoMode = false
    private var recordingVideo = false
    private var stoppingRecording = false
    private var recordingStartedAt = 0L
    private var pendingPermissionAction: (() -> Unit)? = null
    private val mediaPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val action = pendingPermissionAction.also { pendingPermissionAction = null }
        if (grants.values.all { it }) action?.invoke() else toast("저장하려면 요청한 권한을 허용해 주세요")
    }
    private lateinit var engineButton: Button
    private val graphBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showCallbacks(false)
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) restartCamera() else setStatus("카메라 권한이 필요합니다 · Lab에서 카메라를 다시 연결하세요", false)
    }
    private var reconnectFromLab = false
    private var returningFromLab = false
    private var returningFromSettings = false
    private val labLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data?.hasExtra(LiveStreamsActivity.EXTRA_SETTINGS) == true &&
            data.getStringExtra(WorkbenchActivity.EXTRA_CAMERA_ID) == cameraId) {
            @Suppress("DEPRECATION")
            val settings = data.getSerializableExtra(LiveStreamsActivity.EXTRA_SETTINGS) as? LiveStreamSettings
            if (settings == null) streamSettings.remove(streamKey()) else streamSettings[streamKey()] = settings
            telemetry.event(sessionId, "live_streams_changed", settings?.metadata() ?: mapOf("mode" to "default"))
            paused = false
        }
        if (data?.getStringExtra(WorkbenchActivity.EXTRA_LIVE_ACTION) == WorkbenchActivity.ACTION_RECONNECT) {
            reconnectFromLab = true
        }
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
            }
            val events = recorder.snapshot(10_000_000_000L)
            val frames = events.filter { it.session == sessionId && it.kind == "capture_result" }
            if (engineName == "Camera2") frames.lastOrNull()?.values?.get("fpsRange")?.let { lastStreamFps[streamKey()] = it.toString() }
            val previewAt = if (engineName == "Camera2") lastPreviewFrameNs
                else if (cameraXStreaming) frames.lastOrNull()?.atNs ?: 0L else 0L
            liveIndicator.bind(resumed && !paused && !closing && engine != null &&
                previewAt > 0L && time - previewAt < 1_500_000_000L)
            if (!closing && engine != null) {
                liveIndicator.bindSizes(telemetry.sessions[sessionId]?.get("negotiatedStreams") as? Map<*, *>)
            }
            readings.update(events, frames, time, sessionId, controlBar.controls, controlBar.support, zoomRatio)
            if (callbackGraph.visibility == View.VISIBLE) callbackGraph.update(events, sessionId, time, telemetry.sessions[sessionId].orEmpty())
            recorder.finish()?.let(incidents::export)
            val remaining = recorder.remainingNs()
            reportButton.isEnabled = remaining == null && ready && !paused && cli.active == null
            updateMediaControls()
            reportButton.text = if (remaining != null) "저장까지 ${"%.1f".format(Locale.US, remaining/1e9)}s" else "이벤트 저장 · ZIP"
            main.postDelayed(this, 100)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        engineName = savedInstanceState?.getString("engine") ?: "Camera2"
        cameraId = savedInstanceState?.getString("camera") ?: ""
        paused = savedInstanceState?.getBoolean("paused") ?: false
        returningFromLab = savedInstanceState?.getBoolean("returningFromLab") ?: false
        returningFromSettings = savedInstanceState?.getBoolean("returningFromSettings") ?: false
        reconnectFromLab = savedInstanceState?.getBoolean("reconnectFromLab") ?: false
        zoomRatio = savedInstanceState?.getFloat("zoom") ?: 1f
        videoMode = savedInstanceState?.getBoolean("videoMode") ?: false
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        (savedInstanceState?.getSerializable("liveStreams") as? HashMap<String, LiveStreamSettings>)?.let(streamSettings::putAll)
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        (savedInstanceState?.getSerializable("goodStreams") as? HashMap<String, LiveStreamSettings?>)?.let(goodStreams::putAll)
        manager = getSystemService(CameraManager::class.java)
        buildUi()
        recentMedia = RecentMediaThumbnail(this) { bitmap, video -> galleryButton.setThumbnail(bitmap, video) }
        onBackPressedDispatcher.addCallback(this, graphBack)
        recorder.record("app", "clock_anchor", values = mapOf("wallTimeMs" to System.currentTimeMillis(), "uptimeMs" to SystemClock.uptimeMillis()))
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("engine", engineName); outState.putString("camera", cameraId); outState.putBoolean("paused", paused); outState.putFloat("zoom", zoomRatio)
        outState.putBoolean("videoMode", videoMode)
        outState.putSerializable("liveStreams", HashMap(streamSettings))
        outState.putSerializable("goodStreams", HashMap(goodStreams))
        outState.putBoolean("returningFromLab", returningFromLab)
        outState.putBoolean("returningFromSettings", returningFromSettings)
        outState.putBoolean("reconnectFromLab", reconnectFromLab)
        super.onSaveInstanceState(outState)
    }
    override fun onStart() {
        super.onStart(); resumed = true
        cli.attach(liveCli)
        recentMedia.start()
        main.post(tick)
        // Activity results are delivered by STARTED; consume Lab state once at RESUMED.
        if (returningFromLab || returningFromSettings) return
        if (hasPermission()) restartCamera()
        else { setStatus("카메라 접근을 허용하면 측정이 시작됩니다", false); permission.launch(Manifest.permission.CAMERA) }
    }
    override fun onResume() {
        super.onResume()
        when {
            returningFromLab -> {
                returningFromLab = false
                if (reconnectFromLab) reconnectCameraFromLab()
                else if (cli.active == null && !recordingVideo) {
                    if (hasPermission()) restartCamera()
                    else setStatus("카메라 권한이 필요합니다 · Lab에서 카메라를 다시 연결하세요", false)
                }
            }
            returningFromSettings -> {
                returningFromSettings = false
                if (cli.active == null && !recordingVideo && hasPermission()) restartCamera()
                else if (!hasPermission()) setStatus("카메라 권한이 필요합니다 · Lab에서 카메라를 다시 연결하세요", false)
            }
        }
    }
    override fun onStop() {
        cli.detach(liveCli)
        zoomControl.collapse(animate = false)
        recentMedia.stop()
        main.removeCallbacks(clearNotice); savedNoticeShown = false
        pendingPermissionAction = null
        resumed = false; main.removeCallbacks(tick)
        liveIndicator.bind(false)
        telemetry.event(sessionId.ifEmpty { "app" }, "activity_stopped")
        recorder.finish("activity_stopped")?.let(incidents::export)
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
    private fun reconnectCameraFromLab() {
        reconnectFromLab = false
        if (cli.active != null || recordingVideo) return
        paused = false
        if (hasPermission()) restartCamera()
        else if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) permission.launch(Manifest.permission.CAMERA)
        else {
            returningFromSettings = true
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
        }
    }
    private fun restartCamera() {
        liveIndicator.bind(false)
        liveIndicator.bindSizes(null)
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
            if (paused) setStatus("프리뷰 중지 · Lab에서 카메라를 다시 연결하세요", false)
            return
        }
        if (cameraId.isEmpty()) { setStatus("사용 가능한 카메라가 없습니다", false); return }
        sessionId = UUID.randomUUID().toString()
        val thisSession = sessionId
        val thisCamera = cameraId
        val thisKey = streamKey()
        val requestedStreams = streamSettings[thisKey]
        if (engineName == "Camera2") streamState[thisKey] = "구성 중입니다."
        zoomApplied = false
        updateCameraChoices()
        setStatus("$engineName · ${CameraLabel.short(cameraId)} 연결 중…", false)
        (previewHost.getChildAt(0) as? PreviewView)?.previewStreamState?.removeObservers(this)
        previewHost.removeAllViews()
        val previewReady = { if (thisSession == sessionId && resumed && !closing) liveCli.previewReady() }
        val recordingState = { recording: Boolean ->
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
        }
        val notice = { text: String -> if (thisSession == sessionId && resumed && !closing) showNotice(text) }
        val status = { text: String, ok: Boolean -> if (thisSession == sessionId && resumed && !closing) setStatus(text,ok) }
        engine = if (engineName == "CameraX") {
            val view = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER }
            previewHost.addView(view, FrameLayout.LayoutParams(-1,-1))
            view.previewStreamState.observe(this) { state ->
                if (thisSession == sessionId && resumed && !closing) {
                    cameraXStreaming = state == PreviewView.StreamState.STREAMING
                    if (!cameraXStreaming) liveIndicator.bind(false)
                    else previewReady()
                }
            }
            CameraXEngine(this, this, view, cameraId, sessionId, telemetry, cameraWorker, previewReady, recordingState, notice, status,
                liveStreams = requestedStreams, streamsConfigured = { values ->
                    if (thisSession == sessionId && resumed && !closing) {
                        goodStreams[thisKey] = requestedStreams
                        streamState[thisKey] = "구성 성공"
                        liveIndicator.bindSizes(values)
                    }
                }, streamsFailed = { reason ->
                    if (thisSession == sessionId) cli.active?.takeIf { it.command in setOf("preview", "capture", "record.start") }?.let { cli.fail(it.id, "PREFLIGHT_FAILED", reason) }
                    if (thisSession == sessionId && resumed && !closing) streamState[thisKey] = "실패: $reason"
                })
        } else {
            val view = TextureView(this)
            previewHost.addView(view, FrameLayout.LayoutParams(-1,-1))
            Camera2Engine(this, view, cameraId, sessionId, telemetry, liveStreams = requestedStreams,
                streamsConfigured = { values ->
                    if (thisSession == sessionId && resumed && !closing) {
                        goodStreams[thisKey] = requestedStreams
                        liveIndicator.bindSizes(values)
                        streamState[thisKey] = "구성 성공 · Preview ${values["preview"]} · YUV ${values["analysis"] ?: "Off"} · JPEG ${values["jpeg"] ?: "Off"}"
                    }
                }, streamsFailed = { reason ->
                    if (thisSession == sessionId) cli.active?.takeIf { it.command in setOf("preview", "capture", "record.start") }?.let { cli.fail(it.id, "PREFLIGHT_FAILED", reason) }
                    if (thisSession == sessionId && resumed && !closing) {
                        streamState[thisKey] = "실패: $reason"
                        val failed = engine; engine = null; closing = failed != null
                        setStatus("$reason · Lab → Live Streams에서 구성을 변경하거나 복구하세요.", false)
                        failed?.close {
                            closing = false
                            if (destroyed) cameraWorker.shutdown()
                            else if (resumed && thisSession == sessionId) updateMediaControls()
                        }
                    }
                }, previewReady = previewReady, previewFrame = {
                if (thisSession == sessionId && resumed && !closing && !paused) lastPreviewFrameNs = nowNs()
            }, recordingState = recordingState, notice = notice, status = status)
        }
        previewHost.addView(FocusRing(this, { engine as? TouchMetering }) { controlBar.setAeLock(it) }, FrameLayout.LayoutParams(-1,-1))
        try { engine?.start() } catch (e: Exception) { setStatus("시작 실패: ${e.message}",false) }
        updateCameraChoices()
    }
    /** Shown 2.5 s like a save notice, also while recording; [ready] stays as it is. */
    private fun showNotice(text: String) {
        main.removeCallbacks(clearNotice); savedNoticeShown = true
        cameraNotice.text = text; cameraNotice.visibility = View.VISIBLE; main.postDelayed(clearNotice, 2500)
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
            if (controlBar.controls != LiveControls()) (engine as? LiveTuning)?.setControls(controlBar.controls, restore = true)
        }
    }

    @Suppress("DEPRECATION")
    private fun withMediaPermissions(video: Boolean, action: () -> Unit) {
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT <= 28) required += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (video) required += Manifest.permission.RECORD_AUDIO
        val missing = required.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else { pendingPermissionAction = action; mediaPermissions.launch(missing.toTypedArray()) }
    }
    private fun buildUi() {
        val root=FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        previewHost=FrameLayout(this).apply { setBackgroundColor(Color.BLACK); contentDescription="실시간 카메라 프리뷰" }
        root.addView(previewHost,FrameLayout.LayoutParams(-1,-1))
        // A paused preview keeps its last frame, which looks exactly like a preview that has stopped updating
        // on its own. The scrim says which of the two it is, over the frame rather than above it in the top
        // bar, because that frame is what raises the question.
        pausedOverlay=label("프리뷰 중지됨\nLab → Reconnect Camera로 다시 시작합니다",14,Look.onDark).apply {
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
            pendingPermissionAction=null
            chooseEngine(if(engineName=="Camera2") "CameraX" else "Camera2")
        }
        // Lab groups inspection, saved results and settings; Mark stays on the preview.
        labButton=button("Lab") { openLab() }.apply { contentDescription="Lab 열기: 검사 도구, 저장된 결과, 설정" }
        // Read-only overlay controls remain usable while the CLI owns a recording.
        graphButton=CameraWidgets(this).button("Callback") { showCallbacks(callbackGraph.visibility != View.VISIBLE) }.apply { contentDescription="Callback 타이밍 표시" }
        listOf(engineButton,labButton,graphButton).forEach {
            it.background=cameraChrome(Color.TRANSPARENT)
            it.setTextColor(Color.WHITE)
            it.setPadding(dp(12),0,dp(12),0)
            it.setSingleLine(true)
            it.minWidth=dp(48); it.minimumWidth=dp(48)
        }
        liveIndicator=LiveIndicator(this).apply { onSizesClick = ::openLiveStreams }
        val leadingSlot=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; gravity=Gravity.START
            addView(engineButton,LinearLayout.LayoutParams(-2,dp(48)))
        }
        val trailingSlot=row().apply {
            gravity=Gravity.END or Gravity.CENTER_VERTICAL
            addView(graphButton,LinearLayout.LayoutParams(-2,dp(48)))
            addView(labButton,LinearLayout.LayoutParams(-2,dp(48)).apply { marginStart=dp(4) })
        }
        // Only the side slots share spare width; the centred expander needs a 48dp touch target.
        // Giving it a third of the row squeezed the two trailing labels and wrapped "Callback".
        controlBar=LiveControlBar(this,object : LiveControlBar.Host {
            override fun controlsChanged(controls: LiveControls) { (engine as? LiveTuning)?.setControls(controls) }
            override fun notice(text: String) = toast(text)
        })
        controls.gravity=Gravity.TOP
        controls.addView(leadingSlot,LinearLayout.LayoutParams(0,-2,1f))
        controls.addView(FrameLayout(this).apply {
            addView(controlBar.handle,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        },LinearLayout.LayoutParams(dp(48),dp(48)))
        controls.addView(trailingSlot,LinearLayout.LayoutParams(0,dp(48),1f))
        topBar.addView(liveIndicator,LinearLayout.LayoutParams(-1,-2))
        topBar.addView(controlBar.view,lp(top=4))
        resetControls()
        cameraNotice=label("카메라 준비 중…",12,Look.onDark).apply {
            gravity=Gravity.CENTER
            accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        topBar.addView(cameraNotice,lp(top=4))

        // The zoom rail expands horizontally without moving the shutter or the readout.
        bottomBar=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_HORIZONTAL; setPadding(dp(16),dp(4),dp(16),0) }
        // A single soft scrim spans capture controls and Mark without a separate footer band.
        val captureChrome=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            background=GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,intArrayOf(Color.argb(180,0,0,0),Color.TRANSPARENT))
        }
        root.addView(captureChrome,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        captureChrome.addView(bottomBar,LinearLayout.LayoutParams(-1,-2))
        metrics=label("FPS — · ISO — · Exp —\nAE — · AF —",12,Look.onDark).apply {
            textSize=11f
            maxLines=2
            gravity=Gravity.CENTER
            typeface=Look.mono
            setShadowLayer(dp(2).toFloat(),0f,0f,Color.BLACK)
        }
        callbackGraph=ResultCallbackGraph(this).apply { visibility=View.GONE }
        bottomBar.addView(callbackGraph,lp())
        readings=LiveReadings(this,metrics,recorder,io)
        bottomBar.addView(metrics,lp())
        zoomControl=ExpandingZoomControl(this) { ratio ->
            if (cli.active != null) return@ExpandingZoomControl
            zoomRatio=ratio; engine?.setZoom(ratio)
        }
        val zoomViewport=zoomControl.viewport()
        bottomBar.addView(zoomViewport,LinearLayout.LayoutParams(-2,dp(48)))
        val captureRow=row().apply { gravity=Gravity.CENTER_VERTICAL }
        bottomBar.addView(captureRow,lp(top=4))
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
                    withMediaPermissions(true) { (engine as? MediaCapture)?.startRecording() }
                } else withMediaPermissions(false) { engine?.capture() }
            }
        }
        captureRow.addView(mediaButton,LinearLayout.LayoutParams(dp(72),dp(72)).apply { marginStart=dp(12); marginEnd=dp(12) })
        cameraShortcut=IconButton(this,R.drawable.ic_camera_select,"카메라 선택",filled=true) { selectCamera(cameraShortcut) }
        val cameraSlot=FrameLayout(this).apply { addView(cameraShortcut,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER)) }
        captureRow.addView(cameraSlot,LinearLayout.LayoutParams(0,dp(72),1f))

        val modeRow=FrameLayout(this)
        bottomBar.addView(modeRow,lp(height=48))
        modeControls=row().apply { gravity=Gravity.CENTER }
        photoModeButton=button("사진") { selectMode(false) }
        videoModeButton=button("동영상") { selectMode(true) }
        listOf(photoModeButton,videoModeButton).forEach {
            it.background=cameraChrome(Color.TRANSPARENT)
            it.textSize=12f
            modeControls.addView(it,LinearLayout.LayoutParams(dp(80),dp(48)))
        }
        modeRow.addView(modeControls,FrameLayout.LayoutParams(-2,-1,Gravity.CENTER))
        recordingTime=label("● REC  00:00",14,coral,true).apply { gravity=Gravity.CENTER; typeface=Look.mono; visibility=View.GONE }
        modeRow.addView(recordingTime,FrameLayout.LayoutParams(-1,-1))

        val mainRow=row().apply {
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(16),0,dp(16),0)
            setBackgroundColor(Color.TRANSPARENT)
        }
        reportButton=button(MARK_LABEL) {
            val id="incident_"+SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(Date())+"_"+UUID.randomUUID().toString().take(8)
            // The reading is captured here, not when the dialog opens. The dialog is at least five seconds later
            // and after an asynchronous write, by which point lastReading has been replaced many times over and
            // may even belong to a different camera. What the dialog shows has to be the evidence for that ZIP.
            if(recorder.trigger(id)) { incidents.marked(id,readings.last); toast("5초 후 incident ZIP을 저장합니다") }
        }.apply { setTextColor(Color.WHITE); background=cameraChrome(Color.TRANSPARENT); contentDescription="Mark: 직전 10초와 이후 5초를 ZIP으로 저장" }
        reportButton.minHeight=dp(48); reportButton.minimumHeight=dp(48)
        reportButton.contentDescription="문제 시점 기록: 직전 10초와 이후 5초의 이벤트를 ZIP으로 저장"
        mainRow.addView(reportButton,LinearLayout.LayoutParams(0,-2,1f))
        reportButton.setShadowLayer(dp(2).toFloat(),0f,0f,Color.BLACK)
        captureChrome.addView(mainRow,LinearLayout.LayoutParams(-1,-2))

        root.setOnApplyWindowInsetsListener { _,insets ->
            val (l,t,r,b)=if (Build.VERSION.SDK_INT >= 30) {
                val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                listOf(bars.left,bars.top,bars.right,bars.bottom)
            } else { @Suppress("DEPRECATION") listOf(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom) }
            topBar.setPadding(dp(12)+l,dp(8)+t,dp(12)+r,dp(10))
            bottomBar.setPadding(dp(16)+l,dp(4),dp(16)+r,0)
            mainRow.setPadding(dp(16)+l,0,dp(16)+r,b)
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
                pendingPermissionAction=null
                recorder.finish("camera_changed")?.let(incidents::export)
                cameraId=chosen; zoomRatio=1f
                resetControls(); updateCameraChoices(); restartCamera()
            }
        }
    }
    private fun selectMode(video: Boolean) {
        if (cli.active != null) return
        if (recordingVideo || !ready || videoMode==video) return
        pendingPermissionAction=null
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
        cameraShortcut.contentDescription="카메라 선택 목록 열기, 현재 ${cameraLabel(cameraId)}"
        cameraShortcut.tooltipText=cameraShortcut.contentDescription
        zoomControl.setChoices(if(cameraId.isEmpty()) listOf(1f) else zoomPresets(zoomRange(manager,cameraId)),zoomRatio)
    }
    private fun updateMediaControls() {
        if (!resumed || paused || closing || engine == null) liveIndicator.bind(false)
        cli.setUiBusy(liveCli, recordingVideo || stoppingRecording || pendingPermissionAction != null || (engine as? MediaCapture)?.mediaBusy == true)
        listOf(photoModeButton,videoModeButton).forEachIndexed { index, button ->
            val selected=(index==1)==videoMode
            button.isSelected=selected
            button.isEnabled=ready && !recordingVideo
            button.setTextColor(if(selected) Look.onDark else Look.onDarkMuted)
            button.setTypeface(null,if(selected) Typeface.BOLD else Typeface.NORMAL)
            button.contentDescription=if(index==0) "사진 모드, 선택한 YUV 및 JPEG 출력 저장" else "동영상 모드, 소리 포함"
            ViewCompat.setStateDescription(button,if(selected) "선택됨" else null)
        }
        pausedOverlay.visibility=if(paused) View.VISIBLE else View.GONE
        modeControls.visibility=if(recordingVideo) View.INVISIBLE else View.VISIBLE
        recordingTime.visibility=if(recordingVideo) View.VISIBLE else View.GONE
        mediaButton.setCaptureState(videoMode,recordingVideo)
        if(stoppingRecording) {
            mediaButton.contentDescription="동영상 저장 중"
            ViewCompat.setStateDescription(mediaButton,"저장 중")
        }
        mediaButton.isEnabled=(ready || recordingVideo) && !stoppingRecording
        if (!videoMode && streamSettings[streamKey()]?.canCapture == false) {
            mediaButton.isEnabled = false
            mediaButton.contentDescription = "사진 출력 꺼짐: Live 스트림에서 YUV 또는 JPEG을 켜세요"
        }
        engineButton.isEnabled=!recordingVideo
        cameraShortcut.isEnabled=!recordingVideo && cameraId.isNotEmpty()
        // Zoom stays live while recording (#174): the engine changes the recording request in place.
        // The engine reports "REC" as not-ready, so a running recording counts as ready here, as for the shutter.
        zoomControl.isEnabled=(ready || recordingVideo) && !stoppingRecording
        controlBar.bind(videoMode,(ready || recordingVideo) && !stoppingRecording && cli.active==null)
        galleryButton.isEnabled=!recordingVideo
        labButton.isEnabled=!recordingVideo && !stoppingRecording && !closing && (engine as? MediaCapture)?.mediaBusy != true
        liveIndicator.setSizesEnabled(labButton.isEnabled && cli.active == null && pendingPermissionAction == null && cameraId.isNotEmpty())
        if (cli.active != null) {
            listOf(mediaButton, engineButton, cameraShortcut, photoModeButton, videoModeButton, zoomControl, galleryButton, labButton, reportButton).forEach { it.isEnabled = false }
        }
        listOf(engineButton,cameraShortcut,photoModeButton,videoModeButton,mediaButton,galleryButton,labButton).forEach {
            it.alpha=if(it.isEnabled) 1f else 0.4f
        }
    }
    private fun chooseEngine(name:String) {
        if(engineName==name) return
        recorder.finish("engine_changed")?.let(incidents::export)
        engineName=name; resetControls(); updateCameraChoices(); restartCamera()
    }
    /** Locks, EV and flash start over for every camera and engine; the new session opens with the defaults. */
    private fun resetControls()=controlBar.reset(if(cameraId.isEmpty()) LiveControlSupport.NONE else liveControlSupport(manager,cameraId))
    private fun streamKey() = liveStreamSettingsKey(cameraId, engineName)
    private fun openLab() {
        openAfterClose("workbench_opened") {
            streamSettingsIntent(WorkbenchActivity::class.java)
        }
    }
    private fun openLiveStreams() {
        if (cli.active != null || recordingVideo || stoppingRecording || closing ||
            pendingPermissionAction != null || (engine as? MediaCapture)?.mediaBusy == true || cameraId.isEmpty()) return
        openAfterClose("live_stream_settings_opened") {
            streamSettingsIntent(LiveStreamsActivity::class.java)
                .putExtra(LiveStreamsActivity.EXTRA_FROM_LIVE, true)
        }
    }
    private fun streamSettingsIntent(destination: Class<*>) =
            Intent(this, destination)
                .putExtra(WorkbenchActivity.EXTRA_CAMERA_ID, cameraId)
                .putExtra(WorkbenchActivity.EXTRA_ENGINE, engineName)
                .putExtra(LiveStreamsActivity.EXTRA_SETTINGS, streamSettings[streamKey()])
                .putExtra(LiveStreamsActivity.EXTRA_GOOD_SETTINGS, goodStreams[streamKey()])
                .putExtra(LiveStreamsActivity.EXTRA_HAS_GOOD, goodStreams.containsKey(streamKey()))
                .putExtra(LiveStreamsActivity.EXTRA_STATUS, (streamState[streamKey()] ?: "아직 구성하지 않았습니다.") +
                    lastStreamFps[streamKey()]?.let { "\n결과 FPS: $it" }.orEmpty())
    /**
     * Lab can launch CTS and Benchmark, so the live session must be closed and its close(done) received
     * before the next screen starts. Same sequence as the CLI CTS path; onStop's restartCamera() sees
     * `closing` and stays out of the way, and onResume applies the Lab result before reopening the camera.
     */
    private fun openAfterClose(reason: String, intent: () -> Intent) {
        if (closing) return
        recorder.finish(reason)?.let(incidents::export)
        showCallbacks(false)
        val old = engine; engine = null; closing = true; ready = false
        setStatus("카메라 세션 종료 중…", false)
        updateMediaControls()
        val open = {
            closing = false
            if (resumed && !destroyed) {
                returningFromLab = true
                labLauncher.launch(intent())
            } else updateMediaControls()
        }
        if (old == null) open() else old.close { open() }
    }
    /** Ends the recording and says so on both stop buttons, whichever one the user reached. */
    private fun stopRecording() {
        if (!recordingVideo || stoppingRecording) return
        stoppingRecording = true
        recordingTime.text = "저장 중…"
        updateMediaControls()
        (engine as? MediaCapture)?.stopRecording()
    }
    private fun showCallbacks(show: Boolean) {
        callbackGraph.reset()
        callbackGraph.visibility = if (show) View.VISIBLE else View.GONE
        metrics.visibility = if (show) View.GONE else View.VISIBLE
        graphBack.isEnabled = show
        graphButton.contentDescription = if (show) "Callback 타이밍 숨기기" else "Callback 타이밍 표시"
        graphButton.isSelected = show
        ViewCompat.setStateDescription(graphButton, if (show) "표시됨" else "숨겨짐")
        if (show) callbackGraph.update(recorder.snapshot(10_000_000_000L),sessionId,nowNs(),telemetry.sessions[sessionId].orEmpty())
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
