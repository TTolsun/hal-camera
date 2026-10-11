package dev.halcamera

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
    private val manager by lazy { getSystemService(CameraManager::class.java) }
    private var videoMode = false
    private var splitLayout = SplitLayout.STACKED
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
    private lateinit var measurements: LiveMeasurementView
    private lateinit var callbackGraph: ResultCallbackGraph
    private lateinit var recentMedia: RecentMediaThumbnail
    private val modeButtons = mutableListOf<Button>()
    private val previews = mutableListOf<TextureView>()
    private val panels = mutableListOf<FrameLayout>()
    private val labels = mutableListOf<TextView>()
    private val focusRings = mutableListOf<FocusRing>()
    private val textures = mutableListOf<SurfaceTexture?>()
    private val retired = mutableListOf<SurfaceTexture>()
    private val states = linkedMapOf<String,String>()
    private val cameraControls = mutableMapOf<String,LiveControls>()
    private val cameraZooms = mutableMapOf<String,Float>()
    private var plans = emptyList<ConcurrentPlan>()
    private var outputSizes = emptyList<LiveSize>()
    private var selected = 0
    private var selectedIds = emptyList<String>()
    private var activeIndex = 0
    private var session: ConcurrentSession? = null
    private var foreground = false
    private var closing = false
    private var exiting = false
    private var failed = false
    private var ready = false
    private var takingPhoto = false
    private var recording = false
    private var videoPending = false
    private var recordingSince = 0L
    private var report = ""
    private var afterClose: (() -> Unit)? = null
    private val busy get() = takingPhoto || recording || videoPending || closing
    private val recorder = FlightRecorder(SystemClock::elapsedRealtimeNanos)
    private val telemetry = Telemetry(recorder)
    private var sessionId = "multi"
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val activeCamera get() = plans.getOrNull(selected)?.streams?.getOrNull(activeIndex)?.camera
    private val activeSessionId get() = "${sessionId}_${activeCamera?.id}"
    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtimeNanos()
            val events = recorder.snapshot()
            val latest = events.lastOrNull { it.session == activeSessionId && it.kind == "capture_result" }
                ?.takeIf { now-it.atNs < 1_500_000_000L }
            val fps = (latest?.values?.get("resultFps") as? Number)?.toDouble()
            measurements.bind(latest?.values.orEmpty(),fps?.let { String.format(Locale.US,"%.1f",it) })
            liveIndicator.bind(ready && latest != null && foreground && !closing)
            liveIndicator.bindStabilization(LiveEisStatus((latest?.values?.get("videoStabilization") as? Number)?.toInt()),recording)
            if (callbackGraph.visibility == View.VISIBLE)
                callbackGraph.update(events,activeSessionId,now,telemetry.sessions[activeSessionId].orEmpty(),
                    telemetry.sessions.filterKeys { it.startsWith("${sessionId}_") && it != activeSessionId })
            if (recording) {
                val seconds = (SystemClock.elapsedRealtime()-recordingSince)/1000
                recordingTime.text = "● REC  ${seconds/60}:${(seconds%60).toString().padStart(2,'0')}"
            }
            recorder.finish()?.let(::exportIncident)
            val remaining = recorder.remainingNs()
            reportButton.text = remaining?.let { "Saving in ${String.format(Locale.US,"%.1f",it/1e9)}s" } ?: "Save Events · ZIP"
            reportButton.isEnabled = ready && remaining == null && !closing
            main.postDelayed(this,100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme); super.onCreate(savedInstanceState)
        videoMode = savedInstanceState?.getBoolean(EXTRA_VIDEO) ?: intent.getBooleanExtra(EXTRA_VIDEO,false)
        splitLayout = SplitLayout.entries.firstOrNull { it.name == savedInstanceState?.getString("layout") } ?: SplitLayout.STACKED
        selectedIds = savedInstanceState?.getStringArrayList("devices").orEmpty()
        WindowCompat.setDecorFitsSystemWindows(window,false)
        WindowInsetsControllerCompat(window,window.decorView).apply {
            isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false
        }
        showPage()
        recentMedia = RecentMediaThumbnail(this) { bitmap,video -> galleryButton.setThumbnail(bitmap,video) }
        onBackPressedDispatcher.addCallback(this,object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (callbackGraph.visibility == View.VISIBLE) showCallbacks(false) else finish()
            }
        })
        if (ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) discover()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun showPage() {
        root = FrameLayout(this).apply { setBackgroundColor(Look.cameraSurface) }
        stage = FrameLayout(this).apply { clipChildren = true }
        root.addView(stage,FrameLayout.LayoutParams(-1,-1))
        stage.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> layoutPreviews() }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.argb(140,0,0,0),Color.TRANSPARENT))
        }
        val controls = row().apply { gravity = Gravity.TOP }
        val leading = row()
        leading.addView(button("Camera2") {}.apply { isEnabled = false },LinearLayout.LayoutParams(-2,dp(48)))
        splitButton = button("Split") { showSplit() }.apply { contentDescription = "Configure Split layout and camera devices" }
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
        liveIndicator = LiveIndicator(this).apply { onSizesClick = { openTool(LiveStreamsActivity::class.java) } }
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
        measurements = LiveMeasurementView(this); bottomBar.addView(measurements,lp())
        zoomControl = ExpandingZoomControl(this) { ratio ->
            if (Build.VERSION.SDK_INT >= 30 && ready && !takingPhoto && !videoPending && !closing) activeCamera?.let {
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
        cameraButton = IconButton(this,R.drawable.ic_camera_select,"Choose Split cameras",filled = true) { showSplit() }
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
                    else if (videoMode != (index == 3)) { videoMode = index == 3; failed = false; closeSession() }
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
        stage.removeAllViews(); previews.clear(); panels.clear(); labels.clear(); focusRings.clear(); textures.clear()
        repeat(count) { textures += null }
        repeat(count) { index ->
            val panel = FrameLayout(this).apply { clipChildren = true; setBackgroundColor(Look.cameraSurface) }
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
                canInteract = { ready && !takingPhoto && !videoPending && !closing }
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
                setPadding(dp(12),0,dp(12),0); setBackgroundColor(Look.cameraGlass); isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END; setOnClickListener { selectActive(index) }
            }
            labels += label; panel.addView(label,FrameLayout.LayoutParams(-1,dp(48),Gravity.TOP)); stage.addView(panel)
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
            plans = readConcurrentPlans(manager,Int.MAX_VALUE)
            selected = plans.indexOfFirst { it.streams.map { stream -> stream.camera.id }.toSet() == selectedIds.toSet() }.coerceAtLeast(0)
            if (plans.isEmpty()) { ready = false; status.visibility = View.VISIBLE; status.text = "Multi is unavailable on this device." }
            else {
                val plan = plans[selected]
                if (selectedIds.toSet() == plan.streams.map { it.camera.id }.toSet())
                    plans = plans.toMutableList().also { it[selected] = plan.copy(streams = selectedIds.map { id -> plan.streams.first { it.camera.id == id } }) }
                else selectedIds = plan.streams.map { it.camera.id }
                activeIndex = activeIndex.coerceIn(plan.streams.indices); startIfReady()
            }
        } catch (e: Exception) { status.text = e.message; status.visibility = View.VISIBLE; failed = true }
        updateButtons()
    }

    private fun showSplit() {
        if (busy || plans.none { it.advertised }) return
        SplitChoiceSheet(this).show(plans,selectedIds,splitLayout) { layout, ids ->
            if (busy) false else {
                val changed = selectedIds != ids
                splitLayout = layout; selectedIds = ids
                if (changed) { failed = false; activeIndex = 0; closeSession() } else layoutPreviews()
                true
            }
        }
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
        val stream = plans[selected].streams[index]
        liveIndicator.bindSizes(mapOf("preview" to stream.preview.toString(),"jpeg" to stream.photo.toString()))
        labels.forEachIndexed { i,label ->
            label.text = "${if (i == index) "● " else ""}${i+1} · ${plans[selected].streams[i].camera.label}"
            label.contentDescription = "${label.text}. Tap to control this camera"
            ViewCompat.setStateDescription(label,if (i == index) "Selected" else "Not selected")
        }
        updateButtons()
    }

    private fun startIfReady() {
        if (Build.VERSION.SDK_INT < 30 || !foreground || exiting || closing || failed || session != null || plans.isEmpty()) return
        val plan = plans[selected]
        if (textures.size != plan.streams.size) { createPanels(plan.streams.size); return }
        if (textures.any { it == null } || previews.any { it.width == 0 || it.height == 0 }) return
        outputSizes = plan.streams.map { stream ->
            concurrentOutputSize(stream.preview,manager.getCameraCharacteristics(stream.camera.id)[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0)
        }
        states.clear(); cameraControls.clear(); cameraZooms.clear(); ready = false; report = ""; resultText.text = ""
        status.visibility = View.VISIBLE; status.text = "Opening cameras…"; sessionId = "multi_${UUID.randomUUID()}"
        lateinit var current: ConcurrentSession
        current = ConcurrentSession(this,manager,plan,textures.map { checkNotNull(it) },object : ConcurrentSession.Listener {
            override fun onState(id: String,state: String) { if (session === current) states[id] = state }
            override fun onReady() {
                if (session !== current || closing || !foreground || failed) return
                ready = true; previews.forEach { it.alpha = 1f }; status.visibility = View.GONE
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); selectActive(activeIndex); updateButtons()
            }
            override fun onFailed(reason: String) {
                if (session !== current) return
                failed = true; ready = false; recording = false; videoPending = false; takingPhoto = false
                status.text = reason; status.visibility = View.VISIBLE; previews.forEach { it.alpha = 0f }
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); updateButtons()
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
                if (active) recordingSince = SystemClock.elapsedRealtime()
                resultText.text = if (active) "" else if (result.failed) "Video failed · Details" else "Videos saved · Details"
                updateButtons()
            }
        },outputSizes = outputSizes,telemetry = telemetry,sessionId = sessionId,videoMode = videoMode)
        session = current; layoutPreviews(); current.start(); updateButtons()
    }

    @SuppressLint("NewApi")
    private fun closeSession() {
        if (closing) return
        closing = true; ready = false; previews.forEach { it.alpha = 0f }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); updateButtons()
        val done = {
            session = null; closing = false; takingPhoto = false; recording = false; videoPending = false
            retired.forEach { it.release() }; retired.clear()
            val destination = afterClose; afterClose = null
            if (exiting) super.finish() else if (destination != null) destination() else { updateButtons(); if (foreground) discover() }
        }
        session?.close(done) ?: done()
    }
    private fun capture() {
        if (Build.VERSION.SDK_INT < 30 || !ready || takingPhoto || videoPending || closing) return
        if (videoMode) { videoPending = true; if (recording) session?.stopVideo() else session?.startVideo() }
        else if (!recording) takePhoto()
        updateButtons()
    }
    private fun takePhoto() {
        if (Build.VERSION.SDK_INT < 30 || !ready || takingPhoto || videoPending || closing) return
        takingPhoto = true; resultText.text = "Capturing…"; session?.capture(displayDegrees()); updateButtons()
    }
    private fun leave(video: Boolean) { setResult(RESULT_OK,Intent().putExtra(DualPreviewActivity.EXTRA_RETURN_VIDEO,video)); finish() }
    private fun openTool(destination: Class<*>) {
        if (busy) return
        val id = activeCamera?.id ?: return
        if (destination == LiveStreamsActivity::class.java) {
            LiveChoiceSheet.show(this,"Streams",content = text(states.entries.joinToString("\n") { "Camera ${it.key}: ${it.value}" },12))
            return
        }
        afterClose = { startActivity(Intent(this,destination)
            .putExtra(WorkbenchActivity.EXTRA_CAMERA_ID,id).putExtra(WorkbenchActivity.EXTRA_ENGINE,"Camera2")) }
        closeSession()
    }
    override fun onStart() {
        super.onStart(); foreground = true; recentMedia.start(); main.post(tick)
        if (session == null && !closing && ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) discover() else startIfReady()
    }
    override fun onStop() {
        foreground = false; main.removeCallbacks(tick); recentMedia.stop(); recorder.finish("screen_stopped")?.let(::exportIncident)
        closeSession(); super.onStop()
    }
    override fun onDestroy() { recentMedia.close(); io.shutdown(); super.onDestroy() }
    override fun finish() { exiting = true; closeSession() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(EXTRA_VIDEO,videoMode); outState.putString("layout",splitLayout.name)
        outState.putStringArrayList("devices",ArrayList(selectedIds)); super.onSaveInstanceState(outState)
    }
    private fun updateButtons() {
        shutter.isEnabled = ready && !takingPhoto && !videoPending && !closing
        shutter.setCaptureState(videoMode = videoMode,recording = recording)
        shutter.contentDescription = if (videoMode) { if (recording) "Stop all recordings" else "Record each camera separately" } else "Capture a separate photo from each camera"
        splitButton.isEnabled = plans.isNotEmpty() && !busy; cameraButton.isEnabled = splitButton.isEnabled
        cameraButton.visibility = if (recording) View.GONE else View.VISIBLE
        snapshotButton.visibility = if (recording) View.VISIBLE else View.GONE
        snapshotButton.isEnabled = ready && recording && !takingPhoto && !videoPending && !closing
        labButton.isEnabled = !busy && activeCamera != null; galleryButton.isEnabled = !busy
        retryButton.visibility = if (failed) View.VISIBLE else View.GONE; retryButton.isEnabled = !busy
        reportButton.isEnabled = ready && recorder.remainingNs() == null && !closing
        resultText.isClickable = report.isNotEmpty() && !recording; resultText.isFocusable = resultText.isClickable
        controlBar.bind(videoMode,ready && !takingPhoto && !videoPending && !closing)
        zoomControl.isEnabled = ready && !takingPhoto && !videoPending && !closing; liveIndicator.setSizesEnabled(!busy)
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
        if (stage.width <= 0 || stage.height <= 0 || panels.isEmpty()) return
        val top = topBar.bottom
        val frames = concurrentFrames(panels.size,stage.width,(captureChrome.top-top).coerceAtLeast(1),splitLayout)
        panels.forEachIndexed { i,panel ->
            val f = frames[i]; val old = panel.layoutParams as? FrameLayout.LayoutParams
            if (old == null || old.width != f.width || old.height != f.height || old.topMargin != top+f.top || old.leftMargin != f.left)
                panel.layoutParams = FrameLayout.LayoutParams(f.width,f.height).apply { topMargin = top+f.top; leftMargin = f.left }
            transform(i)
            outputSizes.getOrNull(i)?.let { size ->
                val fit = fitConcurrentFrame(f.width,f.height,size.width.toFloat()/size.height)
                focusRings.getOrNull(i)?.let { ring ->
                    val params = ring.layoutParams as FrameLayout.LayoutParams
                    if (params.width != fit.width || params.height != fit.height || params.leftMargin != fit.left || params.topMargin != fit.top)
                        ring.layoutParams = FrameLayout.LayoutParams(fit.width,fit.height).apply { leftMargin = fit.left; topMargin = fit.top }
                }
            }
        }
        // Returning from another screen can expose textures before their views have dimensions.
        stage.post { startIfReady() }
    }
    private fun transform(index: Int) {
        if (outputSizes.getOrNull(index) == null) return
        val view = previews.getOrNull(index) ?: return
        if (view.width <= 0 || view.height <= 0) return
        // EGL fits the full source to the actual native window. A second TextureView fit would shrink it twice.
        view.setTransform(Matrix())
    }
    private fun showCallbacks(show: Boolean) {
        callbackGraph.visibility = if (show) View.VISIBLE else View.GONE; measurements.visibility = if (show) View.GONE else View.VISIBLE
        CameraWidgets(this).highlight(callbackButton,show)
    }
    private fun exportIncident(incident: dev.halcamera.telemetry.Incident) {
        val metadata = telemetry.sessions.toMap()
        io.execute {
            val result = runCatching { IncidentExporter(applicationContext).export(incident,metadata) }
            main.post {
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
    private fun displayDegrees() = when (windowManager.defaultDisplay.rotation) { 1 -> 90; 2 -> 180; 3 -> 270; else -> 0 }
    private fun dp(value: Int) = Look.dp(this,value)
    private fun text(value: String,size: Int) = Look.text(this,value,size,Look.onDark).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun button(value: String,action: () -> Unit) = CameraWidgets(this).chromeButton(value,action)
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(top) }
    companion object { const val EXTRA_VIDEO = "multi_video" }
}
