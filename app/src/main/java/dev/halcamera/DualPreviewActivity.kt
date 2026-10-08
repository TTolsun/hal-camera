package dev.halcamera

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.halcamera.camera.CameraLabel
import dev.halcamera.camera.DualPreviewPlanner
import dev.halcamera.camera.DualPreviewSession
import dev.halcamera.camera.LiveSize
import dev.halcamera.camera.LogicalMultiCamera
import dev.halcamera.camera.PhysicalLens
import dev.halcamera.camera.PhysicalOutputStats
import dev.halcamera.camera.fitPreview
import dev.halcamera.camera.physicalTimestampSkewNs
import dev.halcamera.camera.readLogicalMultiCameras
import dev.halcamera.ui.Look
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Lab destination for #171: two physical rear cameras of one logical multi-camera, streamed side by side.
 *
 * Camera2 only. LIVE has already closed its camera before Lab opened, so this screen owns the logical camera
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
    private var session: DualPreviewSession? = null
    private var closing = false
    private var started = false
    private var streamingSize: LiveSize? = null
    private var status = ""
    private var stats: Pair<PhysicalOutputStats, PhysicalOutputStats>? = null
    private var lastSkewNs: Long? = null
    private val views = arrayOfNulls<TextureView>(2)
    private val textures = arrayOfNulls<SurfaceTexture>(2)
    private val infos = arrayOfNulls<TextView>(2)
    private var skewText: TextView? = null
    private var statusText: TextView? = null
    private val tick = object : Runnable {
        override fun run() { refreshInfo(); main.postDelayed(this, 500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.LabTheme)
        super.onCreate(savedInstanceState)
        Look.configureLabWindow(this)
        logicalId = savedInstanceState?.getString(KEY_LOGICAL)
        pair = savedInstanceState?.getStringArray(KEY_PAIR)?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        showPage()
        message("카메라 정보를 읽는 중입니다.")
        io.execute {
            val read = runCatching { readLogicalMultiCameras(manager) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                read.fold({ cameras = it; render(); startIfReady() }, { message("카메라 정보를 읽지 못했습니다. ${it.message.orEmpty()}") })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        main.post(tick)
        startIfReady()
    }

    override fun onStop() {
        started = false
        main.removeCallbacks(tick)
        closeSession {}
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_LOGICAL, logicalId)
        pair?.let { outState.putStringArray(KEY_PAIR, arrayOf(it.first, it.second)) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun showPage() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Look.canvas); addView(body) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val margin = Look.pageMargin(view, bars.left + bars.right)
            view.setPadding(bars.left + margin, bars.top + dp(16), bars.right + margin, bars.bottom + dp(24))
            insets
        }
        ViewCompat.requestApplyInsets(scroll)
    }

    private fun header() {
        body.removeAllViews()
        views.fill(null); textures.fill(null); infos.fill(null)
        body.addView(Look.titleBar(this, "Dual Preview", 34, "Lab으로 돌아가기", ::finish))
        body.addView(Look.text(this, "한 논리 카메라의 후면 물리 카메라 2개를 동시에 봅니다. Camera2 전용이며 CameraX에서는 지원하지 않습니다.",
            15, Look.inkMuted))
    }

    private fun message(text: String) {
        header()
        body.addView(Look.text(this, text, 15, Look.ink), lp(16))
    }

    private fun render() {
        if (Build.VERSION.SDK_INT < 28) return message("Android 9 (API 28) 이상이 필요합니다.")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            return message("카메라 권한이 필요합니다. Live에서 권한을 허용한 뒤 다시 여세요.")
        val candidates = DualPreviewPlanner.candidates(cameras)
        if (candidates.isEmpty()) return message(unsupportedReport())
        val camera = candidates.firstOrNull { it.logicalId == logicalId } ?: candidates.first()
        if (logicalId != camera.logicalId) { logicalId = camera.logicalId; pair = null }
        val selected = pair?.takeIf { (a, b) -> camera.physical.any { it.id == a } && camera.physical.any { it.id == b } }
            ?: DualPreviewPlanner.defaultPair(camera)
        pair = selected
        header()

        if (candidates.size > 1) {
            body.addView(Look.text(this, "논리 카메라", 13, Look.inkMuted), lp(20))
            body.addView(chips(candidates.map { it.logicalId to CameraLabel.short(it.logicalId) }, camera.logicalId) { id ->
                logicalId = id; pair = null; restart()
            }, lp(8))
        }
        val lensChips = camera.physical.map { it.id to lensLabel(it) }
        body.addView(Look.text(this, "왼쪽 (A)", 13, Look.inkMuted), lp(20))
        body.addView(chips(lensChips, selected?.first) { id -> pair = id to (pair?.second ?: id); restart() }, lp(8))
        body.addView(Look.text(this, "오른쪽 (B)", 13, Look.inkMuted), lp(12))
        body.addView(chips(lensChips, selected?.second) { id -> pair = (pair?.first ?: id) to id; restart() }, lp(8))
        body.addView(Look.text(this, "센서 동기: ${DualPreviewPlanner.syncLabel(camera.syncType)} · 동기화가 보장된다고 가정하지 않습니다.",
            13, Look.inkMuted), lp(16))

        val previews = Look.row(this).apply { gravity = android.view.Gravity.TOP }
        for (i in 0..1) previews.addView(previewColumn(i), LinearLayout.LayoutParams(0, -2, 1f).apply {
            if (i == 1) marginStart = dp(8)
        })
        body.addView(previews, lp(12))
        skewText = Look.text(this, "", 13, Look.ink, mono = true).also { body.addView(it, lp(12)) }
        statusText = Look.text(this, status, 13, Look.inkMuted).also { body.addView(it, lp(8)) }
        body.addView(Look.ghostButton(this, "보고서 복사") { copyReport() }, Look.buttonParams().apply { topMargin = dp(16) })
        refreshInfo()
    }

    private fun previewColumn(index: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val view = object : TextureView(this@DualPreviewActivity) {
            /** 3:4, the portrait shape of a 4:3 sensor buffer; fitPreview crops anything else to fill it. */
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = View.MeasureSpec.getSize(widthMeasureSpec)
                setMeasuredDimension(width, width * 4 / 3)
            }
        }
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
                if (views[index] === view) { textures[index] = null; closeSession {} }
                return true
            }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                if (views[index] === view && session != null) stats?.let { (a, b) -> (if (index == 0) a else b).frame(SystemClock.elapsedRealtime()) }
            }
        }
        views[index] = view
        addView(view, LinearLayout.LayoutParams(-1, -2))
        infos[index] = Look.text(this@DualPreviewActivity, "", 12, Look.ink, mono = true).also { addView(it, lp(8)) }
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
                setStatus("시작하지 않음: ${result.reason.label}")
            }
            is DualPreviewPlanner.Result.Ready -> {
                streamingSize = null
                stats = PhysicalOutputStats(first) to PhysicalOutputStats(second)
                lastSkewNs = null
                session = DualPreviewSession(manager, result.plan, a to b, sessionListener).also { it.start() }
            }
        }
    }

    private val sessionListener = object : DualPreviewSession.Listener {
        override fun onStatus(message: String) = setStatus(message)
        override fun onStreaming(size: LiveSize) {
            streamingSize = size
            views.forEach { it?.fitPreview(Size(size.width, size.height)) }
        }
        override fun onResult(physical: Map<String, Long?>) {
            val (a, b) = stats ?: return
            val ta = physical[a.physicalId]
            val tb = physical[b.physicalId]
            a.result(ta); b.result(tb)
            physicalTimestampSkewNs(ta, tb)?.let { lastSkewNs = it }
        }
        override fun onFailed(message: String) = setStatus("실패: $message")
    }

    /** Closes the whole device; [then] runs once it is closed, so the next pair never shares buffers with this one. */
    private fun closeSession(then: () -> Unit) {
        val old = session ?: return then()
        session = null
        closing = true
        old.close {
            closing = false
            then()
            startIfReady()
        }
    }

    private fun restart() = closeSession { if (!isDestroyed) render() }

    private fun setStatus(text: String) {
        status = text
        statusText?.text = text
    }

    private fun refreshInfo() {
        val camera = cameras.firstOrNull { it.logicalId == logicalId } ?: return
        val current = stats
        for (i in 0..1) {
            val id = pair?.let { if (i == 0) it.first else it.second } ?: continue
            val lens = camera.physical.firstOrNull { it.id == id }
            val s = current?.let { if (i == 0) it.first else it.second }?.takeIf { it.physicalId == id }
            infos[i]?.text = outputReport(lens, id, s)
        }
        skewText?.text = "A−B 타임스탬프: " + when (val skew = lastSkewNs) {
            null -> "—"
            // Some HALs copy the logical timestamp into every physical result; equal values say nothing about sync.
            0L -> "0 (두 물리 결과가 같은 값을 보고 · 센서 간 오차 아님)"
            else -> String.format(Locale.US, "%.3f ms", skew / 1e6)
        }
    }

    private fun outputReport(lens: PhysicalLens?, id: String, s: PhysicalOutputStats?): String = listOf(
        "ID $id · ${lens?.let(::lensLabel)?.substringAfter(" · ", "") ?: ""}".trimEnd(' ', '·'),
        "크기 ${streamingSize ?: "—"}",
        "프레임 ${s?.delivered ?: 0} · ${s?.fps()?.let { String.format(Locale.US, "%.1f fps", it) } ?: "— fps"}",
        "메타 ${s?.metadata ?: 0} · 누락 ${s?.missing ?: 0}",
        "ts ${s?.lastSensorTimestampNs ?: "—"}"
    ).joinToString("\n")

    private fun lensLabel(lens: PhysicalLens): String {
        val name = CameraLabel.lens(lens.role) ?: lens.equivalentFocalMm?.let { String.format(Locale.US, "%.0f mm", it) } ?: "?"
        return "${lens.id} · $name"
    }

    private fun unsupportedReport(): String {
        val rear = cameras.filter { it.facing == CameraLabel.FACING_BACK }
        if (rear.isEmpty()) return "후면 카메라가 없어 사용할 수 없습니다."
        return "이 기기에서는 사용할 수 없습니다.\n" + rear.joinToString("\n") { c ->
            "${CameraLabel.short(c.logicalId)}: ${DualPreviewPlanner.refusal(c, Build.VERSION.SDK_INT)?.label ?: "지원"}"
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
            appendLine("A-B skew ns ${lastSkewNs ?: "-"}")
            append("status $status")
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("HAL CAM dual preview", report))
        Toast.makeText(this, "보고서를 복사했습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun chips(options: List<Pair<String, String>>, selected: String?, pick: (String) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEach { (id, label) ->
            val chosen = id == selected
            val button = if (chosen) Look.primaryButton(this, label) {} else Look.ghostButton(this, label) { if (!closing) pick(id) }
            button.textSize = 14f
            button.minHeight = dp(48); button.minimumHeight = dp(48)
            button.setPadding(dp(14), dp(8), dp(14), dp(8))
            ViewCompat.setStateDescription(button, if (chosen) "선택됨" else "선택 안 됨")
            row.addView(button, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        return android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int) = Look.dp(this, value)

    companion object {
        private const val KEY_LOGICAL = "dual_logical"
        private const val KEY_PAIR = "dual_pair"
    }
}
