package dev.halcamera

import androidx.core.view.isVisible
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.*
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.*
import dev.halcamera.camera.*
import dev.halcamera.telemetry.FlightRecorder
import dev.halcamera.telemetry.Telemetry
import dev.halcamera.telemetry.IncidentExporter
import dev.halcamera.ui.*
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/** Split changes preview geometry. Every device saves its own full photo or video. */
class ConcurrentCameraActivity : ComponentActivity() {
    internal val manager by lazy { getSystemService(CameraManager::class.java) }
    internal var videoMode = false
    internal var engineName = "Camera2"
    private lateinit var engineButton: Button
    private var microphonePending = false
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphonePending = false
        if (granted && foreground && ready && videoMode && !busy) startRecording()
        else if (!granted) notice("Microphone permission is required for Multi video with sound.")
        updateButtons()
    }
    internal var arrangement = SplitArrangement.identity(0)
    private lateinit var panelDrag: SplitPanelDrag
    private val labelBar by lazy { dp(28) }
    private val labelTouch by lazy { dp(48) }
    private val readoutRow by lazy { dp(24) }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) discover() else status.text = "Camera permission required."
    }
    private lateinit var root: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var captureChrome: LinearLayout
    private lateinit var status: TextView
    private lateinit var resultText: TextView
    private lateinit var shutter: ShutterButton
    private lateinit var splitButton: Button
    private lateinit var cameraButton: IconButton
    private lateinit var snapshotButton: IconButton
    private lateinit var galleryButton: RecentMediaButton
    private lateinit var labButton: Button
    private lateinit var retryButton: Button
    private lateinit var callbackButton: Button
    private lateinit var reportButton: Button
    private lateinit var modeRow: LinearLayout
    private lateinit var recordingTime: TextView
    private lateinit var controlBar: LiveControlBar
    private lateinit var zoomControl: ExpandingZoomControl
    private lateinit var liveIndicator: LiveIndicator
    private lateinit var callbackGraph: ResultCallbackGraph
    private lateinit var recentMedia: RecentMediaThumbnail
    private val modeButtons = mutableListOf<Button>()
    private val previews = mutableListOf<TextureView>()
    private val panels = mutableListOf<FrameLayout>()
    private val labels = mutableListOf<TextView>()
    private val infos = mutableListOf<TextView>()
    private val footInfos = mutableListOf<TextView>()
    private val handles = mutableListOf<LinearLayout>()
    private val focusRings = mutableListOf<FocusRing>()
    private val textures = mutableListOf<SurfaceTexture?>()
    private val retired = mutableListOf<SurfaceTexture>()
    private val cameraControls = mutableMapOf<String,LiveControls>()
    private val cameraZooms = mutableMapOf<String,Float>()
    internal var plans = emptyList<ConcurrentPlan>()
    private var outputSizes = emptyList<LiveSize>()
    internal var selected = 0
    internal var selectedIds = emptyList<String>()
    /** Camera ID to (preview, JPEG) chosen in the Sizes sheet; kept across device changes for cameras that return. */
    internal var chosenSizes = emptyMap<String,Pair<LiveSize,LiveSize>>()
    internal var activeIndex = 0
    internal var session: MultiSession? = null
    internal var foreground = false
    private var closing = false
    private var exiting = false
    internal var failed = false
    internal var ready = false
    internal var takingPhoto = false
    internal var recording = false
    internal var videoPending = false
    private var recordingSince = 0L
    private var report = ""
    private var afterClose: (() -> Unit)? = null
    internal val cli by lazy { dev.halcamera.cli.CommandCoordinator.get(this) }
    internal val multiCli by lazy { createMultiCli() }
    internal var cliPaused = false
    private var cliWasActive = false
    private val closeCallbacks = mutableListOf<() -> Unit>()
    internal val mediaBusy get() = takingPhoto || recording || videoPending || closing || microphonePending
    private val busy get() = mediaBusy || cli.active != null
    internal val recorder = FlightRecorder(SystemClock::elapsedRealtimeNanos)
    private val telemetry = Telemetry(recorder)
    private var sessionId = "multi"
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val activeCamera get() = plans.getOrNull(selected)?.streams?.getOrNull(activeIndex)?.camera
    private val activeSessionId get() = "${sessionId}_${activeCamera?.id}"
    private val tick = object : Runnable {
        override fun run() {
            val cliActive = cli.active != null
            if (cliActive != cliWasActive) { cliWasActive = cliActive; updateButtons() }
            val now = SystemClock.elapsedRealtimeNanos()
            val events = recorder.snapshot()
            val latest = events.lastOrNull { it.session == activeSessionId && it.kind == "capture_result" }
                ?.takeIf { now-it.atNs < 1_500_000_000L }
            bindInfos(events,now)
            liveIndicator.bind(ready && latest != null && foreground && !closing)
            liveIndicator.bindStabilization(LiveEisStatus((latest?.values?.get("videoStabilization") as? Number)?.toInt()),recording)
            if (callbackGraph.isVisible)
                // The camera chosen by its name bar drives the graph; the graph has no ID switch of its own here.
                callbackGraph.update(events,activeSessionId,now,telemetry.sessions[activeSessionId].orEmpty())
            if (recording) {
                val seconds = (SystemClock.elapsedRealtime()-recordingSince)/1000
                recordingTime.text = "● REC  ${seconds/60}:${(seconds%60).toString().padStart(2,'0')}"
            }
            recorder.finish()?.let(::exportIncident)
            val remaining = recorder.remainingNs()
            reportButton.text = remaining?.let { "Saving in ${String.format(Locale.US,"%.1f",it/1e9)}s" } ?: "Save Events · ZIP"
            reportButton.isEnabled = ready && remaining == null && !closing && cli.active == null
            main.postDelayed(this,100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme); super.onCreate(savedInstanceState)
        engineName = (savedInstanceState?.getString(EXTRA_ENGINE) ?: intent.getStringExtra(EXTRA_ENGINE)).let { if (it == "CameraX") it else "Camera2" }
        videoMode = savedInstanceState?.getBoolean(EXTRA_VIDEO) ?: intent.getBooleanExtra(EXTRA_VIDEO,false)
        cliPaused = intent.hasExtra("cli_request_id")
        val layout = SplitLayout.entries.firstOrNull { it.name == savedInstanceState?.getString("layout") } ?: SplitLayout.SIDE_BY_SIDE
        arrangement = runCatching { SplitArrangement(layout,savedInstanceState?.getIntArray("order")?.toList().orEmpty()) }
            .getOrElse { SplitArrangement.identity(0,layout) }
        selectedIds = savedInstanceState?.getStringArrayList("devices").orEmpty()
        @Suppress("DEPRECATION","UNCHECKED_CAST")
        chosenSizes = (savedInstanceState?.getSerializable("sizes") as? HashMap<String,Pair<LiveSize,LiveSize>>).orEmpty()
        WindowCompat.setDecorFitsSystemWindows(window,false)
        WindowInsetsControllerCompat(window,window.decorView).apply {
            isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false
        }
        showPage()
        recentMedia = RecentMediaThumbnail(this) { bitmap,video -> galleryButton.setThumbnail(bitmap,video) }
        onBackPressedDispatcher.addCallback(this,object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (callbackGraph.isVisible) showCallbacks(false) else finish()
            }
        })
        if (ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) discover()
        else if (!intent.hasExtra("cli_request_id")) permission.launch(Manifest.permission.CAMERA)
    }

    private fun showPage() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        stage = FrameLayout(this).apply { clipChildren = true }
        root.addView(stage,FrameLayout.LayoutParams(-1,-1))
        stage.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> layoutPreviews() }
        panelDrag = SplitPanelDrag(stage,{ previewArea() },{ arrangement },{ ready && !busy }) { next ->
            arrangement = next; layoutPreviews(); bindLabels()
        }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.argb(140,0,0,0),Color.TRANSPARENT))
        }
        val controls = row().apply { gravity = Gravity.TOP }
        val leading = row()
        engineButton = button(engineName) {
            if (!busy) { engineName = if (engineName == "Camera2") "CameraX" else "Camera2"; failed = false; closeSession() }
        }
        leading.addView(engineButton,LinearLayout.LayoutParams(-2,dp(48)))
        splitButton = button("Split") { showSplit() }.apply { contentDescription = "Choose Split camera devices" }
        leading.addView(splitButton,LinearLayout.LayoutParams(-2,dp(48)))
        controls.addView(leading,LinearLayout.LayoutParams(0,dp(48),1f))
        controlBar = LiveControlBar(this,object : LiveControlBar.Host {
            override fun controlsChanged(controls: LiveControls) { activeCamera?.let {
                cameraControls[it.id] = controls
                if (Build.VERSION.SDK_INT >= 30) session?.setControls(it.id,controls)
            } }
            override fun notice(text: String) = this@ConcurrentCameraActivity.notice(text)
        }).apply { setMultiMode() }
        controls.addView(FrameLayout(this).apply {
            addView(controlBar.handle,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER_HORIZONTAL))
        },LinearLayout.LayoutParams(dp(48),dp(48)))
        val trailing = row().apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        callbackButton = button("Callback") { showCallbacks(callbackGraph.visibility != View.VISIBLE) }
        trailing.addView(callbackButton,LinearLayout.LayoutParams(-2,dp(48)))
        labButton = button("Lab") { openTool(WorkbenchActivity::class.java) }
        trailing.addView(labButton,LinearLayout.LayoutParams(-2,dp(48)).apply { marginStart = dp(4) })
        controls.addView(trailing,LinearLayout.LayoutParams(0,dp(48),1f)); topBar.addView(controls)
        liveIndicator = LiveIndicator(this).apply { onSizesClick = { showSizes() } }
        topBar.addView(controlBar.withStatus(liveIndicator),lp())
        status = text("Opening cameras…",12).apply {
            gravity = Gravity.CENTER; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        topBar.addView(status,lp(4))
        retryButton = button("Retry") { failed = false; closeSession() }.apply { visibility = View.GONE }
        topBar.addView(retryButton,lp()); root.addView(topBar,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        captureChrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,intArrayOf(Color.argb(180,0,0,0),Color.TRANSPARENT))
        }
        val bottomBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        callbackGraph = ResultCallbackGraph(this).apply { visibility = View.GONE }; bottomBar.addView(callbackGraph,lp())
        zoomControl = ExpandingZoomControl(this) { ratio ->
            if (Build.VERSION.SDK_INT >= 30 && ready && cli.active == null && !takingPhoto && !videoPending && !closing) activeCamera?.let {
                cameraZooms[it.id] = ratio; session?.setZoom(it.id,ratio)
            }
        }
        bottomBar.addView(zoomControl.viewport(),LinearLayout.LayoutParams(-2,dp(48)))
        val captureRow = row().apply { isBaselineAligned = false }
        galleryButton = RecentMediaButton(this) { openTool(GalleryActivity::class.java) }
        captureRow.addView(FrameLayout(this).apply {
            addView(galleryButton,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER))
        },LinearLayout.LayoutParams(0,dp(64),1f))
        shutter = ShutterButton(this).apply { setOnClickListener { capture() } }
        captureRow.addView(shutter,LinearLayout.LayoutParams(dp(64),dp(64)).apply { marginStart = dp(12); marginEnd = dp(12) })
        cameraButton = IconButton(this,R.drawable.ic_camera_select,"Choose Split cameras",filled = true) { showSplit(LiveChoiceSheet.Edge.BOTTOM) }
        snapshotButton = IconButton(this,R.drawable.ic_snapshot,"Take a photo while recording",filled = true) { takePhoto() }
        captureRow.addView(FrameLayout(this).apply {
            addView(cameraButton,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER))
            addView(snapshotButton,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER))
        },LinearLayout.LayoutParams(0,dp(64),1f))
        bottomBar.addView(captureRow,lp(4))
        val modeFrame = FrameLayout(this)
        modeRow = row().apply { gravity = Gravity.CENTER; isBaselineAligned = false }
        listOf("Photo","Video","Multi · P","Multi · V").forEachIndexed { index,label ->
            val mode = button(label) {
                if (!busy) {
                    if (index < 2) leave(index == 1)
                    else if (videoMode != (index == 3)) {
                        videoMode = index == 3; failed = false
                        zoomControl.collapse(animate = false)
                        // Match Photo/Video: retain composition, but rebuild mode-specific streams after close(done).
                        closeSession()
                    }
                }
            }.apply { textSize = 12f; setPadding(dp(4),0,dp(4),0) }
            modeButtons += mode; modeRow.addView(mode,LinearLayout.LayoutParams(0,dp(48),1f))
        }
        modeFrame.addView(modeRow,FrameLayout.LayoutParams(-1,-1))
        recordingTime = text("",14).apply { gravity = Gravity.CENTER; setTextColor(Look.statusFail); typeface = Look.mono; visibility = View.GONE }
        modeFrame.addView(recordingTime,FrameLayout.LayoutParams(-1,-1)); bottomBar.addView(modeFrame,LinearLayout.LayoutParams(-1,dp(48)))
        captureChrome.addView(bottomBar)
        val footer = row()
        reportButton = button("Save Events · ZIP") { if (recorder.trigger("multi_${UUID.randomUUID()}")) notice("Saving events in 5 seconds") }
        footer.addView(reportButton,LinearLayout.LayoutParams(-1,dp(48))); captureChrome.addView(footer)
        root.addView(captureChrome,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        resultText = text("",12).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setOnClickListener { if (report.isNotEmpty()) LiveChoiceSheet.show(this@ConcurrentCameraActivity,"Capture details",content = text(report,12)) }
        }
        root.addView(resultText,FrameLayout.LayoutParams(-1,dp(24),Gravity.BOTTOM))
        captureChrome.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ ->
            val params = resultText.layoutParams as FrameLayout.LayoutParams
            if (params.bottomMargin != captureChrome.height + dp(4)) { params.bottomMargin = captureChrome.height + dp(4); resultText.layoutParams = params }
            layoutPreviews()
        }
        topBar.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> layoutPreviews() }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _,insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topBar.setPadding(bars.left+dp(12),bars.top+dp(8),bars.right+dp(12),dp(10))
            bottomBar.setPadding(bars.left+dp(16),dp(4),bars.right+dp(16),0)
            footer.setPadding(bars.left+dp(16),0,bars.right+dp(16),bars.bottom); insets
        }
        setContentView(root); ViewCompat.requestApplyInsets(root); updateButtons()
    }

    private fun createPanels(count: Int) {
        // Old surfaces are removed after close(done); their listeners release each texture.
        stage.removeAllViews(); previews.clear(); panels.clear(); labels.clear(); infos.clear(); footInfos.clear(); handles.clear(); focusRings.clear(); textures.clear()
        repeat(count) { textures += null }
        if (arrangement.count != count) arrangement = SplitArrangement.identity(count,arrangement.layout)
        repeat(count) { index ->
            val panel = FrameLayout(this).apply { clipChildren = true; setBackgroundColor(Color.BLACK) }
            val preview = TextureView(this).apply { alpha = 0f }; previews += preview; panels += panel
            panel.addView(preview,FrameLayout.LayoutParams(-1,-1))
            lateinit var ring: FocusRing
            ring = FocusRing(this,{ object : TouchMetering {
                override fun meterAt(x: Float,y: Float,exposure: Boolean,feedback: (TouchPhase) -> Unit): Boolean {
                    if (Build.VERSION.SDK_INT < 30 || ring.width == 0 || ring.height == 0) return false
                    selectActive(index)
                    return activeCamera?.let { session?.meterAt(it.id,x.toDouble()/ring.width,y.toDouble()/ring.height,exposure,feedback) } == true
                }
            } },{ on -> selectActive(index); controlBar.setAeLock(on) }).apply {
                canInteract = { ready && cli.active == null && !takingPhoto && !videoPending && !closing }
                onZoomScale = { factor ->
                    if (Build.VERSION.SDK_INT >= 30 && factor.isFinite() && factor > 0) {
                        if (activeIndex != index) selectActive(index)
                        activeCamera?.let { camera ->
                            val range = zoomRange(manager,camera.id)
                            val ratio = ((cameraZooms[camera.id] ?: 1f)*factor).coerceIn(range.first,range.second)
                            cameraZooms[camera.id] = ratio; session?.setZoom(camera.id,ratio); zoomControl.setRatio(ratio)
                        }
                    }
                }
            }
            focusRings += ring; panel.addView(ring,FrameLayout.LayoutParams(-1,-1))
            val label = text("",11).apply {
                isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            // Each camera's own FPS and exposure ride on its name bar, so they follow the panel through every layout.
            val info = text("",10).apply {
                isSingleLine = true; setTextColor(Look.onDarkMuted); fontFeatureSettings = "tnum"
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val handle = row().apply {
                // The 28dp bar keeps a 48dp touch area by reaching up into the space above it, never over the preview.
                setPadding(dp(8),labelTouch-labelBar,dp(8),0)
                setOnClickListener { selectActive(index) }
                addView(label,LinearLayout.LayoutParams(-2,-1))
                addView(View(this@ConcurrentCameraActivity),LinearLayout.LayoutParams(0,1,1f))
                addView(info,LinearLayout.LayoutParams(-2,-1).apply { marginStart = dp(8) })
            }
            val footInfo = text("",10).apply {
                isSingleLine = true; setTextColor(Look.onDarkMuted); fontFeatureSettings = "tnum"
                setPadding(dp(12),0,dp(12),0); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            labels += label; infos += info; footInfos += footInfo; handles += handle
            panel.addView(footInfo,FrameLayout.LayoutParams(-1,readoutRow))
            panel.addView(handle,FrameLayout.LayoutParams(-1,labelTouch)); stage.addView(panel)
            panelDrag.attach(handle,panel,index)
            preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture,width: Int,height: Int) {
                    if (previews.getOrNull(index) !== preview) return
                    textures[index] = texture; startIfReady(); transform(index)
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture,width: Int,height: Int) {
                    transform(index); startIfReady()
                }
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    if (previews.getOrNull(index) !== preview) return true
                    textures[index] = null
                    if (session != null || closing) { retired += texture; closeSession(); return false }
                    return true
                }
            }
        }
        layoutPreviews(); updateButtons()
    }

    private fun discover() {
        if (Build.VERSION.SDK_INT < 30) { status.text = "Requires Android 11 or later."; return }
        try {
            plans = readConcurrentPlans(manager,if (engineName == "CameraX") 2 else Int.MAX_VALUE)
            selected = plans.indexOfFirst { it.streams.map { stream -> stream.camera.id }.toSet() == selectedIds.toSet() }.coerceAtLeast(0)
            if (plans.isEmpty()) { ready = false; status.visibility = View.VISIBLE; status.text = "Multi is unavailable on this device." }
            else {
                val plan = plans[selected]
                if (selectedIds.toSet() == plan.streams.map { it.camera.id }.toSet())
                    plans = plans.toMutableList().also { it[selected] = plan.copy(streams = selectedIds.map { id -> plan.streams.first { it.camera.id == id } }) }
                else selectedIds = plan.streams.map { it.camera.id }
                plans = plans.toMutableList().also { it[selected] = ConcurrentPlanner.withSizes(it[selected],chosenSizes) }
                activeIndex = activeIndex.coerceIn(plan.streams.indices); startIfReady()
            }
        } catch (e: Exception) { status.text = e.message; status.visibility = View.VISIBLE; failed = true }
        updateButtons()
    }

    private fun showSplit(edge: LiveChoiceSheet.Edge = LiveChoiceSheet.Edge.TOP) {
        if (busy || plans.none { it.advertised }) return
        // The sheet lists cameras in on-screen order. Reordering the same cameras only moves panels.
        val onScreen = if (arrangement.count == selectedIds.size) arrangement.order.map { selectedIds[it] } else selectedIds
        SplitChoiceSheet(this).show(plans,onScreen,edge) { ids ->
            if (busy) false else {
                if (ids.toSet() == selectedIds.toSet()) {
                    arrangement = arrangement.copy(order = ids.map(selectedIds::indexOf)); layoutPreviews(); bindLabels()
                } else {
                    selectedIds = ids; arrangement = SplitArrangement.identity(ids.size,arrangement.layout)
                    failed = false; activeIndex = 0; closeSession()
                }
                true
            }
        }
    }
    private fun bindLabels() {
        val plan = plans.getOrNull(selected) ?: return
        labels.forEachIndexed { i,label ->
            val camera = plan.streams.getOrNull(i)?.camera ?: return@forEachIndexed
            val selected = i == activeIndex
            label.text = "⠿  ${arrangement.order.indexOf(i)+1} · ${camera.label}${if (selected) "  ✓" else ""}"
            label.background = Look.cardBackground(this,Look.cameraControlGlass,
                if (selected) Look.cameraSelection else Look.cameraOutline)
            label.setPadding(dp(8),0,dp(8),0)
            label.setTextColor(if (selected) Look.onDark else Look.onDarkMuted)
            label.setTypeface(null,if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            describe(i)
            handles.getOrNull(i)?.let { ViewCompat.setStateDescription(it,if (i == activeIndex) "Selected" else "Not selected") }
        }
        bindSizes()
    }
    private fun describe(i: Int) {
        val camera = plans.getOrNull(selected)?.streams?.getOrNull(i)?.camera ?: return
        handles.getOrNull(i)?.contentDescription =
            "Screen ${arrangement.order.indexOf(i)+1}, ${camera.label}. ${infos[i].text}. Tap to control this camera. Hold and drag to move it"
    }
    /** FPS and exposure of each camera from its own fresh result; a stale camera shows dashes. */
    private fun bindInfos(events: List<dev.halcamera.telemetry.Event>,now: Long) {
        val plan = plans.getOrNull(selected) ?: return
        infos.forEachIndexed { i,info ->
            val key = "${sessionId}_${plan.streams.getOrNull(i)?.camera?.id}"
            val values = events.lastOrNull { it.session == key && it.kind == "capture_result" }
                ?.takeIf { now-it.atNs < 1_500_000_000L }?.values.orEmpty()
            val text = LiveMeasurementText.fields(values).take(2).joinToString("  ")
            if (info.text.toString() != text) { info.text = text; footInfos.getOrNull(i)?.text = text; describe(i) }
        }
    }
    private fun previewArea(): android.graphics.Rect? {
        val top = topBar.bottom; val bottom = captureChrome.top
        return if (stage.width <= 0 || bottom <= top) null else android.graphics.Rect(0,top,stage.width,bottom)
    }
    private fun selectActive(index: Int) {
        if (Build.VERSION.SDK_INT < 30) return
        if (takingPhoto || videoPending || closing || index !in panels.indices) return
        activeIndex = index
        val id = activeCamera?.id ?: return
        controlBar.reset(liveControlSupport(manager,id).copy(autoFlash = false,alwaysFlash = false)); controlBar.setMultiMode()
        controlBar.applyRequested(cameraControls[id] ?: LiveControls())
        val ratio = cameraZooms[id] ?: 1f
        session?.setZoom(id,ratio); zoomControl.setChoices(zoomPresets(zoomRange(manager,id)),ratio)
        bindLabels(); updateButtons()
    }
    /** Every camera's sizes, numbered like the name bars. */
    private fun bindSizes() {
        val streams = plans.getOrNull(selected)?.streams ?: return
        if (arrangement.count != streams.size) return
        liveIndicator.bindSizes(LiveStreamReadout.multi(arrangement.order.map { i ->
            streams[i].camera.label to mapOf("preview" to streams[i].preview.toString(),"jpeg" to streams[i].photo.toString())
        }),"choose sizes",arrangement.order.indexOf(activeIndex))
    }
    private fun showSizes() {
        if (busy) return
        val streams = plans.getOrNull(selected)?.streams ?: return
        if (arrangement.count != streams.size) return
        MultiSizeSheet(this).show(arrangement.order.map { streams[it] }) { sizes ->
            if (busy) false else {
                val changed = sizes.any { (id,size) -> streams.first { it.camera.id == id }.let { it.preview to it.photo } != size }
                chosenSizes = chosenSizes + sizes
                if (changed) { failed = false; closeSession() }
                true
            }
        }
    }

    internal fun startIfReady() {
        if (Build.VERSION.SDK_INT < 30 || cliPaused || !foreground || exiting || closing || failed || session != null || plans.isEmpty()) return
        val plan = plans[selected]
        if (textures.size != plan.streams.size) { createPanels(plan.streams.size); return }
        if (textures.any { it == null } || previews.any { it.width == 0 || it.height == 0 }) return
        outputSizes = plan.streams.map { stream ->
            concurrentOutputSize(stream.preview,manager.getCameraCharacteristics(stream.camera.id)[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0)
        }
        cameraControls.clear(); cameraZooms.clear(); ready = false; report = ""; resultText.text = ""
        status.visibility = View.VISIBLE; status.text = "Opening cameras…"; sessionId = "multi_${UUID.randomUUID()}"
        lateinit var current: MultiSession
        val listener = object : MultiSession.Listener {
            override fun onState(id: String,state: String) {}
            override fun onReady() {
                if (session !== current || closing || !foreground || failed) return
                ready = true; previews.forEach { it.alpha = 1f }; status.visibility = View.GONE
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); selectActive(activeIndex); updateButtons()
                multiCli.ready()
            }
            override fun onFailed(reason: String) {
                if (session !== current) return
                failed = true; ready = false; recording = false; videoPending = false; takingPhoto = false
                status.text = reason; status.visibility = View.VISIBLE; previews.forEach { it.alpha = 0f }
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); updateButtons()
                multiCli.failed(reason)
            }
            override fun onPhotoOutcomes(outcomes: Map<String, Result<android.net.Uri>>) {
                if (session === current) multiArtifacts(outcomes, false)
            }
            override fun onVideoOutcomes(outcomes: Map<String, Result<android.net.Uri>>) {
                if (session === current) multiArtifacts(outcomes, true)
            }
            override fun onPhoto(result: ConcurrentMediaResult) {
                if (session !== current) return
                takingPhoto = false; report = result.message
                resultText.text = if (result.failed) "Photo failed · Details" else "Photos saved · Details"
                updateButtons()
            }
            override fun onRecording(active: Boolean,result: ConcurrentMediaResult) {
                if (session !== current) return
                recording = active; videoPending = false; report = result.message
                if (active) { recordingSince = SystemClock.elapsedRealtime(); multiCli.recordingStarted() }
                else if (result.failed) multiCli.failed(result.message)
                resultText.text = if (active) "" else if (result.failed) "Video failed · Details" else "Videos saved · Details"
                updateButtons()
            }
        }
        current = if (engineName == "CameraX") CameraXMultiSession(this,this,plan,textures.map { checkNotNull(it) },listener,
            outputSizes,telemetry,sessionId,videoMode)
        else ConcurrentSession(this,manager,plan,textures.map { checkNotNull(it) },listener,
            outputSizes = outputSizes,telemetry = telemetry,sessionId = sessionId,videoMode = videoMode)
        session = current; layoutPreviews(); current.start(); updateButtons()
    }

    @SuppressLint("NewApi")
    internal fun closeSession(after: (() -> Unit)? = null) {
        after?.let { closeCallbacks += it }
        if (after == null && cli.active == null) cliPaused = false
        if (closing) return
        closing = true; ready = false; previews.forEach { it.alpha = 0f }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); updateButtons()
        val done = {
            session = null; closing = false; takingPhoto = false; recording = false; videoPending = false
            retired.forEach { it.release() }; retired.clear()
            val callbacks = closeCallbacks.toList(); closeCallbacks.clear()
            callbacks.forEach { it() }
            val destination = afterClose; afterClose = null
            if (exiting) super.finish() else if (destination != null) destination() else { updateButtons(); if (foreground && !cliPaused && callbacks.isEmpty()) discover() }
        }
        session?.close(done) ?: done()
    }
    private fun capture() {
        if (Build.VERSION.SDK_INT < 30 || !ready || takingPhoto || videoPending || closing || microphonePending) return
        if (videoMode) {
            if (recording) { videoPending = true; session?.stopVideo() }
            else if (ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
            else { microphonePending = true; microphonePermission.launch(Manifest.permission.RECORD_AUDIO) }
        }
        else if (!recording) takePhoto()
        updateButtons()
    }
    private fun startRecording() {
        videoPending = true; session?.startVideo(audio = true)
    }
    private fun takePhoto() {
        if (Build.VERSION.SDK_INT < 30 || !ready || takingPhoto || videoPending || closing) return
        takingPhoto = true; resultText.text = "Capturing…"; session?.capture(displayDegrees()); updateButtons()
    }
    private fun leave(video: Boolean) { setResult(RESULT_OK,Intent().putExtra(DualPreviewActivity.EXTRA_RETURN_VIDEO,video)); finish() }
    private fun openTool(destination: Class<*>) {
        if (busy) return
        val id = activeCamera?.id ?: return
        afterClose = { startActivity(Intent(this,destination)
            .putExtra(WorkbenchActivity.EXTRA_CAMERA_ID,id).putExtra(WorkbenchActivity.EXTRA_ENGINE,engineName)) }
        closeSession()
    }
    override fun onStart() {
        super.onStart(); foreground = true; recentMedia.start(); main.post(tick)
        val incoming = intent.getStringExtra("cli_request_id")
        if (incoming != null && cli.active?.id != incoming) { cliPaused = true; finish(); return }
        if (cli.active == null) cliPaused = false
        if (intent.hasExtra("cli_request_id")) { intent.removeExtra("cli_request_id"); cli.continueHandover(multiCli) }
        else cli.attach(multiCli)
        if (session == null && !closing && ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) discover() else startIfReady()
    }
    override fun onStop() {
        foreground = false; multiCli.stopped(); cli.detach(multiCli); main.removeCallbacks(tick); recentMedia.stop(); recorder.finish("screen_stopped")?.let(::exportIncident)
        closeSession(); super.onStop()
    }
    override fun onDestroy() { recentMedia.close(); io.shutdown(); super.onDestroy() }
    override fun finish() { exiting = true; closeSession() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(EXTRA_ENGINE,engineName)
        outState.putBoolean(EXTRA_VIDEO,videoMode); outState.putString("layout",arrangement.layout.name)
        outState.putIntArray("order",arrangement.order.toIntArray())
        outState.putSerializable("sizes",HashMap(chosenSizes))
        outState.putStringArrayList("devices",ArrayList(selectedIds)); super.onSaveInstanceState(outState)
    }
    internal fun updateButtons() {
        cli.setUiBusy(multiCli, mediaBusy)
        engineButton.text = engineName; engineButton.isEnabled = !busy

        shutter.isEnabled = ready && cli.active == null && !takingPhoto && !videoPending && !closing && !microphonePending
        shutter.setCaptureState(videoMode = videoMode,recording = recording)
        shutter.contentDescription = if (videoMode) { if (recording) "Stop all recordings" else "Record each camera separately with microphone audio" } else "Capture a separate photo from each camera"
        splitButton.isEnabled = plans.isNotEmpty() && !busy; cameraButton.isEnabled = splitButton.isEnabled
        cameraButton.visibility = if (recording) View.GONE else View.VISIBLE
        snapshotButton.visibility = if (recording) View.VISIBLE else View.GONE
        snapshotButton.isEnabled = ready && cli.active == null && recording && !takingPhoto && !videoPending && !closing
        labButton.isEnabled = !busy && activeCamera != null; galleryButton.isEnabled = !busy
        retryButton.visibility = if (failed) View.VISIBLE else View.GONE; retryButton.isEnabled = !busy
        reportButton.isEnabled = ready && recorder.remainingNs() == null && !closing && cli.active == null
        resultText.isClickable = report.isNotEmpty() && !recording; resultText.isFocusable = resultText.isClickable
        controlBar.bind(videoMode,ready && cli.active == null && !takingPhoto && !videoPending && !closing)
        zoomControl.isEnabled = ready && cli.active == null && !takingPhoto && !videoPending && !closing; liveIndicator.setSizesEnabled(!busy)
        modeRow.visibility = if (recording || videoPending) View.INVISIBLE else View.VISIBLE
        recordingTime.visibility = if (recording || videoPending) View.VISIBLE else View.GONE
        if (videoPending) recordingTime.text = if (recording) "Saving…" else "Starting…"
        modeButtons.forEachIndexed { index,button ->
            button.isEnabled = !busy; button.isSelected = index == if (videoMode) 3 else 2
            button.setTextColor(if (button.isSelected) Look.onDark else Look.onDarkMuted)
            button.setTypeface(null,if (button.isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            ViewCompat.setStateDescription(button,if (button.isSelected) "Selected" else "Not selected")
        }
        listOf<View>(shutter,splitButton,cameraButton,snapshotButton,labButton,galleryButton,zoomControl).forEach { it.alpha = if (it.isEnabled) 1f else .4f }
    }
    private fun layoutPreviews() {
        if (stage.width <= 0 || stage.height <= 0 || panels.isEmpty() || arrangement.count != panels.size) return
        val top = topBar.bottom
        val frames = arrangement.frames(stage.width,(captureChrome.top-top).coerceAtLeast(1))
        panels.forEachIndexed { i,panel ->
            val f = frames[i]; val old = panel.layoutParams as? FrameLayout.LayoutParams
            if (old == null || old.width != f.width || old.height != f.height || old.topMargin != top+f.top || old.leftMargin != f.left)
                panel.layoutParams = FrameLayout.LayoutParams(f.width,f.height).apply { topMargin = top+f.top; leftMargin = f.left }
            // The Callback graph replaces the live readouts while it is open.
            val readout = callbackGraph.visibility != View.VISIBLE
            val below = readout && splitReadoutBelow(arrangement.layout)
            val fit = splitPreviewFrame(f.width,f.height,labelTouch,if (below) readoutRow else 0,
                outputSizes.getOrNull(i)?.let { it.width.toFloat()/it.height })
            infos.getOrNull(i)?.visibility = if (readout && !below) View.VISIBLE else View.GONE
            val infoWidth = if (readout && !below) infos.getOrNull(i)?.let {
                it.paint.measureText("FPS 999.9  EXP 999.99ms").toInt()+dp(8)
            } ?: 0 else 0
            labels.getOrNull(i)?.maxWidth = (f.width-dp(16)-infoWidth).coerceAtLeast(dp(48))
            footInfos.getOrNull(i)?.let {
                it.visibility = if (below) View.VISIBLE else View.GONE
                // Starts under the preview but may run past it, so a narrow preview never clips the readout.
                place(it,ConcurrentFrame(fit.left,fit.top+fit.height,f.width-fit.left,readoutRow))
            }
            place(previews[i],fit); focusRings.getOrNull(i)?.let { place(it,fit) }
            handles.getOrNull(i)?.let { place(it,ConcurrentFrame(0,fit.top-labelTouch,f.width,labelTouch)) }
            transform(i)
        }
        // Returning from another screen can expose textures before their views have dimensions.
        stage.post { startIfReady() }
    }
    private fun place(view: View,f: ConcurrentFrame) {
        val old = view.layoutParams as? FrameLayout.LayoutParams
        if (old == null || old.width != f.width || old.height != f.height || old.leftMargin != f.left || old.topMargin != f.top)
            view.layoutParams = FrameLayout.LayoutParams(f.width,f.height).apply { leftMargin = f.left; topMargin = f.top }
    }
    private fun transform(index: Int) {
        if (outputSizes.getOrNull(index) == null) return
        val view = previews.getOrNull(index) ?: return
        if (view.width <= 0 || view.height <= 0) return
        // EGL fits the full source to the actual native window. A second TextureView fit would shrink it twice.
        view.setTransform(Matrix())
    }
    private fun showCallbacks(show: Boolean) {
        callbackGraph.visibility = if (show) View.VISIBLE else View.GONE; layoutPreviews()
        CameraWidgets(this).highlight(callbackButton,show)
    }
    internal fun exportIncident(incident: dev.halcamera.telemetry.Incident) {
        val metadata = telemetry.sessions.toMap()
        io.execute {
            val result = runCatching { IncidentExporter(applicationContext).export(incident,metadata) }
            main.post {
                if (cli.active?.let { it.id == incident.id && it.command == "events" } == true) {
                    result.fold({ file -> cli.complete(incident.id, mapOf("artifact_count" to 1,
                        "cancelled" to (cli.readRecord(incident.id)?.optString("state") == "cancelling")),
                        listOf(dev.halcamera.cli.CliArtifact(file.name, "application/zip", android.net.Uri.fromFile(file)))) },
                        { cli.fail(incident.id, "SAVE_FAILED", it.message ?: "Events export failed") })
                    return@post
                }
                if (!foreground || isDestroyed) return@post
                result.fold({ file ->
                    LiveChoiceSheet.show(this,"Events ZIP saved",content = text(file.name,12),actionLabel = "Share",confirm = {
                        val uri = FileProvider.getUriForFile(this,"$packageName.files",file)
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip")
                            .putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Events ZIP")); true
                    })
                },{ notice("Save failed: ${it.message}") })
            }
        }
    }
    private fun notice(value: String) = Toast.makeText(this,value,Toast.LENGTH_LONG).show()
    @Suppress("DEPRECATION")
    internal fun displayDegrees() = DisplayRotation.degrees(windowManager.defaultDisplay.rotation)
    private fun dp(value: Int) = Look.dp(this,value)
    private fun text(value: String,size: Int) = Look.text(this,value,size,Look.onDark).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun button(value: String,action: () -> Unit) = CameraWidgets(this).chromeButton(value,action)
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(top) }
    companion object { const val EXTRA_VIDEO = "multi_video"; const val EXTRA_ENGINE = "multi_engine" }
}
