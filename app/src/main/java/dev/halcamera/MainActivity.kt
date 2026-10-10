package dev.halcamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
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

/** Live launcher: preview, capture and observation. Benchmark owns baseline comparisons and verdicts. */
class MainActivity : ComponentActivity() {
    internal val cli by lazy { dev.halcamera.cli.CommandCoordinator.get(this) }
    internal val cliSequence by lazy { dev.halcamera.cli.CliSequence(this) }
    private val liveCli by lazy { createLiveCli() }
    internal fun exportCliIncident(incident: Incident) {
        if (cli.active?.let { it.id == incident.id && it.command == "events" } == true) {
            val sessions = telemetry.sessions.toMap()
            io.execute {
                try {
                    val file = IncidentExporter(this).export(incident, sessions)
                    cli.complete(incident.id, org.json.JSONObject().put("artifact_count", 1)
                        .put("cancelled", cli.store.read(incident.id)?.optString("state") == "cancelling"),
                        listOf(dev.halcamera.cli.CliArtifact(file.name, "application/zip", android.net.Uri.fromFile(file))))
                } catch (e: Exception) { cli.fail(incident.id, "SAVE_FAILED", e.message ?: "Events export failed") }
            }
        } else incidents.export(incident)
    }
    companion object {
        /** Shared initial and idle label for the event ZIP action. */
        const val MARK_LABEL = "Save Events · ZIP"
    }
    // Camera UI follows docs/design/APP-UI.md; shared dark surfaces and active states use ui/Look.
    private val bg = Look.cameraSurface
    private val panel = Look.cameraCard
    private val muted = dev.halcamera.ui.Look.onDarkMuted
    private val coral = dev.halcamera.ui.Look.statusFail
    private val glass = Look.cameraGlass
    internal val main = Handler(Looper.getMainLooper())
    /** Buttons refuse taps while an ADB command drives the camera. */
    private val widgets by lazy { dev.halcamera.ui.CameraWidgets(this) { cli.active == null } }
    private val cameraWorker = Executors.newSingleThreadExecutor()
    internal val io = Executors.newSingleThreadExecutor()
    internal val recorder = FlightRecorder(::nowNs)
    internal val telemetry = Telemetry(recorder)
    internal var engine: CameraEngine? = null
    /** The camera [engine] opened. A CLI request changes [cameraId] before the old engine closes. */
    private var engineCameraId: String? = null
    internal var closing = false
    internal var resumed = false
    private var destroyed = false
    internal var paused = false
    internal var engineName = "CameraX"
    internal var cameraId = ""
    internal var sessionId = ""
    internal val streamSettings = mutableMapOf<String, LiveStreamSettings>()
    private val goodStreams = mutableMapOf<String, LiveStreamSettings?>()
    private val streamState = mutableMapOf<String, String>()
    private val eisTracker = LiveEisTracker()
    internal var ready = false
    private val previewStartup = PreviewStartup()
    internal var zoomRatio = 1f
    private var zoomApplied = false
    private var saveFile: File? = null
    internal lateinit var manager: CameraManager
    internal lateinit var previewHost: FrameLayout
    /** Sits over the frozen frame while the preview is paused. */
    private lateinit var pausedOverlay: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var graphButton: Button
    private lateinit var callbackGraph: ResultCallbackGraph
    private lateinit var zoomControl: ExpandingZoomControl
    internal lateinit var controlBar: LiveControlBar
    private lateinit var manualPanel: ManualControlPanel
    internal var manualCapabilities = ManualSupport()
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
    private lateinit var multiButton: Button
    private lateinit var multiVideoButton: Button
    private lateinit var modeControls: LinearLayout
    private lateinit var recordingTime: TextView
    private lateinit var galleryButton: RecentMediaButton
    private lateinit var cameraShortcut: IconButton
    /** Takes a photo from the running recording (#175); it sits where the camera picker is while recording. */
    private lateinit var snapshotButton: IconButton
    private var cameraIds = emptyList<String>()
    /** Logical id to endpoint, so [cameraLabel] can name the lens without re-reading CameraCharacteristics. */
    private var cameraEndpoints = emptyMap<String, CameraEndpoint>()
    private lateinit var labButton: Button
    internal var videoMode = false
    internal var recordingVideo = false
    internal var stoppingRecording = false
    private var recordingStartedAt = 0L
    internal var pendingPermissionAction: (() -> Unit)? = null
    internal val captureFeedback by lazy { CaptureFeedback(this) }
    internal val bursts by lazy {
        LiveBurst(SystemClock::elapsedRealtime, { d, b -> main.postDelayed(b, d) },
            { engine as? MediaCapture }, { ready && cli.active == null && !videoMode }, telemetry::event, ::updateMediaControls,
            { if (resumed) captureFeedback.showResult(it) }, BracketFusion(this))
    }
    private val mediaPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val action = pendingPermissionAction.also { pendingPermissionAction = null }
        if (grants.values.all { it }) action?.invoke() else toast("Allow the requested permissions to save media.")
    }
    private lateinit var engineButton: Button
    private lateinit var physicalPipButton: Button
    private var pendingPip: PipSource? = null
    private val pipUi by lazy { LivePipController(this,{ previewHost },{ engine as? PipCamera },::updateMediaControls,{ lastPreviewFrameNs = nowNs() },::showNotice,{ cameraId }) }
    private val physicalPip by lazy { dev.halcamera.ui.PhysicalPipPicker(this,manager) }
    private val graphBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showCallbacks(false)
    }
    private val manualBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { manualPanel.close(); isEnabled = false }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) restartCamera() else setStatus("Camera permission required · Reconnect Camera in Lab.", false)
    }
    private var reconnectFromLab = false
    private var returningFromLab = false
    private var returningFromSettings = false
    private val labLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data?.hasExtra(DualPreviewActivity.EXTRA_RETURN_VIDEO) == true) {
            videoMode = data.getBooleanExtra(DualPreviewActivity.EXTRA_RETURN_VIDEO, false)
            data.getStringExtra(DualPreviewActivity.EXTRA_ENGINE)?.takeIf { it == "Camera2" || it == "CameraX" }?.let {
                if (engineName != it) { engineName = it; resetControls() }
            }
            resetModeSettings()
        }
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
                    ?: error("Cannot open the save location.")
                main.post { if (!destroyed) toast("Saved to your chosen location. Safely stashed.") }
            } catch (e: Exception) { main.post { if (!destroyed) toast("Save failed: ${e.message}") } }
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
            val previewAt = if (engineName == "Camera2" || pipUi.selected != null) lastPreviewFrameNs
                else if (cameraXStreaming) frames.lastOrNull()?.atNs ?: 0L else 0L
            val live = resumed && !paused && !closing && engine != null &&
                previewAt > 0L && time - previewAt < 1_500_000_000L
            liveIndicator.bind(live)
            val eisFrame = frames.lastOrNull {
                if (engineName == "Camera2") it.values["requestTag"] == if (recordingVideo) "recording" else "preview"
                else when ((it.values["captureIntent"] as? Number)?.toInt()) {
                    CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD -> true
                    else -> false
                }
            }
            liveIndicator.bindStabilization(eisTracker.update(sessionId, recordingVideo,
                (streamSettings[streamKey()]?.stabilization ?: LiveStabilization.AUTO).eisComparisonMode(engineName, recordingVideo),
                eisFrame?.atNs, (eisFrame?.values?.get("videoStabilization") as? Number)?.toInt(), time,
                live && !stoppingRecording), recordingVideo)
            if (!closing && engine != null) {
                liveIndicator.bindSizes(telemetry.sessions[sessionId]?.get("negotiatedStreams") as? Map<*, *>)
            }
            readings.update(events, frames, time, sessionId, controlBar.controls, controlBar.support, zoomRatio)
            manualPanel.bind(controlBar.controls.manual, (ready || recordingVideo) && !stoppingRecording && cli.active == null && !bursts.controlsLocked,
                manualCapabilities, frames.lastOrNull(), time)
            manualBack.isEnabled = manualPanel.isExpanded
            if (callbackGraph.visibility == View.VISIBLE) callbackGraph.update(events, sessionId, time, telemetry.sessions[sessionId].orEmpty())
            recorder.finish()?.let(::exportCliIncident)
            val remaining = recorder.remainingNs()
            reportButton.isEnabled = remaining == null && ready && !paused && cli.active == null
            updateMediaControls()
            reportButton.text = if (remaining != null) "Saving in ${"%.1f".format(Locale.US, remaining/1e9)}s" else MARK_LABEL
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
        onBackPressedDispatcher.addCallback(this, manualBack)
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
        else { setStatus("Allow camera access to start the preview.", false); permission.launch(Manifest.permission.CAMERA) }
    }
    override fun onResume() {
        super.onResume()
        when {
            returningFromLab -> {
                returningFromLab = false
                if (reconnectFromLab) reconnectCameraFromLab()
                else if (cli.active == null && !recordingVideo) {
                    if (hasPermission()) restartCamera()
                    else setStatus("Camera permission required · Reconnect Camera in Lab.", false)
                }
            }
            returningFromSettings -> {
                returningFromSettings = false
                if (cli.active == null && !recordingVideo && hasPermission()) restartCamera()
                else if (!hasPermission()) setStatus("Camera permission required · Reconnect Camera in Lab.", false)
            }
        }
    }
    override fun onStop() {
        cliSequence.cancel()
        cli.detach(liveCli)
        zoomControl.collapse(animate = false)
        recentMedia.stop()
        main.removeCallbacks(clearNotice); savedNoticeShown = false
        pendingPermissionAction = null
        resumed = false; main.removeCallbacks(tick)
        liveIndicator.bind(false)
        telemetry.event(sessionId.ifEmpty { "app" }, "activity_stopped")
        recorder.finish("activity_stopped")?.let(::exportCliIncident)
        restartCamera()
        super.onStop()
    }
    override fun onDestroy() {
        cliSequence.cancel()
        destroyed = true
        bursts.dispose()
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
    internal fun restartCamera() {
        bursts.close()
        eisTracker.reset()
        liveIndicator.bind(false)
        liveIndicator.bindSizes(null)
        lastPreviewFrameNs = 0L
        cameraXStreaming = false
        zoomControl.collapse(animate = false)
        if (closing) return
        ready=false; mediaButton.isEnabled=false; reportButton.isEnabled=false
        val old = engine; engine = null
        val released = engineCameraId.takeIf { old != null }
        (old as? CameraXEngine)?.releaseOnClose = engineName != "CameraX"
        if (old != null) {
            closing = true; setStatus("Closing camera…", false)
            old.close {
                closing = false
                if (destroyed) cameraWorker.shutdown() else openCamera(released)
            }
        } else openCamera()
    }
    /** [released]: the camera the previous engine closed, which Camera2 waits for (#230). */
    private fun openCamera(released: String? = null) {
        if (!resumed || destroyed || paused || !hasPermission()) {
            if (paused) setStatus("Preview paused · Reconnect Camera in Lab.", false)
            return
        }
        if (cameraId.isEmpty()) { setStatus("No cameras available.", false); return }
        sessionId = UUID.randomUUID().toString()
        if (pendingPip == null && engineCameraId == cameraId) pendingPip = pipUi.selected
        previewStartup.reset()
        engineCameraId = cameraId
        val thisSession = sessionId
        val thisCamera = cameraId
        val thisKey = streamKey()
        val requestedStreams = streamSettings[thisKey]
        if (engineName == "Camera2") streamState[thisKey] = "Configuring… Getting the pixels in line."
        zoomApplied = false
        updateCameraChoices()
        setStatus("$engineName · ${CameraLabel.short(cameraId)} · Connecting…", false)
        (previewHost.getChildAt(0) as? PreviewView)?.previewStreamState?.removeObservers(this)
        pipUi.reset()
        previewHost.removeAllViews()
        val previewReady = { if (thisSession == sessionId && resumed && !closing) {
            try { liveCli.previewReady() } catch (e: Exception) { cli.active?.let { cli.fail(it.id, (e as? dev.halcamera.cli.CliFailure)?.code ?: "EXECUTION_FAILED", e.message ?: "CLI operation failed") } }
        } }
        val recordingState = { recording: Boolean ->
            if (thisSession == sessionId) {
                recordingVideo = recording
                eisTracker.reset()
                liveIndicator.bindStabilization(LiveEisStatus(), recording)
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
                    else { initialPreviewArrived(); previewReady() }
                }
            }
            CameraXEngine(this, this, view, cameraId, sessionId, telemetry, cameraWorker, previewReady, recordingState, notice, status,
                liveStreams = requestedStreams, streamsConfigured = { values ->
                    if (thisSession == sessionId && resumed && !closing) {
                        goodStreams[thisKey] = requestedStreams
                        streamState[thisKey] = "Configured"
                        liveIndicator.bindSizes(values)
                    }
                }, streamsFailed = { reason ->
                    if (thisSession == sessionId) cli.active?.takeIf { it.command in dev.halcamera.cli.CliCommand.STREAM_COMMANDS }?.let { cli.fail(it.id, "PREFLIGHT_FAILED", reason) }
                    if (thisSession == sessionId && resumed && !closing) streamState[thisKey] = "Failed: $reason"
                })
        } else {
            val view = TextureView(this)
            previewHost.addView(view, FrameLayout.LayoutParams(-1,-1))
            Camera2Engine(this, view, cameraId, sessionId, telemetry, liveStreams = requestedStreams, releasedCameraId = released,
                streamsConfigured = { values ->
                    if (thisSession == sessionId && resumed && !closing) {
                        goodStreams[thisKey] = requestedStreams
                        liveIndicator.bindSizes(values)
                        streamState[thisKey] = "Configured · Preview ${values["preview"]} · YUV ${values["analysis"] ?: "Off"} · JPEG ${values["jpeg"] ?: "Off"}"
                    }
                }, streamsFailed = { reason ->
                    if (thisSession == sessionId) cli.active?.takeIf { it.command in dev.halcamera.cli.CliCommand.STREAM_COMMANDS }?.let { cli.fail(it.id, "PREFLIGHT_FAILED", reason) }
                    if (thisSession == sessionId && resumed && !closing) {
                        streamState[thisKey] = "Failed: $reason"
                        val failed = engine; engine = null; closing = failed != null
                        setStatus("$reason · Change or restore the configuration in Lab → Live Streams.", false)
                        failed?.close {
                            closing = false
                            if (destroyed) cameraWorker.shutdown()
                            else if (resumed && thisSession == sessionId) updateMediaControls()
                        }
                    }
                }, previewReady = previewReady, previewFrame = {
                if (thisSession == sessionId && resumed && !closing && !paused) {
                    lastPreviewFrameNs = nowNs()
                    initialPreviewArrived()
                }
            }, recordingState = recordingState, notice = notice, status = status)
        }
        previewHost.addView(FocusRing(this, { engine as? TouchMetering }) { controlBar.setAeLock(it) }.apply {
            canInteract = { !bursts.controlsLocked && cli.active == null }
            unavailableReason = { exposure ->
                if (exposure && controlBar.controls.manual.exposure != null) "Manual exposure · Adjust ISO and Shutter"
                else if (!exposure && controlBar.controls.manual.focusDiopters != null) "Manual focus · Select Auto in Focus"
                else null
            }
        }, FrameLayout.LayoutParams(-1,-1))
        try { engine?.start() } catch (e: Exception) { setStatus("Start failed: ${e.message}",false) }
        updateCameraChoices()
    }
    private fun initialPreviewArrived() {
        previewStartup.frameArrived()?.let { setStatus(it, true) }
    }

    /** Save failures remain visible; routine tuning notices expire without changing [ready]. */
    private fun showNotice(text: String) {
        main.removeCallbacks(clearNotice); savedNoticeShown = true
        cameraNotice.text = text; cameraNotice.visibility = View.VISIBLE
        if (!text.startsWith("Video not saved") && !text.startsWith("Video save failed")) main.postDelayed(clearNotice, 3000)
    }
    internal fun setStatus(text: String, ok: Boolean) {
        // Configuration/open callbacks can precede the first displayed frame.
        if (previewStartup.defer(text, ok)) return
        // Keep a save notice across routine LIVE reports, but let errors replace it.
        val saved = ok && text.contains("saved", ignoreCase = true)
        if (captureFeedback.coversStatus(text)) cameraNotice.visibility = View.GONE
        else if (!(savedNoticeShown && ok && text.endsWith("· LIVE"))) {
            main.removeCallbacks(clearNotice)
            savedNoticeShown = saved
            cameraNotice.text = text
            cameraNotice.visibility = if ((!ok && !recordingVideo) || (text.contains("실패") || text.contains("failed", ignoreCase = true)) || saved) View.VISIBLE else View.GONE
            if (saved && !text.contains("failed",ignoreCase=true) && !text.contains("not saved",ignoreCase=true))
                main.postDelayed(clearNotice,3000)
        }
        ready=ok; reportButton.isEnabled=ok && recorder.remainingNs()==null
        if (ok || recordingVideo) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateMediaControls()
        // Re-apply the chosen zoom once the new session is live so engine and camera switches keep the same framing.
        if (ok && pendingPip != null) {
            val source = pendingPip; pendingPip = null
            if (pipSources().any { it.key == source?.key }) main.post { pipUi.select(source) }
        }
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
        previewHost=FrameLayout(this).apply { setBackgroundColor(Color.BLACK); contentDescription="Live camera preview" }
        root.addView(previewHost,FrameLayout.LayoutParams(-1,-1))
        // A paused preview keeps its last frame, which looks exactly like a preview that has stopped updating
        // on its own. The scrim says which of the two it is, over the frame rather than above it in the top
        // bar, because that frame is what raises the question.
        pausedOverlay=label("Preview paused\nChoose Lab → Reconnect Camera to resume.",14,Look.onDark).apply {
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
        physicalPipButton=button("PIP") {
            physicalPip.show(cameraId,engineName,pipUi.selected,pipSources()) { _,source -> pipUi.select(source) }
        }
        // Lab groups inspection, saved results and settings; Mark stays on the preview.
        labButton=button("Lab") { openLab() }.apply { contentDescription="Open Lab: inspection tools, saved results, and settings" }
        // Read-only overlay controls remain usable while the CLI owns a recording.
        graphButton=CameraWidgets(this).button("Callback") { showCallbacks(callbackGraph.visibility != View.VISIBLE) }.apply { contentDescription="Show callback timing" }
        listOf(engineButton,physicalPipButton,labButton,graphButton).forEach {
            it.background=cameraChrome(Color.TRANSPARENT)
            it.setTextColor(Color.WHITE)
            it.setPadding(dp(8),0,dp(8),0)
            it.setSingleLine(true)
            it.gravity=Gravity.CENTER
            it.includeFontPadding=false
            it.minWidth=dp(48); it.minimumWidth=dp(48)
        }
        liveIndicator=LiveIndicator(this).apply { onSizesClick = ::openLiveStreams }
        val leadingSlot=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL; gravity=Gravity.START or Gravity.CENTER_VERTICAL; isBaselineAligned=false
            addView(engineButton,LinearLayout.LayoutParams(-2,dp(48)))
            addView(physicalPipButton,LinearLayout.LayoutParams(-2,dp(48)))
        }
        val trailingSlot=row().apply {
            gravity=Gravity.END or Gravity.CENTER_VERTICAL
            addView(graphButton,LinearLayout.LayoutParams(-2,dp(48)))
            addView(labButton,LinearLayout.LayoutParams(-2,dp(48)).apply { marginStart=dp(4) })
        }
        // Only the side slots share spare width; the centred expander needs a 48dp touch target.
        // Giving it a third of the row squeezed the two trailing labels and wrapped "Callback".
        controlBar=LiveControlBar(this,object : LiveControlBar.Host {
            override fun controlsChanged(controls: LiveControls) { if (!bursts.controlsLocked) (engine as? LiveTuning)?.setControls(controls) }
            override fun notice(text: String) = toast(text)
            override fun manualRequested() { showCallbacks(false); manualPanel.toggle() }
            override fun manualClosed() { if (::manualPanel.isInitialized) manualPanel.close() }
        })
        manualPanel = ManualControlPanel(this, { if (!bursts.controlsLocked) controlBar.setManual(it) }, ::toast,
            controlBar::setManualExpanded)
        controls.gravity=Gravity.TOP
        controls.addView(leadingSlot,LinearLayout.LayoutParams(0,dp(48),1f))
        controls.addView(FrameLayout(this).apply {
            addView(controlBar.handle,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        },LinearLayout.LayoutParams(dp(48),dp(48)))
        controls.addView(trailingSlot,LinearLayout.LayoutParams(0,dp(48),1f))
        topBar.addView(controlBar.withStatus(liveIndicator),lp())
        topBar.addView(manualPanel.view,lp())
        resetControls()
        cameraNotice=label("Preparing camera… Gathering photons.",12,Look.onDark).apply {
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
        metrics=label("FPS — · ISO — · Exp — · AE — · AF —",12,Look.onDark).apply {
            textSize=10f
            gravity=Gravity.CENTER
            typeface=Typeface.DEFAULT
            setShadowLayer(dp(2).toFloat(),0f,0f,Color.BLACK)
        }
        callbackGraph=ResultCallbackGraph(this).apply { visibility=View.GONE }
        bottomBar.addView(callbackGraph,lp())
        readings=LiveReadings(this,metrics,recorder,io)
        bottomBar.addView(metrics,lp())
        zoomControl=ExpandingZoomControl(this) { ratio ->
            if (cli.active != null || bursts.controlsLocked) return@ExpandingZoomControl
            zoomRatio=ratio; engine?.setZoom(ratio)
        }
        val zoomViewport=zoomControl.viewport()
        bottomBar.addView(zoomViewport,LinearLayout.LayoutParams(-2,dp(48)))
        root.addView(captureFeedback,FrameLayout.LayoutParams(-1,dp(24),Gravity.BOTTOM))
        captureChrome.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ ->
            val params = captureFeedback.layoutParams as FrameLayout.LayoutParams
            val margin = captureChrome.height + dp(4)
            if (params.bottomMargin != margin) { params.bottomMargin = margin; captureFeedback.layoutParams = params }
        }
        val captureRow=row().apply { gravity=Gravity.CENTER_VERTICAL; isBaselineAligned=false }
        bottomBar.addView(captureRow,lp(top=4))
        galleryButton=RecentMediaButton(this) {
            if (cli.active != null) return@RecentMediaButton
            withMediaPermissions(false) { startActivity(Intent(this, GalleryActivity::class.java)) }
        }
        val gallerySlot=FrameLayout(this).apply { addView(galleryButton,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER)) }
        val captureSize=dp(64)
        captureRow.addView(gallerySlot,LinearLayout.LayoutParams(0,captureSize,1f))
        mediaButton=ShutterButton(this).apply {
            setOnClickListener {
                if (cli.active != null) return@setOnClickListener
                if (bursts.run == null) captureFeedback.clearResult()
                if(bursts.run != null) bursts.stop()
                else if(recordingVideo) stopRecording()
                else if(videoMode) {
                    withMediaPermissions(true) { (engine as? MediaCapture)?.startRecording() }
                } else withMediaPermissions(false) { controlBar.controls.let { if (it.bracket) bursts.bracket(sessionId, it, controlBar.support) else engine?.capture() } }
            }
            bindBurstInput({ !videoMode && !mediaBusy() && !controlBar.controls.bracket && cli.active == null },
                { withMediaPermissions(false,it) }, { bursts.hold(sessionId) }, bursts::release)
        }
        captureRow.addView(mediaButton,LinearLayout.LayoutParams(captureSize,captureSize).apply { marginStart=dp(12); marginEnd=dp(12) })
        cameraShortcut=IconButton(this,R.drawable.ic_camera_select,"Choose camera",filled=true) { selectCamera() }
        snapshotButton=IconButton(this,R.drawable.ic_snapshot,"Take a photo while recording",filled=true) { takeSnapshot() }.apply { visibility=View.GONE }
        val cameraSlot=FrameLayout(this).apply {
            addView(cameraShortcut,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER))
            addView(snapshotButton,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER))
        }
        captureRow.addView(cameraSlot,LinearLayout.LayoutParams(0,captureSize,1f))

        val modeRow=FrameLayout(this)
        bottomBar.addView(modeRow,lp(height=48))
        modeControls=row().apply { gravity=Gravity.CENTER; isBaselineAligned=false }
        photoModeButton=button("Photo") { selectMode(false) }
        videoModeButton=button("Video") { selectMode(true) }
        multiButton=button("Multi · P") { openConcurrent(false) }
        multiVideoButton=button("Multi · V") { openConcurrent(true) }
        listOf(photoModeButton,videoModeButton,multiButton,multiVideoButton).forEach {
            it.background=cameraChrome(Color.TRANSPARENT)
            it.textSize=12f; it.gravity=Gravity.CENTER; it.includeFontPadding=false; it.setSingleLine(true)
            it.setPadding(dp(4),0,dp(4),0)
            modeControls.addView(it,LinearLayout.LayoutParams(0,dp(48),1f))
        }
        modeRow.addView(modeControls,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
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
            if(recorder.trigger(id)) { incidents.marked(id,readings.last); toast("Saving events in 5 seconds. Bagging the evidence.") }
        }.apply { setTextColor(Color.WHITE); background=cameraChrome(Color.TRANSPARENT); contentDescription="Mark: save the previous 10 and next 5 seconds to a ZIP" }
        reportButton.minHeight=dp(48); reportButton.minimumHeight=dp(48)
        reportButton.contentDescription="Save events: previous 10 and next 5 seconds to a ZIP"
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
    private fun selectCamera() {
        if (cli.active != null || recordingVideo || mediaBusy() || closing) return
        val ids=cameraIds.sortedWith(CameraLabel.idOrder)
        val choices=ids.map { id ->
            val endpoint=cameraEndpoints[id]
            val name=endpoint?.let { listOfNotNull(CameraLabel.facing(it.role,it.facing),CameraLabel.lens(it.role),
                CameraLabel.angle(it.role,it.equivalentFocalMm)).joinToString(" · ") }?.takeIf { it.isNotEmpty() } ?: "Camera"
            LiveChoiceSheet.Choice("Service · ID $id",name)
        }
        LiveChoiceSheet.show(this,"Camera",choices,ids.indexOf(cameraId)) { index ->
            if (recordingVideo || !resumed || cli.active != null || mediaBusy() || closing) return@show
            val chosen=ids[index]
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
        if (recordingVideo || stoppingRecording || closing || pendingPermissionAction != null ||
            mediaBusy() || !ready || videoMode==video) return
        pendingPermissionAction=null
        videoMode=video
        resetModeSettings()
        restartCamera()
        updateMediaControls()
    }
    /**
     * "Camera · 0 (Wide · Rear)". The roles come from the shared enumeration so LIVE names a lens exactly as
     * BENCHMARK, PROBE and the run history do; an id the resolver did not reach still gets the short label.
     */
    private fun cameraLabel(id:String):String {
        if(id.isEmpty()) return "No camera"
        return cameraEndpoints[id]?.let(CameraLabel::full) ?: CameraLabel.short(id)
    }
    internal fun updateCameraChoices() {
        if(cameraId.isNotEmpty()) {
            val presets=zoomPresets(zoomRange(manager,cameraId))
            if(zoomRatio !in presets) zoomRatio=presets.minByOrNull { kotlin.math.abs(it-zoomRatio) } ?: 1f
        }
        engineButton.text=engineName
        engineButton.contentDescription="Current: $engineName; tap to switch to ${if(engineName=="Camera2") "CameraX" else "Camera2"}"
        cameraShortcut.contentDescription="Choose camera, current: ${cameraLabel(cameraId)}"
        cameraShortcut.tooltipText=cameraShortcut.contentDescription
        zoomControl.setChoices(if(cameraId.isEmpty()) listOf(1f) else zoomPresets(zoomRange(manager,cameraId)),zoomRatio)
    }
    internal fun updateMediaControls() {
        if (!resumed || paused || closing || engine == null) liveIndicator.bind(false)
        val idle=!recordingVideo && bursts.run==null
        cli.setUiBusy(liveCli, recordingVideo || stoppingRecording || pendingPermissionAction != null || mediaBusy())
        listOf(photoModeButton,videoModeButton).forEachIndexed { index, button ->
            val selected=(index==1)==videoMode
            button.isSelected=selected
            button.isEnabled=ready && idle && !mediaBusy() && pendingPermissionAction == null
            button.setTextColor(if(selected) Look.onDark else Look.onDarkMuted)
            button.setTypeface(null,if(selected) Typeface.BOLD else Typeface.NORMAL)
            button.contentDescription=if(index==0) "Photo mode: save selected JPEG and RAW outputs" else "Video mode with audio"
            ViewCompat.setStateDescription(button,if(selected) "Selected" else null)
        }
        pausedOverlay.visibility=if(paused) View.VISIBLE else View.GONE
        modeControls.visibility=if(recordingVideo) View.INVISIBLE else View.VISIBLE
        recordingTime.visibility=if(recordingVideo) View.VISIBLE else View.GONE
        mediaButton.setCaptureState(videoMode,recordingVideo,bursts.run != null,controlBar.controls.bracket,bursts.label)
        val cliProgress = cli.active?.takeIf { it.command in setOf("burst", "bracket", "capture", "events") }?.let { command ->
            val record = cli.store.read(command.id)
            val progress = record?.optJSONObject("progress")
            if (progress != null) "CLI · ${progress.optInt("saved")}/${progress.optInt("total")} saved · ${progress.optString("phase")}" else "CLI · ${record?.optString("state") ?: "preparing"}"
        }
        captureFeedback.bind(cliProgress ?: bursts.label,false)
        if(stoppingRecording) {
            mediaButton.contentDescription="Saving video"
            ViewCompat.setStateDescription(mediaButton,"Saving")
        }
        mediaButton.isEnabled=(ready || recordingVideo || bursts.run != null) && !stoppingRecording
        if (bursts.fusing && controlBar.controls.bracket) mediaButton.isEnabled=false
        if (!videoMode && pipUi.selected == null && streamSettings[streamKey()]?.canCapture == false) {
            mediaButton.isEnabled = false
            mediaButton.contentDescription = "Photo output is off: enable JPEG or RAW in Live streams"
        }
        engineButton.isEnabled=idle
        cameraShortcut.isEnabled=idle && cameraId.isNotEmpty()
        val snapshot=(engine as? MediaCapture)?.snapshot ?: SnapshotStatus.NONE
        cameraShortcut.visibility=if(recordingVideo) View.GONE else View.VISIBLE
        snapshotButton.visibility=if(recordingVideo) View.VISIBLE else View.GONE
        snapshotButton.isEnabled=cli.active == null
        snapshotButton.alpha=if(snapshot.canCapture && cli.active == null) 1f else 0.4f
        snapshotButton.contentDescription=snapshot.reason ?: when(snapshot.phase) {
            SnapshotStatus.Phase.BUSY -> "Saving snapshot"
            else -> "Take a photo while recording"
        }
        // Zoom stays live while recording (#174): the engine changes the recording request in place.
        // The engine reports "REC" as not-ready, so a running recording counts as ready here, as for the shutter.
        zoomControl.isEnabled=(ready || recordingVideo) && !stoppingRecording && !bursts.controlsLocked
        controlBar.bind(videoMode,(ready || recordingVideo) && !stoppingRecording && cli.active==null && !bursts.controlsLocked,
            pipUi.selected != null || pipUi.busy)
        galleryButton.isEnabled=idle
        labButton.isEnabled=!recordingVideo && !stoppingRecording && !closing && !mediaBusy()
        liveIndicator.setSizesEnabled(labButton.isEnabled && cli.active == null && pendingPermissionAction == null && cameraId.isNotEmpty())
        listOf(multiButton,multiVideoButton).forEach {
            it.isEnabled = labButton.isEnabled && cli.active == null && pendingPermissionAction == null
            it.setTextColor(Look.onDarkMuted)
            it.alpha = if (it.isEnabled) 1f else 0.4f
        }
        multiButton.contentDescription = "Multi photo mode"
        multiVideoButton.contentDescription = "Multi video mode"
        physicalPipButton.isEnabled = multiButton.isEnabled && pipSources().isNotEmpty()
        physicalPipButton.alpha = if (physicalPipButton.isEnabled) 1f else .4f
        physicalPipButton.contentDescription = "Choose PIP camera"
        widgets.highlight(physicalPipButton,pipUi.selected != null)
        ViewCompat.setStateDescription(physicalPipButton,if (pipUi.selected != null) "On" else "Off")
        if (pipUi.busy) { mediaButton.isEnabled=false; physicalPipButton.isEnabled=false; engineButton.isEnabled=false; cameraShortcut.isEnabled=false }
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
        engineName=name; resetModeSettings(); updateCameraChoices(); restartCamera()
    }
    internal fun resetModeSettings() {
        if (::callbackGraph.isInitialized) showCallbacks(false)
        pendingPip = null
        pipUi.resetMode()
        streamSettings.clear(); goodStreams.clear(); streamState.clear()
        zoomRatio = 1f
        resetControls()
    }
    /** Locks, EV and flash start over for every camera and engine; the new session opens with the defaults. */
    internal fun resetControls() {
        controlBar.reset(if(cameraId.isEmpty()) LiveControlSupport.NONE else liveControlSupport(manager,cameraId))
        refreshManualSupport()
        manualPanel.reset(manualCapabilities)
    }
    private fun refreshManualSupport() {
        val streams = streamSettings[streamKey()]
        val fps = if (videoMode) maxOf(streams?.video?.fps ?: 30, streams?.fps?.max ?: 30) else streams?.fps?.max ?: 30
        manualCapabilities = if (engineName != "Camera2") ManualSupport(camera2 = false)
            else try { manualSupport(manager.getCameraCharacteristics(cameraId), fps) } catch (_: Exception) { ManualSupport() }
        controlBar.setManualAvailable(manualCapabilities.camera2)
    }
    internal fun streamKey() = liveStreamSettingsKey(cameraId, engineName)
    private fun openLab() {
        openAfterClose("workbench_opened") {
            streamSettingsIntent(WorkbenchActivity::class.java)
        }
    }
    private fun openConcurrent(video: Boolean) {
        if (cli.active != null || recordingVideo || stoppingRecording || closing ||
            pendingPermissionAction != null || mediaBusy()) return
        if (engineName == "CameraX") {
            LiveChoiceSheet.show(this,"Multi",listOf(LiveChoiceSheet.Choice("Camera2","Open Multi"))) {
                openConcurrentCamera2(video)
            }
        } else openConcurrentCamera2(video)
    }
    private fun openConcurrentCamera2(video: Boolean) {
        if (cli.active != null || recordingVideo || stoppingRecording || closing ||
            pendingPermissionAction != null || mediaBusy()) return
        resetModeSettings()
        openAfterClose("concurrent_opened") { Intent(this, ConcurrentCameraActivity::class.java).putExtra(ConcurrentCameraActivity.EXTRA_VIDEO,video) }
    }
    private fun openLiveStreams() {
        if (cli.active != null || recordingVideo || stoppingRecording || closing ||
            pendingPermissionAction != null || mediaBusy() || cameraId.isEmpty()) return
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
                .putExtra(LiveStreamsActivity.EXTRA_STATUS, streamState[streamKey()].orEmpty())
    /** Wait for close(done) before handing the camera to Lab/CTS/Benchmark; onStop respects closing. */
    private fun openAfterClose(reason: String, intent: () -> Intent) {
        if (closing) return
        bursts.close()
        recorder.finish(reason)?.let(incidents::export)
        showCallbacks(false)
        val old = engine; engine = null; closing = true; ready = false
        setStatus("Closing camera…", false)
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
    private fun pipSources(): List<PipSource> = if (engineName == "CameraX")
        (engine as? CameraXEngine)?.pipSources.orEmpty() else physicalPip.supported(cameraId)

    internal fun mediaBusy() = pipUi.busy || (engine as? MediaCapture)?.mediaBusy == true || bursts.run != null
    /** One snapshot at a time; failures leave recording active and unsupported cameras explain why. */
    private fun takeSnapshot() {
        if (cli.active != null) return
        val camera=engine as? MediaCapture ?: return
        val state=camera.snapshot
        when {
            state.phase==SnapshotStatus.Phase.UNSUPPORTED -> toast(state.reason.orEmpty())
            state.canCapture -> { showNotice("Capturing…"); camera.captureSnapshot(); updateMediaControls() }
        }
    }
    /** Ends the recording and says so on both stop buttons, whichever one the user reached. */
    private fun stopRecording() {
        if (!recordingVideo || stoppingRecording) return
        stoppingRecording = true
        recordingTime.text = "Saving…"
        updateMediaControls()
        (engine as? MediaCapture)?.stopRecording()
    }
    internal fun showCallbacks(show: Boolean) {
        if (show && ::manualPanel.isInitialized) manualPanel.close()
        callbackGraph.reset()
        callbackGraph.visibility = if (show) View.VISIBLE else View.GONE
        metrics.visibility = if (show) View.GONE else View.VISIBLE
        graphBack.isEnabled = show
        graphButton.contentDescription = if (show) "Hide callback timing" else "Show callback timing"
        widgets.highlight(graphButton,show)
        ViewCompat.setStateDescription(graphButton, if (show) "Shown" else "Hidden")
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
