package dev.halcamera.cts

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.halcamera.camera.CameraLabel
import dev.halcamera.ui.Look
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs one [CtsCatalog] case, chosen by [EXTRA_CASE_ID], through the runner [CtsRunners] builds for it, and
 * shows one card per camera. The SurfaceView plays the part of Camera2SurfaceViewCtsActivity: a runner resizes
 * its buffer to each size it needs and waits for surfaceChanged before opening the session. LIVE is stopped
 * while this screen is up, so the camera is free.
 */
class CtsCaseActivity : ComponentActivity(), CtsRunner.PreviewHost, SurfaceHolder.Callback {
    private lateinit var spec: CtsCaseSpec
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private lateinit var preview: SurfaceView
    private lateinit var statusView: TextView
    private lateinit var runButton: android.widget.Button
    private lateinit var headlineView: TextView
    private lateinit var results: LinearLayout
    /** Covers the surface while nothing runs, so a closed camera's last frame does not stay on screen. */
    private lateinit var previewCover: TextView
    private var runner: CtsRunner? = null
    private var report: CaseReport? = null
    private var status = "대기 중"
    private val live = LinkedHashMap<String, ArrayList<StepResult>>()
    private var destroyed = false

    // Preview surface state shared with the runner thread.
    private val surfaceLock = Object()
    private var surfaceCreated = false
    private var surfaceSize: Dim? = null
    private var awaited: Dim? = null
    private var waiter: CountDownLatch? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) start() else { status = if (spec.needsAudio) "카메라와 마이크 권한이 필요합니다" else "카메라 권한이 필요합니다"; render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        spec = CtsCatalog.byId(intent.getStringExtra(EXTRA_CASE_ID)) ?: run {
            Toast.makeText(this, "알 수 없는 CTS 케이스입니다", Toast.LENGTH_SHORT).show(); finish(); return
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(Look.expertTile) }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left + dp(16), bars.top + dp(16), bars.right + dp(16), bars.bottom + dp(16))
            insets
        }
        preview = SurfaceView(this).apply { holder.addCallback(this@CtsCaseActivity); contentDescription = "녹화 프리뷰" }
        buildScreen()
        render()
    }

    override fun onStop() { runner?.cancel(); super.onStop() }

    override fun onDestroy() {
        destroyed = true
        runner?.cancel()
        io.shutdown()
        super.onDestroy()
    }

    // ---- run ----

    private fun requestAndStart() {
        val needed = (if (spec.needsAudio) listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO) else listOf(Manifest.permission.CAMERA))
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) start() else permissions.launch(needed.toTypedArray())
    }

    private fun start() {
        if (runner != null) return
        synchronized(surfaceLock) { if (!surfaceCreated) { status = "프리뷰 surface를 기다리는 중입니다. 다시 눌러 주세요."; render(); return } }
        report = null; live.clear()
        val metrics = windowBounds()
        val env = CaseEnvironment(
            manager = getSystemService(CameraManager::class.java),
            previewHost = this,
            outputDir = cacheDir,
            windowWidth = metrics.first, windowHeight = metrics.second,
            listener = object : CtsRunner.Listener {
                override fun onCameraStarted(cameraId: String, index: Int, total: Int) = post {
                    live.getOrPut(cameraId) { ArrayList() }
                    status = "${CameraLabel.short(cameraId)} 준비 중 (${index + 1}/$total)"; render()
                }
                override fun onProgress(cameraId: String, stage: String, index: Int, total: Int) = post {
                    status = "${CameraLabel.short(cameraId)} · $stage 진행 중 (${index + 1}/$total)"; render()
                }
                override fun onStep(cameraId: String, step: StepResult) = post {
                    live.getOrPut(cameraId) { ArrayList() }.add(step); render()
                }
                override fun onFinished(report: CaseReport) = post {
                    this@CtsCaseActivity.report = report; runner = null
                    status = if (report.cancelled) "중단됨 · 완료된 항목까지만 표시합니다" else "완료"
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    render()
                }
            }
        )
        val r = CtsRunners.create(spec.id, env)
        runner = r
        status = "시작"
        // A screen-off would stop the activity and cancel the run.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
        io.execute { r.run() }
    }

    private fun post(action: () -> Unit) { main.post { if (!destroyed) action() } }

    private fun windowBounds(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds; b.width() to b.height()
        } else {
            @Suppress("DEPRECATION") val d = wm.defaultDisplay
            val p = android.graphics.Point(); @Suppress("DEPRECATION") d.getRealSize(p); p.x to p.y
        }
    }

    // ---- PreviewHost: Camera2SurfaceViewTestCase.updatePreviewSurface ----

    override fun acquirePreview(size: Dim, timeoutMs: Long): Surface? {
        val latch: CountDownLatch
        synchronized(surfaceLock) {
            if (surfaceSize == size && preview.holder.surface.isValid) return preview.holder.surface
            latch = CountDownLatch(1)
            awaited = size; waiter = latch
        }
        main.post {
            preview.holder.setFixedSize(size.width, size.height)
            fitPreview(size)
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        return preview.holder.surface.takeIf { it.isValid }
    }

    /** Letterbox the SurfaceView to the buffer aspect inside its frame; the buffer size itself is fixed by the holder. */
    private fun fitPreview(size: Dim) {
        val frame = preview.parent as? FrameLayout ?: return
        val fw = frame.width; val fh = frame.height
        if (fw == 0 || fh == 0) return
        val scale = minOf(fw.toFloat() / size.width, fh.toFloat() / size.height)
        preview.layoutParams = FrameLayout.LayoutParams((size.width * scale).toInt(), (size.height * scale).toInt(), Gravity.CENTER)
    }

    override fun surfaceCreated(holder: SurfaceHolder) { synchronized(surfaceLock) { surfaceCreated = true } }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(surfaceLock) {
            surfaceSize = Dim(width, height)
            if (surfaceSize == awaited) { waiter?.countDown(); waiter = null }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        synchronized(surfaceLock) { surfaceCreated = false; surfaceSize = null }
        runner?.cancel()
    }

    // ---- screen ----

    /** Static parts once; the SurfaceView must never be re-parented, or its surface is destroyed mid-run. */
    private fun buildScreen() {
        body.addView(Look.titleBar(this, spec.title, 22, "이전 화면으로 돌아가기") { finish() })
        body.addView(Look.text(this, spec.source, 14, Look.onDark, mono = true), lp(top = 8))
        val cameras = runCatching { getSystemService(CameraManager::class.java).cameraIdList.size }.getOrDefault(0)
        body.addView(Look.text(this, spec.summary(cameras), 12, Look.onDarkMuted), lp(top = 4))
        body.addView(Look.text(this, CaseReportPresenter.DISCLAIMER, 11, Look.onDarkMuted), lp(top = 4))

        // The preview keeps its view size; only the buffer is resized per step, as in the CTS activity.
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        // A closed camera leaves its last frame on the surface, so the case ends with a still picture of
        // whatever it last pointed at sitting under the results. The cover hides the surface whenever nothing
        // is running; the surface itself is left alone because the runner holds it.
        previewCover = Look.text(this, "", 12, Look.onDarkMuted).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        }
        frame.addView(previewCover, FrameLayout.LayoutParams(-1, -1))
        body.addView(frame, lp(top = 12).apply { height = dp(180) })

        statusView = Look.text(this, status, 13, Look.onDarkMuted)
        body.addView(statusView, lp(top = 8))
        val actions = Look.row(this)
        runButton = Look.ghostButton(this, "실행", dark = true) { if (runner != null) runner?.cancel() else requestAndStart() }
        actions.addView(runButton, Look.buttonParams(0, 1f))
        actions.addView(Look.ghostButton(this, "복사", dark = true) { copy() }, Look.buttonParams(0, 1f).apply { marginStart = dp(8) })
        actions.addView(Look.ghostButton(this, "공유", dark = true) { share() }, Look.buttonParams(0, 1f).apply { marginStart = dp(8) })
        body.addView(actions, lp(top = 8))
        headlineView = Look.text(this, "", 19, Look.onDark, bold = true)
        body.addView(headlineView, lp(top = 18))
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(results, lp())
    }

    private fun render() {
        statusView.text = status
        previewCover.visibility = if (runner != null) android.view.View.GONE else android.view.View.VISIBLE
        previewCover.text = if (report == null) "실행을 시작하면 프리뷰가 여기에 나옵니다" else "실행이 끝나 카메라를 닫았습니다"
        runButton.text = if (runner != null) "중단" else "실행"
        val done = report
        headlineView.visibility = if (done == null) android.view.View.GONE else android.view.View.VISIBLE
        if (done != null) {
            headlineView.text = CaseReportPresenter.headline(done)
            headlineView.setTextColor(if (done.failed == 0) Look.statusPass else Look.statusFail)
        }
        results.removeAllViews()
        val cameras = done?.cameras ?: live.map { CameraCaseResult(it.key, it.value) }
        cameras.forEach { r ->
            val card = Look.card(this, dark = true)
            card.addView(Look.text(this, CaseReportPresenter.cameraLine(r), 14, Look.onDark, bold = true))
            card.addView(wide(Look.text(this, colorize(CaseReportPresenter.cameraTable(r)), 11, Look.onDark, mono = true)), lp(top = 10))
            results.addView(card, lp(top = 12))
        }
    }


    /** PASS/FAIL/SKIP at the start of a row get the status colours; the rest of the table stays as text. */
    private fun colorize(table: String): CharSequence {
        val spannable = SpannableString(table)
        var index = 0
        table.split('\n').forEach { row ->
            val verdict = Verdict.values().firstOrNull { row.startsWith(it.name) }
            if (verdict != null) {
                val color = when (verdict) { Verdict.PASS -> Look.statusPass; Verdict.FAIL -> Look.statusFail; Verdict.SKIP -> Look.statusUnknown }
                spannable.setSpan(ForegroundColorSpan(color), index, index + verdict.name.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            index += row.length + 1
        }
        return spannable
    }

    private fun fullText(): String? = report?.let {
        CaseReportPresenter.fullText(
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            build = "Android ${Build.VERSION.RELEASE} (${Build.DISPLAY})",
            app = runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "" }.getOrDefault(""),
            report = it
        )
    }

    private fun copy() {
        val text = fullText() ?: return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("cts-case", text))
        Toast.makeText(this, "결과를 복사했습니다", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val text = fullText() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "HAL CAM CTS case · ${spec.source}")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "결과 공유"))
    }

    // Same reason as the suite screen: a step line that runs past the edge has to look unfinished, or the
    // number it was cut in the middle of is read as the whole value.
    private fun wide(view: TextView): HorizontalScrollView = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = true
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(24))
        addView(view, LinearLayout.LayoutParams(-2, -2))
    }

    private fun dp(v: Int) = Look.dp(this, v)
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); gravity = Gravity.START }

    companion object {
        const val EXTRA_CASE_ID = "case_id"
    }
}
