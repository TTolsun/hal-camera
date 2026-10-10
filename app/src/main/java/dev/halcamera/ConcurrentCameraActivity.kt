package dev.halcamera

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.halcamera.camera.concurrentFrames
import dev.halcamera.camera.ConcurrentPlan
import dev.halcamera.camera.ConcurrentSession
import dev.halcamera.camera.fitPreview
import dev.halcamera.camera.readConcurrentPlans
import dev.halcamera.ui.Look

/** Multi mode opens independent camera devices and saves each camera separately. */
class ConcurrentCameraActivity : ComponentActivity() {
    private val manager by lazy { getSystemService(CameraManager::class.java) }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) discover() else status.text = "Camera permission denied. Return to Live to grant access."
    }
    private lateinit var stage: FrameLayout
    private lateinit var status: TextView
    private lateinit var resultText: TextView
    private lateinit var captureButton: Button
    private lateinit var pairButton: Button
    private lateinit var retryButton: Button
    private lateinit var sizeControl: SeekBar
    private lateinit var sizing: LinearLayout
    private lateinit var layoutButton: Button
    private lateinit var moveButton: Button
    private var photoReport = ""
    private var afterClose: (() -> Unit)? = null
    private var loadedLimit: Int? = null
    private lateinit var streamsButton: Button
    private val previews = mutableListOf<TextureView>()
    private val panels = mutableListOf<FrameLayout>()
    private val labels = mutableListOf<TextView>()
    private val textures = mutableListOf<SurfaceTexture?>()
    private val retired = mutableListOf<SurfaceTexture>()
    private val states = linkedMapOf<String, String>()
    private var plans = emptyList<ConcurrentPlan>()
    private var selected = 0
    private var session: ConcurrentSession? = null
    private var foreground = false
    private var closing = false
    private var exiting = false
    private var failed = false
    private var ready = false
    private var takingPhoto = false
    private var split = false
    private var primary = 0
    private var pipScale = 0.36f
    private var pipX = 1f
    private var pipY = 0.08f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        split = savedInstanceState?.getBoolean("split") ?: false
        primary = savedInstanceState?.getInt("primary") ?: 0
        pipScale = savedInstanceState?.getFloat("scale") ?: 0.36f
        pipX = savedInstanceState?.getFloat("x") ?: 1f
        pipY = savedInstanceState?.getFloat("y") ?: 0.08f
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Look.cameraSurface) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left + dp(12), bars.top, bars.right + dp(12), bars.bottom)
            insets
        }
        val header = row()
        header.addView(button("Live") { finish() }, LinearLayout.LayoutParams(dp(72), dp(48)))
        header.addView(text("Multi", 18).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        streamsButton = button("Streams") {
            val id = plans.getOrNull(selected)?.streams?.firstOrNull()?.camera?.id ?: return@button
            afterClose = {
                startActivity(android.content.Intent(this, LiveStreamsActivity::class.java)
                    .putExtra(WorkbenchActivity.EXTRA_CAMERA_ID, id)
                    .putExtra(WorkbenchActivity.EXTRA_ENGINE, "Camera2")
                    .putExtra(LiveStreamsActivity.EXTRA_MULTI, true)
                    .putExtra(LiveStreamsActivity.EXTRA_FROM_LIVE, true))
            }
            closeSession()
        }
        header.addView(streamsButton, LinearLayout.LayoutParams(dp(84), dp(48)))
        root.addView(header)
        pairButton = button("Cameras") { choosePair() }
        root.addView(pairButton, LinearLayout.LayoutParams(-1, dp(48)))
        status = text("Checking concurrent camera support…", 12)
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        stage = FrameLayout(this).apply { setBackgroundColor(Look.cameraCard) }
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutPreviews() }
        val layouts = row()
        layoutButton = button("Split") { split = !split; layoutPreviews() }
        layouts.addView(layoutButton, weight())
        layouts.addView(button("Swap") { primary = (primary + 1) % panels.size.coerceAtLeast(1); layoutPreviews() }, weight())
        moveButton = button("Move inset") {
            when {
                pipX >= .5f && pipY < .5f -> { pipX = 1f; pipY = 1f }
                pipX >= .5f -> { pipX = 0f; pipY = 1f }
                pipY >= .5f -> { pipX = 0f; pipY = 0f }
                else -> { pipX = 1f; pipY = 0f }
            }
            layoutPreviews()
        }
        layouts.addView(moveButton, weight())
        root.addView(layouts)
        sizing = row()
        sizing.addView(text("Inset size", 12), LinearLayout.LayoutParams(dp(76), dp(48)))
        sizeControl = SeekBar(this).apply {
            contentDescription = "Inset preview size"
            max = 25; progress = ((pipScale - .25f) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    pipScale = .25f + value / 100f; layoutPreviews()
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        sizing.addView(sizeControl, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(sizing)
        resultText = text("", 12)
        resultText.maxLines = 1
        resultText.ellipsize = android.text.TextUtils.TruncateAt.END
        resultText.minHeight = dp(48)
        resultText.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        resultText.setOnClickListener {
            if (photoReport.isNotEmpty()) AlertDialog.Builder(this).setTitle("Capture details").setMessage(photoReport).setPositiveButton("OK", null).show()
        }
        resultText.isClickable = false
        root.addView(resultText, LinearLayout.LayoutParams(-1, -2))
        val actions = row()
        retryButton = button("Retry") { failed = false; if (plans.isEmpty()) discover() else closeSession() }
        captureButton = Look.galleryButton(this, "Take photos", primary = true) {
            if (ready && !takingPhoto && !closing && Build.VERSION.SDK_INT >= 30) {
                takingPhoto = true; updateButtons()
                photoReport = ""; resultText.isClickable = false
                resultText.text = "Capturing…"
                resultText.contentDescription = null
                @Suppress("DEPRECATION")
                val degrees = when (windowManager.defaultDisplay.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
                session?.capture(degrees)
            }
        }
        captureButton.contentDescription = "Take photos with selected cameras"
        actions.addView(retryButton, weight()); actions.addView(captureButton, LinearLayout.LayoutParams(0, dp(56), 1f))
        root.addView(actions)
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })
        updateButtons()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) discover()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun createPanels(count: Int) {
        previews.forEach { it.surfaceTextureListener = null }
        stage.removeAllViews()
        previews.clear(); panels.clear(); labels.clear(); textures.clear()
        repeat(count) { textures += null; }
        primary = primary.coerceIn(0, count - 1)
        repeat(count) { index ->
            val panel = FrameLayout(this)
            val preview = TextureView(this).apply { alpha = 0f }
            previews += preview; panels += panel
            panel.addView(preview, FrameLayout.LayoutParams(-1, -1))
            val label = text("", 13).apply { setBackgroundColor(Look.cameraGlass) }
            labels += label
            panel.addView(label, FrameLayout.LayoutParams(-1, dp(32), Gravity.BOTTOM))
            stage.addView(panel)
            preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    textures[index] = texture; startIfReady(); transform(index)
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = transform(index)
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    textures[index] = null
                    retired += texture
                    closeSession()
                    return false
                }
            }
            installDrag(panel, index)
        }
        layoutPreviews()
    }

    private fun discover() {
        if (Build.VERSION.SDK_INT < 30) { status.text = "Requires Android 11 or later."; return }
        try {
            val limit = LiveStreamsActivity.savedMultiLimit(this)
            if (loadedLimit != limit) { selected = 0; failed = false }
            loadedLimit = limit
            plans = readConcurrentPlans(manager, limit.takeIf { it > 0 } ?: Int.MAX_VALUE)
            if (plans.isEmpty()) status.text = "Multi is unavailable on this device."
            else { selected = selected.coerceIn(plans.indices); pairButton.text = plans[selected].label; startIfReady() }
        } catch (e: Exception) { status.text = "Camera support query failed: ${e.message}"; failed = true }
        updateButtons()
    }

    private fun choosePair() {
        if (takingPhoto || closing || plans.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Cameras")
            .setSingleChoiceItems(plans.map { it.label }.toTypedArray(), selected) { dialog, index ->
                selected = index; pairButton.text = plans[index].label; failed = false
                photoReport = ""; resultText.text = ""; resultText.isClickable = false
                closeSession(); dialog.dismiss()
            }.setNeutralButton("Details") { _, _ ->
                AlertDialog.Builder(this).setTitle("Camera2 streams")
                    .setMessage(states.entries.joinToString("\n") { "Camera ${it.key}: ${it.value}" })
                    .setPositiveButton("OK", null).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun startIfReady() {
        if (Build.VERSION.SDK_INT < 30 || !foreground || exiting || closing || failed || session != null || plans.isEmpty()) return
        val plan = plans[selected]
        if (textures.size != plan.streams.size) { createPanels(plan.streams.size); return }
        if (textures.any { it == null }) return
        states.clear(); ready = false
        status.visibility = View.VISIBLE
        status.text = "Opening cameras…"
        plan.streams.forEachIndexed { index, stream ->
            labels[index].text = stream.camera.label
        }
        lateinit var current: ConcurrentSession
        current = ConcurrentSession(this, manager, plan, textures.map { checkNotNull(it) }, object : ConcurrentSession.Listener {
            override fun onState(id: String, state: String) {
                if (session !== current) return
                states[id] = state
            }
            override fun onReady() {
                if (session !== current || closing || !foreground || failed) return
                ready = true
                previews.forEach { it.alpha = 1f }
                status.visibility = View.GONE
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                updateButtons()
            }
            override fun onFailed(reason: String) {
                if (session !== current) return
                failed = true; ready = false; status.text = reason; status.visibility = View.VISIBLE
                previews.forEach { it.alpha = 0f }
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                updateButtons()
            }
            override fun onPhoto(message: String) {
                if (session !== current) return
                takingPhoto = false; photoReport = message
                val summary = message.lineSequence().first()
                resultText.text = if (summary.contains("failed", ignoreCase = true)) "Photo failed · Details" else if (summary.contains("saved")) "Photos saved · Details" else "Couldn’t save photos · Details"
                resultText.contentDescription = resultText.text
                resultText.isClickable = true
                updateButtons()
            }
        })
        session = current
        layoutPreviews()
        current.start()
        updateButtons()
    }

    @SuppressLint("NewApi")
    private fun closeSession() {
        if (closing) return
        closing = true; ready = false
        previews.forEach { it.alpha = 0f }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateButtons()
        val old = session
        val done = {
            session = null; closing = false; takingPhoto = false
            retired.forEach { it.release() }; retired.clear()
            val destination = afterClose
            afterClose = null
            if (exiting) super.finish() else if (destination != null) destination()
            else { updateButtons(); if (foreground) discover() }
        }
        if (old == null) done() else old.close(done)
    }

    override fun onStart() {
        super.onStart(); foreground = true
        if (session == null && !closing && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) discover()
        else startIfReady()
    }
    override fun onStop() { foreground = false; closeSession(); super.onStop() }
    override fun finish() { exiting = true; closeSession() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("split", split); outState.putInt("primary", primary); outState.putFloat("scale", pipScale)
        outState.putFloat("x", pipX); outState.putFloat("y", pipY)
        super.onSaveInstanceState(outState)
    }

    private fun updateButtons() {
        captureButton.isEnabled = ready && !takingPhoto && !closing
        streamsButton.isEnabled = plans.isNotEmpty() && !takingPhoto && !closing
        pairButton.isEnabled = plans.isNotEmpty() && !takingPhoto && !closing
        retryButton.isEnabled = !takingPhoto && !closing && Build.VERSION.SDK_INT >= 30
        retryButton.visibility = if (failed) View.VISIBLE else View.GONE
    }

    private fun layoutPreviews() {
        if (stage.width <= 0 || stage.height <= 0) return
        val frames = concurrentFrames(panels.size, stage.width, stage.height, primary, split, pipScale, pipX, pipY)
        panels.forEachIndexed { index, panel ->
            val (left, top, width, height) = frames[index]
            val previous = panel.layoutParams as? FrameLayout.LayoutParams
            if (previous == null || previous.width != width || previous.height != height || previous.leftMargin != left || previous.topMargin != top) {
                panel.layoutParams = FrameLayout.LayoutParams(width, height).apply { leftMargin = left; topMargin = top }
            }
            transform(index)
        }
        panels.forEachIndexed { index, panel -> if (index != primary) panel.bringToFront() }
        if (::sizeControl.isInitialized) {
            sizing.visibility = if (split) View.GONE else View.VISIBLE
            moveButton.visibility = if (split) View.GONE else View.VISIBLE
            layoutButton.text = if (split) "Inset" else "Split"
            layoutButton.contentDescription = if (split) "Switch to inset view" else "Switch to split view"
        }
    }

    private fun transform(index: Int) {
        plans.getOrNull(selected)?.streams?.getOrNull(index)?.preview?.let { previews[index].fitPreview(Size(it.width, it.height)) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDrag(panel: View, index: Int) {
        var x = 0f; var y = 0f; var startX = 0f; var startY = 0f
        panel.setOnTouchListener { view, event ->
            if (split || index == primary) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x = event.rawX; y = event.rawY; startX = pipX; startY = pipY; true }
                MotionEvent.ACTION_MOVE -> {
                    pipX = (startX + (event.rawX - x) / (stage.width - view.width).coerceAtLeast(1)).coerceIn(0f, 1f)
                    pipY = (startY + (event.rawY - y) / (stage.height - view.height * (panels.size - 1)).coerceAtLeast(1)).coerceIn(0f, 1f)
                    layoutPreviews(); true
                }
                MotionEvent.ACTION_UP -> { view.performClick(); true }
                else -> true
            }
        }
    }

    private fun dp(value: Int) = Look.dp(this, value)
    private fun text(value: String, size: Int) = Look.text(this, value, size, Look.onDark).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun button(value: String, action: () -> Unit) = Look.galleryButton(this, value, action = action).apply {
        textSize = 13f; setPadding(dp(6), 0, dp(6), 0)
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun weight() = LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) }
}
