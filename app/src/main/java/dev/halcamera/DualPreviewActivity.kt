package dev.halcamera

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.TextureView
import android.view.View
import android.view.MotionEvent
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.halcamera.camera.CameraLabel
import dev.halcamera.camera.DualPreviewPlanner
import dev.halcamera.camera.DualPreviewSession
import dev.halcamera.camera.DualCameraSession
import dev.halcamera.camera.DualCameraXSession
import dev.halcamera.camera.LiveSize
import dev.halcamera.camera.LogicalMultiCamera
import dev.halcamera.camera.PhysicalLens
import dev.halcamera.camera.PhysicalOutputStats
import dev.halcamera.camera.fitPreview
import dev.halcamera.camera.physicalTimestampSkewNs
import dev.halcamera.camera.readLogicalMultiCameras
import dev.halcamera.ui.Look
import dev.halcamera.ui.ShutterButton
import dev.halcamera.ui.CameraWidgets
import dev.halcamera.ui.IconButton
import dev.halcamera.ui.LiveIndicator
import dev.halcamera.ui.RecentMediaButton
import dev.halcamera.ui.ResultCallbackGraph
import dev.halcamera.camera.RecentMediaThumbnail
import dev.halcamera.telemetry.FlightRecorder
import dev.halcamera.telemetry.Telemetry
import dev.halcamera.telemetry.IncidentExporter
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import java.util.Locale
import java.util.concurrent.Executors
import dev.halcamera.camera.*
import dev.halcamera.ui.ExpandingZoomControl
import dev.halcamera.ui.LiveControlBar
import dev.halcamera.ui.ManualControlPanel
import dev.halcamera.ui.FocusRing

/**
 * Live Dual modes: a main physical camera and a draggable inset from another physical camera.
 *
 * LIVE has already closed its camera before this screen opened, so it owns the logical camera
 * while it is visible and closes it in onStop; LIVE reopens its own preview when the user returns.
 */
