package dev.halcamera

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.halcamera.camera.*
import dev.halcamera.ui.Look
import dev.halcamera.ui.CameraWidgets

/** Multi owns independent devices; each device may compose its own physical PIP inputs. */
class ConcurrentCameraActivity : ComponentActivity() {
    private val manager by lazy { getSystemService(CameraManager::class.java) }
    private val singleId by lazy { intent.getStringExtra(EXTRA_SINGLE_ID) }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) discover() }
    private lateinit var stage: FrameLayout
    private lateinit var status: TextView
    private lateinit var resultText: TextView
    private lateinit var captureButton: Button
    private lateinit var recordButton: Button
    private lateinit var pairButton: Button
    private lateinit var retryButton: Button
    private lateinit var streamsButton: Button
    private var headerPip: Button? = null
    private val previews = mutableListOf<TextureView>()
    private val panels = mutableListOf<FrameLayout>()
    private val labels = mutableListOf<TextView>()
    private val pipButtons = mutableListOf<Button>()
    private val moveButtons = mutableListOf<Button>()
    private val textures = mutableListOf<SurfaceTexture?>()
    private val retired = mutableListOf<SurfaceTexture>()
    private val physical = mutableMapOf<String,List<String>>()
    private val positions = mutableMapOf<String,MutableList<PipRect>>()
    private val states = linkedMapOf<String,String>()
    private var plans = emptyList<ConcurrentPlan>()
    private var outputSizes = emptyList<LiveSize>()
    private var selected = 0
    private var loadedLimit: Int? = null
    private var session: ConcurrentSession? = null
    private var foreground = false
    private var closing = false
    private var exiting = false
    private var failed = false
    private var ready = false
    private var takingPhoto = false
    private var recording = false
    private var videoPending = false
    private var report = ""
    private var afterClose: (() -> Unit)? = null
    private val busy get() = takingPhoto || recording || videoPending || closing

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        singleId?.let { physical[it] = intent.getStringArrayListExtra(EXTRA_PHYSICAL_IDS).orEmpty() }
        savedInstanceState?.getBundle("physical")?.let { saved -> saved.keySet().forEach { physical[it] = saved.getStringArrayList(it).orEmpty() } }
        val root = row().apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Look.cameraSurface) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left+dp(12),bars.top,bars.right+dp(12),bars.bottom); insets
        }
        val header = row()
        header.addView(button("Live") { finish() },LinearLayout.LayoutParams(dp(68),dp(48)))
        header.addView(text(if (singleId == null) "Multi" else "Camera2",18).apply { setPadding(dp(12),0,0,0) },LinearLayout.LayoutParams(0,dp(48),1f))
        if (singleId != null) {
            headerPip = button("PIP") { choosePhysical(0) }
            header.addView(headerPip,LinearLayout.LayoutParams(dp(56),dp(48)))
        }
        streamsButton = button("Streams") {
            val id = plans.getOrNull(selected)?.streams?.firstOrNull()?.camera?.id ?: return@button
            afterClose = { startActivity(Intent(this,LiveStreamsActivity::class.java)
                .putExtra(WorkbenchActivity.EXTRA_CAMERA_ID,id).putExtra(WorkbenchActivity.EXTRA_ENGINE,"Camera2")
                .putExtra(LiveStreamsActivity.EXTRA_MULTI,true).putExtra(LiveStreamsActivity.EXTRA_FROM_LIVE,true)) }
            closeSession()
        }
        header.addView(streamsButton,LinearLayout.LayoutParams(dp(80),dp(48))); root.addView(header)
        pairButton = button("Cameras") { chooseCameras() }
        pairButton.visibility = if (singleId == null) View.VISIBLE else View.GONE
        root.addView(pairButton,LinearLayout.LayoutParams(-1,dp(48)))
        status = text("Opening cameras…",12)
        stage = FrameLayout(this).apply { setBackgroundColor(Look.cameraCard); clipChildren = true }
        stage.addView(status,FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply { topMargin = dp(8) })
        root.addView(stage,LinearLayout.LayoutParams(-1,0,1f))
        stage.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> layoutPreviews() }
        resultText = text("",12).apply { minHeight = dp(48); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setOnClickListener { if (report.isNotEmpty()) AlertDialog.Builder(this@ConcurrentCameraActivity).setTitle("Capture details").setMessage(report).setPositiveButton("OK",null).show() }
        }
        val feedback = row()
        feedback.addView(resultText,LinearLayout.LayoutParams(0,dp(48),1f))
        retryButton = button("Retry") { failed = false; closeSession() }
        feedback.addView(retryButton,LinearLayout.LayoutParams(dp(64),dp(48)))
        root.addView(feedback)
        val actions = row()
        recordButton = button("Record") {
            if (Build.VERSION.SDK_INT < 30) return@button
            videoPending = true; updateButtons()
            if (recording) session?.stopVideo() else session?.startVideo()
        }
        captureButton = Look.galleryButton(this,"Photo",primary = true) {
            if (Build.VERSION.SDK_INT >= 30 && ready && !busy) { takingPhoto = true; resultText.text = "Capturing…"; updateButtons(); session?.capture(0) }
        }
        actions.addView(recordButton,weight()); actions.addView(captureButton,weight())
        root.addView(actions); setContentView(root)
        onBackPressedDispatcher.addCallback(this,object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = finish() })
        updateButtons()
        if (ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) discover()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun createPanels(count: Int) {
        previews.forEach { it.surfaceTextureListener = null }; stage.removeAllViews()
        previews.clear(); panels.clear(); labels.clear(); textures.clear(); pipButtons.clear(); moveButtons.clear()
        repeat(count) { textures += null }
        repeat(count) { index ->
            val panel = FrameLayout(this).apply { clipChildren = true; clipToPadding = true }
            val preview = TextureView(this).apply { alpha = 0f }
            previews += preview; panels += panel
            panel.addView(preview,FrameLayout.LayoutParams(-1,-1).apply { bottomMargin = dp(48) })
            val controls = row().apply { setBackgroundColor(Look.cameraGlass) }
            val label = text("",13); labels += label
            controls.addView(label,LinearLayout.LayoutParams(0,dp(48),1f))
            val pip = button("PIP") { choosePhysical(index) }; pipButtons += pip
            if (singleId == null) controls.addView(pip,LinearLayout.LayoutParams(dp(64),dp(48)))
            val move = button("Move") { choosePosition(index) }; moveButtons += move
            controls.addView(move,LinearLayout.LayoutParams(dp(64),dp(48)))
            panel.addView(controls,FrameLayout.LayoutParams(-1,dp(48),Gravity.BOTTOM)); stage.addView(panel)
            preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture,width: Int,height: Int) { textures[index] = texture; startIfReady(); transform(index) }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture,width: Int,height: Int) = transform(index)
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    textures[index] = null; retired += texture; closeSession(); return false
                }
            }
            installDrag(preview,index)
        }
        stage.addView(status,FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply { topMargin = dp(8) })
        status.setBackgroundColor(Look.cameraGlass)
        layoutPreviews(); updateButtons()
    }

    private fun discover() {
        if (Build.VERSION.SDK_INT < 30) { status.text = "Requires Android 11 or later."; return }
        try {
            val limit = LiveStreamsActivity.savedMultiLimit(this)
            if (loadedLimit != limit) { selected = 0; failed = false }
            loadedLimit = limit
            plans = singleId?.let { listOf(readSingleCompositionPlan(manager,it)) }
                ?: readConcurrentPlans(manager,limit.takeIf { it > 0 } ?: Int.MAX_VALUE)
            if (plans.isEmpty()) status.text = "Multi is unavailable on this device."
            else { selected = selected.coerceIn(plans.indices); pairButton.text = plans[selected].label; startIfReady() }
        } catch (e: Exception) { status.text = e.message; failed = true }
        updateButtons()
    }

    private fun chooseCameras() {
        if (busy || plans.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Cameras").setSingleChoiceItems(plans.map { it.label }.toTypedArray(),selected) { dialog,index ->
            selected = index; pairButton.text = plans[index].label; failed = false; resultText.text = ""; report = ""
            closeSession(); dialog.dismiss()
        }.setNeutralButton("Details") { _,_ -> AlertDialog.Builder(this).setTitle("Streams")
            .setMessage(states.entries.joinToString("\n") { "Camera ${it.key}: ${it.value}" }).setPositiveButton("OK",null).show()
        }.setNegativeButton("Cancel",null).show()
    }

    private fun choosePhysical(index: Int) {
        val camera = plans.getOrNull(selected)?.streams?.getOrNull(index)?.camera ?: return
        if (busy || camera.physicalIds.isEmpty()) return
        val chosen = camera.physicalIds.map { it in physical[camera.id].orEmpty() }.toBooleanArray()
        AlertDialog.Builder(this).setTitle("Camera ${camera.id} · PIP")
            .setMultiChoiceItems(camera.physicalIds.map { "Physical $it" }.toTypedArray(),chosen) { _,i,value -> chosen[i] = value }
            .setPositiveButton("Apply") { _,_ ->
                physical[camera.id] = camera.physicalIds.filterIndexed { i,_ -> chosen[i] }
                positions.remove(camera.id); failed = false; closeSession()
            }.setNeutralButton("Off") { _,_ -> physical.remove(camera.id); positions.remove(camera.id); failed = false; closeSession() }
            .setNegativeButton("Cancel",null).show()
    }

    private fun choosePosition(index: Int) {
        if (Build.VERSION.SDK_INT < 30) return
        val id = plans.getOrNull(selected)?.streams?.getOrNull(index)?.camera?.id ?: return
        val ids = physical[id].orEmpty()
        if (ids.isEmpty() || takingPhoto || videoPending || closing) return
        AlertDialog.Builder(this).setTitle("Move physical preview").setItems(ids.map { "Physical $it" }.toTypedArray()) { _,i ->
            val frames = positions.getOrPut(id) { PipScene.initial(ids.size).toMutableList() }
            val old = frames[i]
            frames[i] = when {
                old.x >= .5f && old.y < .5f -> old.moved(1f,1f)
                old.x >= .5f -> old.moved(0f,1f)
                old.y >= .5f -> old.moved(0f,0f)
                else -> old.moved(1f,0f)
            }
            session?.movePhysical(id,i,frames[i])
        }.setNegativeButton("Cancel",null).show()
    }

    private fun startIfReady() {
        if (Build.VERSION.SDK_INT < 30 || !foreground || exiting || closing || failed || session != null || plans.isEmpty()) return
        val plan = plans[selected]
        if (textures.size != plan.streams.size) { createPanels(plan.streams.size); return }
        if (textures.any { it == null } || previews.any { it.width == 0 || it.height == 0 }) return
        outputSizes = previews.map {
            val scale = minOf(1f,1280f/maxOf(it.width,it.height))
            LiveSize(((it.width*scale).toInt()/2*2).coerceAtLeast(2),((it.height*scale).toInt()/2*2).coerceAtLeast(2))
        }
        states.clear(); ready = false; report = ""; resultText.text = ""
        status.visibility = View.VISIBLE; status.text = "Opening cameras…"
        plan.streams.forEachIndexed { i,stream -> labels[i].text = stream.camera.label }
        lateinit var current: ConcurrentSession
        current = ConcurrentSession(this,manager,plan,textures.map { checkNotNull(it) },object : ConcurrentSession.Listener {
            override fun onState(id: String,state: String) { if (session === current) states[id] = state }
            override fun onReady() {
                if (session !== current || closing || !foreground || failed) return
                ready = true; previews.forEach { it.alpha = 1f }; status.visibility = View.GONE
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); updateButtons()
                positions.forEach { (id,frames) -> frames.forEachIndexed { i,rect -> current.movePhysical(id,i,rect) } }
            }
            override fun onFailed(reason: String) {
                if (session !== current) return
                failed = true; ready = false; recording = false; videoPending = false
                status.text = reason; status.visibility = View.VISIBLE; previews.forEach { it.alpha = 0f }
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); updateButtons()
            }
            override fun onPhoto(message: String) {
                if (session !== current) return
                takingPhoto = false; report = message
                resultText.text = if (message.contains("failed",true) || message.startsWith("Could not")) "Photo failed · Details" else "Photos saved · Details"
                updateButtons()
            }
            override fun onRecording(active: Boolean,message: String) {
                if (session !== current) return
                recording = active; videoPending = false; report = message
                resultText.text = if (active) "Recording" else if (message.contains("failed",true) || message.startsWith("Could not")) "Video failed · Details" else "Videos saved · Details"
                updateButtons()
            }
        },physical.toMap(),outputSizes)
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
    override fun onStart() {
        super.onStart(); foreground = true
        if (session == null && !closing && ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) discover() else startIfReady()
    }
    override fun onStop() { foreground = false; closeSession(); super.onStop() }
    override fun finish() { exiting = true; closeSession() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("physical",Bundle().apply { physical.forEach { (id,ids) -> putStringArrayList(id,ArrayList(ids)) } })
        super.onSaveInstanceState(outState)
    }
    private fun updateButtons() {
        captureButton.isEnabled = ready && !busy
        recordButton.isEnabled = ready && !takingPhoto && !videoPending && !closing
        recordButton.text = if (recording) "Stop" else "Record"
        pairButton.isEnabled = plans.isNotEmpty() && !busy; streamsButton.isEnabled = pairButton.isEnabled
        retryButton.visibility = if (failed) View.VISIBLE else View.INVISIBLE; retryButton.isEnabled = !busy
        resultText.isClickable = report.isNotEmpty() && !recording
        resultText.isFocusable = resultText.isClickable
        val streams = plans.getOrNull(selected)?.streams.orEmpty()
        pipButtons.forEachIndexed { i,button -> button.isEnabled = !busy && streams.getOrNull(i)?.camera?.physicalIds?.isNotEmpty() == true
            button.alpha = if (button.isEnabled) 1f else .4f
            val active = !physical[streams.getOrNull(i)?.camera?.id].isNullOrEmpty()
            button.isSelected = active
            button.text = if (active) "PIP ✓" else "PIP"
            button.setTextColor(if (active) Look.primaryOnDark else Look.onDark)
            button.contentDescription = if (active) "PIP on, select physical cameras" else "PIP, select physical cameras" }
        headerPip?.isEnabled = !busy && streams.firstOrNull()?.camera?.physicalIds?.isNotEmpty() == true
        headerPip?.alpha = if (headerPip?.isEnabled == true) 1f else .4f
        headerPip?.setTextColor(if (!physical[streams.firstOrNull()?.camera?.id].isNullOrEmpty()) Look.primaryOnDark else Look.onDark)
        headerPip?.isSelected = pipButtons.firstOrNull()?.isSelected == true
        headerPip?.text = pipButtons.firstOrNull()?.text ?: "PIP"
        headerPip?.contentDescription = pipButtons.firstOrNull()?.contentDescription
        moveButtons.forEachIndexed { i,button -> button.visibility = if (physical[streams.getOrNull(i)?.camera?.id].isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
            button.isEnabled = ready && !takingPhoto && !videoPending && !closing }
    }
    private fun layoutPreviews() {
        if (stage.width <= 0 || stage.height <= 0 || panels.isEmpty()) return
        val frames = concurrentFrames(panels.size,stage.width,stage.height,0,true,.32f,1f,0f)
        panels.forEachIndexed { i,panel ->
            val f = frames[i]; val old = panel.layoutParams as? FrameLayout.LayoutParams
            if (old == null || old.width != f.width || old.height != f.height || old.topMargin != f.top)
                panel.layoutParams = FrameLayout.LayoutParams(f.width,f.height).apply { topMargin = f.top }
            transform(i)
        }
    }
    /** Fit the full saved scene; never crop an inset out of the on-screen logical canvas. */
    private fun transform(index: Int) {
        val size = outputSizes.getOrNull(index) ?: return
        val view = previews[index]; if (view.width <= 0 || view.height <= 0) return
        val scale = minOf(view.width.toFloat()/size.width,view.height.toFloat()/size.height)
        view.setTransform(Matrix().apply { setScale(size.width*scale/view.width,size.height*scale/view.height,view.width/2f,view.height/2f) })
    }
    @SuppressLint("ClickableViewAccessibility")
    private fun installDrag(view: TextureView,index: Int) {
        var hit = -1; var originX = 0f; var originY = 0f; var original: PipRect? = null
        view.setOnTouchListener { _,event ->
            if (Build.VERSION.SDK_INT < 30) return@setOnTouchListener false
            val id = plans.getOrNull(selected)?.streams?.getOrNull(index)?.camera?.id ?: return@setOnTouchListener false
            val ids = physical[id].orEmpty(); val size = outputSizes.getOrNull(index) ?: return@setOnTouchListener false
            if (ids.isEmpty() || !ready || takingPhoto || closing) return@setOnTouchListener false
            val scale = minOf(view.width.toFloat()/size.width,view.height.toFloat()/size.height)
            val x = (event.x-(view.width-size.width*scale)/2)/(size.width*scale)
            val y = (event.y-(view.height-size.height*scale)/2)/(size.height*scale)
            val frames = positions.getOrPut(id) { PipScene.initial(ids.size).toMutableList() }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { hit = frames.indexOfLast { it.contains(x,y) }; originX = x; originY = y; original = frames.getOrNull(hit); hit >= 0 }
                MotionEvent.ACTION_MOVE -> {
                    val old = original ?: return@setOnTouchListener false
                    frames[hit] = old.moved(old.x+x-originX,old.y+y-originY); session?.movePhysical(id,hit,frames[hit]); true
                }
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> { original = null; if (hit >= 0) view.performClick(); hit >= 0 }
                else -> hit >= 0
            }
        }
    }
    private fun dp(value: Int) = Look.dp(this,value)
    private fun text(value: String,size: Int) = Look.text(this,value,size,Look.onDark).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun button(value: String,action: () -> Unit) = CameraWidgets(this).button(value,action).apply {
        background = CameraWidgets(this@ConcurrentCameraActivity).chrome(android.graphics.Color.TRANSPARENT)
        textSize = 13f; setPadding(dp(6),0,dp(6),0); setSingleLine(true)
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun weight() = LinearLayout.LayoutParams(0,dp(56),1f).apply { setMargins(dp(2),dp(4),dp(2),dp(4)) }
    companion object {
        const val EXTRA_SINGLE_ID = "single_logical_id"
        const val EXTRA_PHYSICAL_IDS = "pip_physical_ids"
    }
}