class DualPreviewActivity : ComponentActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val manager by lazy { getSystemService(CameraManager::class.java) }
    private lateinit var body: LinearLayout
    private var cameras: List<LogicalMultiCamera> = emptyList()
    private var logicalId: String? = null
    private var pair: Pair<String, String>? = null
    private var session: DualCameraSession? = null
    private var engineName = "Camera2"
    private lateinit var engineButton: Button
    private var closing = false
    private var started = false
    private var streamingSize: LiveSize? = null
    private var status = ""
    private var stats: Pair<PhysicalOutputStats, PhysicalOutputStats>? = null
    private var lastSkewNs: Long? = null
    private val views = arrayOfNulls<TextureView>(2)
    private val textures = arrayOfNulls<SurfaceTexture>(2)
    private var statusText: TextView? = null
    private var retryButton: View? = null
    private var failed = false
    private var videoMode = false
    private var recording = false
    private var recordPending = false
    private var photoPending = false
    private var mainControls: DualMainControls? = null
    private lateinit var controlBar: LiveControlBar
    private lateinit var manualPanel: ManualControlPanel
    private lateinit var zoomControl: ExpandingZoomControl
    private var zoomRatio = 1f
    private var zoomAvailable = false
    private var recordingSince = 0L
    private var recordButton: ShutterButton? = null
    private var recordLabel: TextView? = null
    private var captureRow: LinearLayout? = null
    private val modeButtons = mutableListOf<Button>()
    private val pairButtons = mutableListOf<Button>()
    private val retiredTextures = mutableListOf<SurfaceTexture>()
    private val positionPrefs by lazy { getSharedPreferences("dual_preview", MODE_PRIVATE) }
    private var pipX = 1f
    private var pipY = 0.12f
    private lateinit var root: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var metricsText: TextView
    private lateinit var liveIndicator: LiveIndicator
    private lateinit var callbackGraph: ResultCallbackGraph
    private lateinit var galleryButton: RecentMediaButton
    private lateinit var labButton: Button
    private lateinit var reportButton: Button
    private lateinit var recentMedia: RecentMediaThumbnail
    private var modeRow: LinearLayout? = null
    private val recorder = FlightRecorder(SystemClock::elapsedRealtimeNanos)
    private val telemetry = Telemetry(recorder)
    private var sessionId = "dual"
    private var lastPreviewMs = 0L
    private var pipStage: FrameLayout? = null
    private var pipView: View? = null
    private var pipSelector: Button? = null
    private val widgets by lazy { CameraWidgets(this) }
    private val tick = object : Runnable {
        override fun run() {
            refreshInfo()
            recorder.finish()?.let(::exportIncident)
            val remaining = recorder.remainingNs()
            reportButton.text = remaining?.let { "Saving in ${String.format(Locale.US, "%.1f", it / 1e9)}s" } ?: "Save Events · ZIP"
            reportButton.isEnabled = remaining == null && streamingSize != null && !failed && !closing
            main.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false
        }
        videoMode = savedInstanceState?.getBoolean(EXTRA_VIDEO) ?: intent.getBooleanExtra(EXTRA_VIDEO, false)
        engineName = (savedInstanceState?.getString(EXTRA_ENGINE) ?: intent.getStringExtra(EXTRA_ENGINE))
            ?.takeIf { it == "CameraX" } ?: "Camera2"
        pipX = positionPrefs.getFloat("pip_x", 1f)
        pipY = positionPrefs.getFloat("pip_y", 0.12f)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (manualPanel.close()) return
                if (callbackGraph.visibility == View.VISIBLE) { showCallbacks(false); return }
                if (!photoPending) leave(videoMode)
            }
        })
        logicalId = savedInstanceState?.getString(KEY_LOGICAL)
        pair = savedInstanceState?.getStringArray(KEY_PAIR)?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        showPage()
        recentMedia = RecentMediaThumbnail(this) { bitmap, video -> galleryButton.setThumbnail(bitmap, video) }
        message("Loading camera information…")
        io.execute {
            val read = runCatching { readLogicalMultiCameras(manager) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                read.fold({ cameras = it; render(); startIfReady() }, { message("Could not load camera information. ${it.message.orEmpty()}") })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        recentMedia.start()
        main.post(tick)
        startIfReady()
    }

    override fun onStop() {
        started = false
        root.keepScreenOn = false
        recentMedia.stop()
        recorder.finish("screen_stopped")?.let(::exportIncident)
        main.removeCallbacks(tick)
        closeSession {}
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_LOGICAL, logicalId)
        outState.putBoolean(EXTRA_VIDEO, videoMode)
        outState.putString(EXTRA_ENGINE, engineName)
        pair?.let { outState.putStringArray(KEY_PAIR, arrayOf(it.first, it.second)) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        io.shutdown()
        recentMedia.close()
        super.onDestroy()
    }

    private fun chromeButton(label: String, action: () -> Unit) = widgets.button(label, action).apply {
        background = widgets.chrome(Color.TRANSPARENT)
        setPadding(dp(8), 0, dp(8), 0)
        isSingleLine = true
        minWidth = dp(48); minimumWidth = dp(48)
    }

    private fun showPage() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(body, FrameLayout.LayoutParams(-1, -1))
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(140, 0, 0, 0), Color.TRANSPARENT))
        }
        val controls = Look.row(this)
        engineButton = chromeButton(engineName) {
            if (!busy()) {
                engineName = if (engineName == "Camera2") "CameraX" else "Camera2"
                restart(); updateControls()
            }
        }
        controls.addView(FrameLayout(this).apply {
            addView(engineButton, FrameLayout.LayoutParams(-2, dp(48)))
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        controlBar = LiveControlBar(this, object : LiveControlBar.Host {
            override fun controlsChanged(controls: LiveControls) { session?.setControls(controls) }
            override fun notice(text: String) { Toast.makeText(this@DualPreviewActivity, text, Toast.LENGTH_LONG).show() }
            override fun manualRequested() { showCallbacks(false); manualPanel.toggle() }
        })
        manualPanel = ManualControlPanel(this, { controlBar.setManual(it) }, { Toast.makeText(this, it, Toast.LENGTH_LONG).show() })
        controls.addView(controlBar.handle, LinearLayout.LayoutParams(dp(48), dp(48)))
        val trailing = Look.row(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        trailing.addView(chromeButton("Callback") {
            showCallbacks(callbackGraph.visibility != View.VISIBLE)
        }, LinearLayout.LayoutParams(-2, dp(48)))
        labButton = chromeButton("Lab") { openTool(WorkbenchActivity::class.java) }
        trailing.addView(labButton, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(4) })
        controls.addView(trailing, LinearLayout.LayoutParams(0, dp(48), 1f))
        topBar.addView(controls)
        liveIndicator = LiveIndicator(this).apply { onSizesClick = { openTool(LiveStreamsActivity::class.java) } }
        topBar.addView(liveIndicator)
        topBar.addView(controlBar.view, lp(4))
        statusText = Look.text(this, "", 12, Look.onDark).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }.also { topBar.addView(it, lp(4)) }
        retryButton = chromeButton("Retry") { if (!busy()) restart() }.apply { visibility = View.GONE }
            .also { topBar.addView(it, lp(4)) }
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        topBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            pipStage?.let { stage -> pipView?.let { positionPip(stage, it) } }
        }

        val captureChrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(180, 0, 0, 0), Color.TRANSPARENT))
        }
        bottomBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        callbackGraph = ResultCallbackGraph(this).apply { visibility = View.GONE }
        bottomBar.addView(callbackGraph, lp(0))
        metricsText = Look.text(this, "FPS — / —\nPhys — / —", 11, Look.onDark, mono = true).apply {
            gravity = Gravity.CENTER
            setShadowLayer(dp(2).toFloat(), 0f, 0f, Color.BLACK)
            setOnClickListener { showInfo() }
        }
        bottomBar.addView(metricsText, lp(0))
        zoomControl = ExpandingZoomControl(this) { ratio ->
            zoomRatio = ratio; session?.setZoom(ratio)
        }
        bottomBar.addView(zoomControl.viewport(), LinearLayout.LayoutParams(-2, dp(48)))
        captureRow = Look.row(this).apply { gravity = Gravity.CENTER_VERTICAL }
        galleryButton = RecentMediaButton(this) { openTool(GalleryActivity::class.java) }
        captureRow!!.addView(FrameLayout(this).apply {
            addView(galleryButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        }, LinearLayout.LayoutParams(0, dp(64), 1f))
        recordButton = ShutterButton(this).apply { setOnClickListener { if (videoMode) toggleRecording() else takePhoto() } }
        captureRow!!.addView(recordButton, LinearLayout.LayoutParams(dp(64), dp(64)).apply {
            marginStart = dp(12); marginEnd = dp(12)
        })
        val cameraButton = IconButton(this, R.drawable.ic_camera_select, "듀얼 카메라 선택", dark = true, filled = true) { choosePair() }
        pairButtons += cameraButton
        captureRow!!.addView(FrameLayout(this).apply {
            addView(cameraButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        }, LinearLayout.LayoutParams(0, dp(64), 1f))
        bottomBar.addView(captureRow, lp(4))
        val modeFrame = FrameLayout(this)
        modeRow = Look.row(this).apply { gravity = Gravity.CENTER }
        listOf("Photo", "Video", "Dual · P", "Dual · V").forEachIndexed { index, label ->
            val button = chromeButton(label) {
                if (index < 2) leave(index == 1) else if (videoMode != (index == 3)) {
                    videoMode = index == 3; restart(); updateControls()
                }
            }.apply { setPadding(dp(4), 0, dp(4), 0) }
            modeButtons += button
            modeRow!!.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        modeFrame.addView(modeRow, FrameLayout.LayoutParams(-1, -1))
        recordLabel = Look.text(this, "", 14, Look.statusFail, mono = true).apply {
            gravity = Gravity.CENTER; visibility = View.GONE
        }.also { modeFrame.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        bottomBar.addView(modeFrame, LinearLayout.LayoutParams(-1, dp(48)))
        captureChrome.addView(bottomBar)
        val footer = Look.row(this)
        reportButton = chromeButton("Save Events · ZIP") {
            if (recorder.trigger("dual_${System.currentTimeMillis()}"))
                Toast.makeText(this, "Saving events in 5 seconds", Toast.LENGTH_SHORT).show()
        }
        footer.addView(reportButton, LinearLayout.LayoutParams(-1, dp(48)))
        captureChrome.addView(footer)
        root.addView(captureChrome, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        captureChrome.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            pipStage?.let { stage -> pipView?.let { positionPip(stage, it) } }
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topBar.setPadding(dp(12) + bars.left, dp(8) + bars.top, dp(12) + bars.right, dp(10))
            bottomBar.setPadding(dp(16) + bars.left, dp(4), dp(16) + bars.right, 0)
            footer.setPadding(dp(16) + bars.left, 0, dp(16) + bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        root.addView(manualPanel.view, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(12); rightMargin = dp(12)
        })
        captureChrome.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val params = manualPanel.view.layoutParams as FrameLayout.LayoutParams
            val margin = captureChrome.height + dp(8)
            if (params.bottomMargin != margin) { params.bottomMargin = margin; manualPanel.view.layoutParams = params }
        }
        updateControls()
    }

    private fun header() {
        body.removeAllViews()
        views.fill(null); textures.fill(null)
    }
    private fun message(text: String) {
        header()
        body.addView(Look.text(this, text, 15, Look.onDark).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(140), dp(24), dp(260))
        }, LinearLayout.LayoutParams(-1, -1))
    }

    private fun render() {
        if (Build.VERSION.SDK_INT < 28) return message("Requires Android 9 (API 28) or later.")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            return message("Camera permission required. Allow access in Live, then reopen Dual.")
        val candidates = DualPreviewPlanner.candidates(cameras)
        if (candidates.isEmpty()) return message(unsupportedReport())
        val camera = candidates.firstOrNull { it.logicalId == logicalId } ?: candidates.first()
        if (logicalId != camera.logicalId) { logicalId = camera.logicalId; pair = null }
        val selected = pair?.takeIf { (a, b) -> a != b && camera.physical.any { it.id == a } && camera.physical.any { it.id == b } }
            ?: DualPreviewPlanner.defaultPair(camera)
        pair = selected
        mainControls = if (engineName == "Camera2" && selected != null) runCatching {
            DualMainControls(selected.first, manager.getCameraCharacteristics(camera.logicalId), manager.getCameraCharacteristics(selected.first))
        }.getOrNull() else null
        controlBar.reset(mainControls?.support ?: LiveControlSupport.NONE)
        controlBar.setManualAvailable(mainControls != null)
        manualPanel.reset(mainControls?.manual ?: ManualSupport(camera2 = false))
        zoomRatio = 1f
        zoomAvailable = mainControls != null
        zoomControl.setChoices(zoomPresets(1f to (mainControls?.maxZoom ?: 1f)), zoomRatio)
        header()

        val stage = FrameLayout(this).apply { clipChildren = true }
        stage.addView(previewColumn(0), FrameLayout.LayoutParams(-1, -1))
        stage.addView(FocusRing(this, { object : TouchMetering {
            override fun meterAt(x: Float, y: Float, exposure: Boolean, feedback: (TouchPhase) -> Unit): Boolean {
                val point = views[0]?.naturalPoint(x, y) ?: return false
                return (session as? DualPreviewSession)?.meterAt(point.first, point.second, exposure, feedback) ?: false
            }
        } }, { controlBar.setAeLock(it) }), FrameLayout.LayoutParams(-1, -1))
        val pip = previewColumn(1).apply {
            background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(16).toFloat(); setStroke(dp(1), Look.cameraOutline) }
            clipToOutline = true
            elevation = dp(8).toFloat()
            contentDescription = "Camera 2 서브 프리뷰. 드래그하여 이동"
        }
        stage.addView(pip, FrameLayout.LayoutParams(dp(112), dp(176)))
        pipStage = stage; pipView = pip
        stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            positionPip(stage, pip)
            stage.post { positionPip(stage, pip) }
        }
        installDrag(stage, pip)
        body.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        refreshInfo()
    }

    private fun previewColumn(index: Int) = FrameLayout(this).apply {
        val id = pair?.let { if (index == 0) it.first else it.second }
        val lens = cameras.firstOrNull { it.logicalId == logicalId }?.physical?.firstOrNull { it.id == id }
        val selector = Look.ghostButton(this@DualPreviewActivity, "${if (index == 0) "1" else "2"} · ${lens?.let(::lensLabel) ?: "—"} ▾", dark = true) {
            selectLens(index)
        }.apply {
            textSize = 11f; isSingleLine = true; setPadding(dp(4), 0, dp(4), 0)
            setTextColor(Look.onDark); setBackgroundColor(Look.cameraGlass)
            contentDescription = "Camera ${index + 1}: physical ${lens?.let(::lensLabel)} 선택"
        }
        if (index == 1) pipSelector = selector

        val view = TextureView(this@DualPreviewActivity)
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                if (views[index] !== view) return
                textures[index] = texture
                startIfReady()
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                streamingSize?.let { view.fitPreview(Size(it.width, it.height)) }
            }
            /** The texture outlives the session: the session closes before this view's surface goes away. */
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                if (views[index] === view) textures[index] = null
                if (session != null || closing) {
                    retiredTextures += texture
                    closeSession {}
                } else texture.release()
                return false
            }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                if (views[index] === view && index == 0) lastPreviewMs = SystemClock.elapsedRealtime()
                if (views[index] === view && session != null) stats?.let { (a, b) -> (if (index == 0) a else b).frame(SystemClock.elapsedRealtime()) }
                if (views[index] === view && session != null) recorder.record(sessionId, "preview_available", sensorNs = texture.timestamp,
                    values = mapOf("stream" to if (index == 0) "preview_main" else "preview_sub"))
            }
        }
        views[index] = view
        addView(view, FrameLayout.LayoutParams(-1, -1))
        if (index == 1) addView(selector, FrameLayout.LayoutParams(-1, dp(48), Gravity.TOP or Gravity.START))

    }

    private fun startIfReady() {
        if (Build.VERSION.SDK_INT < 28) return
        if (!started || closing || session != null) return
        val a = textures[0] ?: return
        val b = textures[1] ?: return
        val camera = cameras.firstOrNull { it.logicalId == logicalId } ?: return
        val (first, second) = pair ?: return
        when (val result = DualPreviewPlanner.plan(camera, first, second, Build.VERSION.SDK_INT)) {
            is DualPreviewPlanner.Result.Refused -> {
                // Nothing streams for a refused pair, so the previous pair's counts must not stay on screen.
                streamingSize = null
                stats = null
                lastSkewNs = null
                refreshInfo()
                setStatus("Unavailable: ${result.reason.label}")
            }
            is DualPreviewPlanner.Result.Ready -> {
                failed = false
                retryButton?.visibility = View.GONE
                setStatus("Opening…")
                streamingSize = null
                stats = PhysicalOutputStats(first) to PhysicalOutputStats(second)
                lastSkewNs = null
                sessionId = "dual_${java.util.UUID.randomUUID()}"
                session = if (engineName == "CameraX") DualCameraXSession(applicationContext, this,
                    result.plan, a to b, videoMode, sessionListener, telemetry, sessionId)
                else DualPreviewSession(manager, result.plan, a to b, sessionListener,
                    if (videoMode) applicationContext else null, telemetry, sessionId, applicationContext, mainControls)
                session?.start()
            }
        }
    }

    private val sessionListener = object : DualPreviewSession.Listener {
        override fun onZoomRange(range: Pair<Float, Float>) {
            zoomAvailable = true
            zoomControl.setChoices(zoomPresets(range), zoomRatio)
            updateControls()
        }
        override fun onStatus(message: String) = setStatus(message)
        override fun onStreaming(size: LiveSize) {
            streamingSize = size
            liveIndicator.bindSizes(mapOf("preview" to size.toString()))
            views.forEach { it?.fitPreview(Size(size.width, size.height)) }
            updateControls()
        }
        override fun onResult(physical: Map<String, Long?>) {
            val (a, b) = stats ?: return
            val ta = physical[a.physicalId]
            val tb = physical[b.physicalId]
            a.result(ta); b.result(tb)
            physicalTimestampSkewNs(ta, tb)?.let { lastSkewNs = it }
        }
        override fun onFailed(message: String) {
            failed = true
            setStatus("Stopped · $message")
            retryButton?.visibility = View.VISIBLE
            refreshInfo()
            recordPending = false
            photoPending = false
            closeSession { updateControls() }
        }
        override fun onRecording() {
            recordPending = false; recording = true
            recordingSince = SystemClock.elapsedRealtime()
            updateControls()
        }
        override fun onVideoSaved(result: Result<Int>) {
            result.fold({ Toast.makeText(this@DualPreviewActivity, "MP4 2개 저장 완료", Toast.LENGTH_SHORT).show() },
                { Toast.makeText(this@DualPreviewActivity, "저장 실패: ${it.message}", Toast.LENGTH_LONG).show() })
        }
        override fun onPhotoSaved(result: Result<Int>) {
            photoPending = false
            result.fold({ Toast.makeText(this@DualPreviewActivity, "두 센서 사진 ${it}개 저장 완료", Toast.LENGTH_SHORT).show() },
                { Toast.makeText(this@DualPreviewActivity, "동시 사진 저장 실패: ${it.message}", Toast.LENGTH_LONG).show() })
            updateControls()
        }
    }

    /** Closes the whole device; [then] runs once it is closed, so the next pair never shares buffers with this one. */
    private fun closeSession(then: () -> Unit) {
        val old = session ?: return then()
        session = null
        closing = true
        old.close {
            closing = false
            recording = false; recordPending = false
            retiredTextures.forEach { it.release() }; retiredTextures.clear()
            then()
            updateControls()
            if (!failed) startIfReady()
        }
    }

    private fun restart() {
        if (busy()) return
        failed = false
        streamingSize = null; stats = null; lastSkewNs = null
        setStatus("Restarting…")
        retryButton?.visibility = View.GONE
        closeSession { if (!isDestroyed) render() }
    }

    private fun selectLens(index: Int) {
        if (busy()) return
        val current = pair ?: return
        val camera = cameras.firstOrNull { it.logicalId == logicalId } ?: return
        val other = if (index == 0) current.second else current.first
        val options = camera.physical.filter { it.id != other }
        val selected = if (index == 0) current.first else current.second
        AlertDialog.Builder(this, R.style.LabDialogTheme)
            .setTitle("Camera ${index + 1} · Physical ID")
            .setSingleChoiceItems(options.map(::lensLabel).toTypedArray(), options.indexOfFirst { it.id == selected }) { dialog, position ->
                val id = options[position].id
                if (!busy() && id != selected) {
                    pair = if (index == 0) id to current.second else current.first to id
                    restart()
                }
                dialog.dismiss()
            }.setNegativeButton("취소", null).show()
    }

    private fun setStatus(text: String) {
        status = text
        statusText?.text = if (failed || streamingSize == null) text else ""
        statusText?.visibility = if (failed || streamingSize == null) View.VISIBLE else View.GONE
    }

    private fun refreshInfo() {
        val current = stats
        fun fps(value: PhysicalOutputStats?) = value?.fps()?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
        metricsText.text = "FPS ${fps(current?.first)} / ${fps(current?.second)}\n" +
            if (photoPending) "Capturing…"
            else "Phys ${pair?.first ?: "—"} / ${pair?.second ?: "—"}" + if (videoMode) " · Silent" else ""
        liveIndicator.bind(started && !closing && !failed && streamingSize != null && SystemClock.elapsedRealtime() - lastPreviewMs < 1500)
        val events = recorder.snapshot()
        val latest = events.lastOrNull { it.session == sessionId && it.kind == "capture_result" }
        val primary = events.lastOrNull { it.session == sessionId && it.kind == "dual_main_result" }
        manualPanel.bind(controlBar.controls.manual, !busy(), mainControls?.manual ?: ManualSupport(camera2 = false), primary, SystemClock.elapsedRealtimeNanos())
        liveIndicator.bindStabilization(dev.halcamera.camera.LiveEisStatus(
            (latest?.values?.get("videoStabilization") as? Number)?.toInt()), recording)
        if (callbackGraph.visibility == View.VISIBLE)
            callbackGraph.update(events, sessionId, SystemClock.elapsedRealtimeNanos(), telemetry.sessions[sessionId].orEmpty())
        if (recording) {
            val seconds = (SystemClock.elapsedRealtime() - recordingSince) / 1000
            recordLabel?.text = "REC ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} · MP4 × 2"
        }
    }

    private fun outputReport(lens: PhysicalLens?, id: String, s: PhysicalOutputStats?): String = listOf(
        "ID $id · ${lens?.let(::lensLabel)?.substringAfter(" · ", "") ?: ""}".trimEnd(' ', '·'),
        "크기 ${streamingSize ?: "—"}",
        "${if (failed) "Last " else ""}${s?.fps()?.let { String.format(Locale.US, "%.1f fps", it) } ?: "— fps"} · ${s?.delivered ?: 0} frames",
        "Meta ${s?.metadata ?: 0} · Missing ${s?.missing ?: 0}"
    ).joinToString("\n")

    private fun lensLabel(lens: PhysicalLens): String {
        val name = CameraLabel.lens(lens.role) ?: lens.equivalentFocalMm?.let { String.format(Locale.US, "%.0f mm", it) } ?: "?"
        return "${lens.id} · $name"
    }

    private fun unsupportedReport(): String {
        val rear = cameras.filter { it.facing == CameraLabel.FACING_BACK }
        if (rear.isEmpty()) return "Unavailable: no rear camera."
        return "Dual preview is unavailable on this device.\n" + rear.joinToString("\n") { c ->
            "${CameraLabel.short(c.logicalId)}: ${DualPreviewPlanner.refusal(c, Build.VERSION.SDK_INT)?.label ?: "Supported"}"
        }
    }

    private fun copyReport() {
        val camera = cameras.firstOrNull { it.logicalId == logicalId } ?: return
        val report = buildString {
            appendLine("HAL CAM Dual Preview")
            appendLine("logical ${camera.logicalId} · sync ${DualPreviewPlanner.syncLabel(camera.syncType)} · API ${Build.VERSION.SDK_INT}")
            stats?.let { (a, b) ->
                appendLine("A " + outputReport(camera.physical.firstOrNull { it.id == a.physicalId }, a.physicalId, a).replace("\n", " | "))
                appendLine("B " + outputReport(camera.physical.firstOrNull { it.id == b.physicalId }, b.physicalId, b).replace("\n", " | "))
            }
            appendLine("A SENSOR_TIMESTAMP ns ${stats?.first?.lastSensorTimestampNs ?: "-"}")
            appendLine("B SENSOR_TIMESTAMP ns ${stats?.second?.lastSensorTimestampNs ?: "-"}")
            appendLine("Result timestamp A-B ns ${lastSkewNs ?: "-"} (not proof of sensor synchronization)")
            append("status $status")
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("HAL CAM dual preview", report))
        Toast.makeText(this, "보고서를 복사했습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun chips(options: List<Pair<String, String>>, selected: String?, pick: (String) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEach { (id, label) ->
            val chosen = id == selected
            val button = if (chosen) Look.primaryButton(this, label) {} else Look.ghostButton(this, label, dark = true) { if (!busy()) pick(id) }
            pairButtons += button
            button.textSize = 14f
            button.minHeight = dp(48); button.minimumHeight = dp(48)
            button.setPadding(dp(14), dp(8), dp(14), dp(8))
            ViewCompat.setStateDescription(button, if (chosen) "선택됨" else "선택 안 됨")
            row.addView(button, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        return android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    private fun busy() = closing || recording || recordPending || photoPending

    private fun updateControls() {
        root.keepScreenOn = started && !closing && !failed && streamingSize != null
        controlBar.bind(videoMode, mainControls != null && !closing && !recordPending && !photoPending && streamingSize != null)
        zoomControl.isEnabled = zoomAvailable && !closing && !recordPending && !photoPending && streamingSize != null
        liveIndicator.setSizesEnabled(!busy())
        engineButton.text = engineName
        engineButton.isEnabled = !busy()
        engineButton.alpha = if (busy()) 0.4f else 1f
        engineButton.contentDescription = "Current: $engineName; tap to switch engine"
        pairButtons.forEach { it.isEnabled = !busy(); it.alpha = if (it.isEnabled) 1f else 0.45f }
        pipSelector?.isEnabled = !busy()
        pipSelector?.alpha = if (busy()) 0.45f else 1f
        recordButton?.isEnabled = (videoMode || mainControls != null) && !closing && !recordPending && !photoPending && !failed && streamingSize != null
        recordButton?.setCaptureState(videoMode = videoMode, recording = recording)
        recordButton?.contentDescription = if (!videoMode) "두 센서 동시 사진 촬영" else if (recording) "두 카메라 녹화 정지" else "두 카메라 무음 녹화 시작"
        modeRow?.visibility = if (recording || recordPending) View.INVISIBLE else View.VISIBLE
        recordLabel?.visibility = if (recording || recordPending) View.VISIBLE else View.GONE
        if (recordPending) recordLabel?.text = "Starting…"
        galleryButton.isEnabled = !busy()
        galleryButton.alpha = if (busy()) 0.4f else 1f
        labButton.isEnabled = !busy()
        labButton.alpha = if (busy()) 0.4f else 1f
        recordButton?.alpha = if (recordButton?.isEnabled == true) 1f else 0.45f
        modeButtons.forEachIndexed { index, button ->
            button.isEnabled = !busy()
            button.isSelected = index == if (videoMode) 3 else 2
            button.setTypeface(null, if (button.isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            button.setTextColor(if (button.isSelected) Look.onDark else Look.onDarkMuted)
            button.alpha = if (button.isEnabled) 1f else 0.45f
            ViewCompat.setStateDescription(button, if (button.isSelected) "선택됨" else "선택 안 됨")
        }
    }

    private fun toggleRecording() {
        if (closing || recordPending) return
        if (recording) {
            closeSession { streamingSize = null; render() }
        } else {
            if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 7)
                return
            }
            recordPending = true
            session?.startRecording()
        }
        updateControls()
    }

    @Suppress("DEPRECATION")
    private fun takePhoto() {
        if (busy() || mainControls == null || streamingSize == null) return
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 7); return
        }
        photoPending = true
        updateControls()
        val degrees = when (windowManager.defaultDisplay.rotation) { 1 -> 90; 2 -> 180; 3 -> 270; else -> 0 }
        session?.capturePhoto(degrees)
    }

    private fun showCallbacks(show: Boolean) {
        if (show) manualPanel.close()
        callbackGraph.visibility = if (show) View.VISIBLE else View.GONE
        metricsText.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun leave(video: Boolean) {
        if (closing) return
        started = false
        closeSession {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_RETURN_VIDEO, video).putExtra(EXTRA_ENGINE, engineName))
            finish()
        }
        updateControls()
    }

    private fun choosePair() {
        if (busy()) return
        val options = arrayOf("메인 · ${pair?.first ?: "—"}", "보조 · ${pair?.second ?: "—"}", "Logical · ${logicalId ?: "—"}", "메인 ↔ 보조 교환")
        AlertDialog.Builder(this, R.style.LabDialogTheme).setTitle("Dual cameras")
            .setItems(options) { _, index ->
                if (index == 3) { if (!busy()) { pair = pair?.let { it.second to it.first }; restart() } }
                else if (index < 2) selectLens(index) else {
                    val choices = DualPreviewPlanner.candidates(cameras)
                    AlertDialog.Builder(this, R.style.LabDialogTheme).setTitle("Logical camera")
                        .setSingleChoiceItems(choices.map { it.logicalId }.toTypedArray(), choices.indexOfFirst { it.logicalId == logicalId }) { dialog, selected ->
                            if (!busy() && logicalId != choices[selected].logicalId) {
                                logicalId = choices[selected].logicalId; pair = null; restart()
                            }
                            dialog.dismiss()
                        }.setNegativeButton("취소", null).show()
                }
            }.setNegativeButton("취소", null).show()
    }

    private fun openTool(destination: Class<*>) {
        if (busy()) return
        val tool = Intent(this, destination)
            .putExtra(WorkbenchActivity.EXTRA_CAMERA_ID, logicalId)
            .putExtra(WorkbenchActivity.EXTRA_ENGINE, engineName)
            .putExtra(LiveStreamsActivity.EXTRA_DUAL, true)
            .putExtra(EXTRA_VIDEO, videoMode)
            .putExtra(LiveStreamsActivity.EXTRA_DUAL_SIZE, streamingSize?.toString())
            .putExtra(LiveStreamsActivity.EXTRA_DUAL_PAIR, "${pair?.first ?: "—"} / ${pair?.second ?: "—"}")
            .putExtra(LiveStreamsActivity.EXTRA_FROM_LIVE, destination == LiveStreamsActivity::class.java)
        started = false
        updateControls()
        closeSession {
            startActivity(tool)
        }
    }

    private fun exportIncident(incident: dev.halcamera.telemetry.Incident) {
        val sessions = telemetry.sessions.toMap()
        io.execute {
            val result = runCatching { IncidentExporter(applicationContext).export(incident, sessions) }
            main.post {
                if (isDestroyed || !started) return@post
                result.fold({ file ->
                    AlertDialog.Builder(this, R.style.LabDialogTheme).setTitle("Events ZIP 저장 완료")
                        .setMessage(file.name).setPositiveButton("닫기", null)
                        .setNeutralButton("공유") { _, _ ->
                            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip")
                                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Events ZIP"))
                        }.show()
                }, { Toast.makeText(this, "저장 실패: ${it.message}", Toast.LENGTH_LONG).show() })
            }
        }
    }

    private fun showInfo() {
        val camera = cameras.firstOrNull { it.logicalId == logicalId }
        val details = buildString {
            appendLine("$engineName · Logical $logicalId · Sync ${DualPreviewPlanner.syncLabel(camera?.syncType)}")
            if (engineName == "CameraX") appendLine("Photo & main controls: Camera2 only")
            stats?.let { (a, b) ->
                appendLine("\nCamera 1\n" + outputReport(camera?.physical?.firstOrNull { it.id == a.physicalId }, a.physicalId, a))
                appendLine("\nCamera 2\n" + outputReport(camera?.physical?.firstOrNull { it.id == b.physicalId }, b.physicalId, b))
                appendLine("\nSENSOR_TIMESTAMP (ns)\n1 ${a.lastSensorTimestampNs ?: "—"}\n2 ${b.lastSensorTimestampNs ?: "—"}")
            }
            appendLine("Result timestamp Δ (1−2): ${lastSkewNs?.let { String.format(Locale.US, "%.3f ms", it / 1e6) } ?: "—"}")
            appendLine("동일한 보고값은 실제 센서 동기를 입증하지 않습니다.")
            listOf("메인" to "dual_main_result", "보조" to "dual_sub_result").forEach { (label, kind) ->
                recorder.snapshot().lastOrNull { it.session == sessionId && it.kind == kind }?.let { e ->
                    appendLine("\n$label 적용값\n" + e.values.entries.joinToString("\n") { "${it.key}: ${it.value}" })
                }
            }
            if (videoMode) appendLine(if (engineName == "CameraX") "\nCameraX · H.264 · GPU relay · 무음 · MP4 × 2"
                else "\nH.264 · 30 fps 요청 · 무음 · MP4 × 2")
            append(status)
        }
        AlertDialog.Builder(this, R.style.LabDialogTheme).setTitle("Dual · Info").setMessage(details)
            .setNeutralButton("보고서 복사") { _, _ -> copyReport() }.setPositiveButton("닫기", null).show()
    }

    private fun positionPip(stage: FrameLayout, pip: View) {
        val available = ((bottomBar.parent as View).top - topBar.bottom - dp(16)).coerceAtLeast(0)
        val wanted = (stage.width * 0.32f).toInt().coerceAtLeast(dp(96)).coerceAtMost(stage.width)
        val height = (wanted * 16 / 9).coerceAtMost(available)
        val width = (height * 9 / 16).coerceAtMost(wanted)
        if (pip.layoutParams.width != width || pip.layoutParams.height != height) {
            pip.layoutParams = FrameLayout.LayoutParams(width, height)
        }
        val bounds = pipBounds(stage, pip)
        pip.x = bounds.left + pipX.coerceIn(0f, 1f) * bounds.width()
        pip.y = bounds.top + pipY.coerceIn(0f, 1f) * bounds.height()
    }

    private fun pipBounds(stage: FrameLayout, pip: View): android.graphics.RectF {
        val left = dp(16).toFloat()
        val top = (topBar.bottom + dp(8)).toFloat()
        val right = (stage.width - pip.layoutParams.width - dp(16)).toFloat().coerceAtLeast(left)
        val chrome = bottomBar.parent as View
        val bottom = (chrome.top - pip.layoutParams.height - dp(8)).toFloat().coerceAtLeast(top)
        return android.graphics.RectF(left, top, right, bottom)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun installDrag(stage: FrameLayout, pip: View) {
        var downX = 0f; var downY = 0f; var startX = 0f; var startY = 0f
        fun remember() {
            val bounds = pipBounds(stage, pip)
            pipX = if (bounds.width() > 0) (pip.x - bounds.left) / bounds.width() else 0f
            pipY = if (bounds.height() > 0) (pip.y - bounds.top) / bounds.height() else 0f
            positionPrefs.edit().putFloat("pip_x", pipX).putFloat("pip_y", pipY).apply()
        }
        pip.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = view.x; startY = view.y
                    view.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val bounds = pipBounds(stage, pip)
                    view.x = (startX + event.rawX - downX).coerceIn(bounds.left, bounds.right)
                    view.y = (startY + event.rawY - downY).coerceIn(bounds.top, bounds.bottom)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    remember(); view.parent.requestDisallowInterceptTouchEvent(false)
                    if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                }
            }
            true
        }
        listOf("왼쪽 위" to (0f to 0f), "오른쪽 위" to (1f to 0f),
            "왼쪽 아래" to (0f to 1f), "오른쪽 아래" to (1f to 1f)).forEach { (label, point) ->
            ViewCompat.addAccessibilityAction(pip, "$label 이동") { _, _ ->
                pipX = point.first; pipY = point.second; positionPip(stage, pip); remember(); true
            }
        }
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int) = Look.dp(this, value)

    companion object {
        const val EXTRA_ENGINE = "dual_engine"
        const val EXTRA_VIDEO = "dual_video"
        const val EXTRA_RETURN_VIDEO = "return_video"
        private const val KEY_LOGICAL = "dual_logical"
        private const val KEY_PAIR = "dual_pair"
    }
}
