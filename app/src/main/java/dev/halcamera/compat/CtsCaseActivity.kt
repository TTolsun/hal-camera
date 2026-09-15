package dev.halcamera.compat

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
import dev.halcamera.R
import dev.halcamera.ui.IconButton
import dev.halcamera.ui.Look
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs CTS `RecordingTest#testBasicRecording` through [BasicRecordingRunner] and shows one card per camera.
 * The SurfaceView plays the part of Camera2SurfaceViewCtsActivity: the runner resizes its buffer to each
 * video size and waits for surfaceChanged before opening the session. LIVE is stopped while this screen is
 * up, so the camera is free.
 */
class CtsCaseActivity : ComponentActivity(), BasicRecordingRunner.PreviewHost, SurfaceHolder.Callback {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private lateinit var preview: SurfaceView
    private lateinit var statusView: TextView
    private lateinit var runButton: android.widget.Button
    private lateinit var headlineView: TextView
    private lateinit var results: LinearLayout
    private var runner: BasicRecordingRunner? = null
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
        if (granted.values.all { it }) start() else { status = "카메라와 마이크 권한이 필요합니다"; render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val needed = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) start() else permissions.launch(needed.toTypedArray())
    }

    private fun start() {
        if (runner != null) return
        synchronized(surfaceLock) { if (!surfaceCreated) { status = "프리뷰 surface를 기다리는 중입니다. 다시 눌러 주세요."; render(); return } }
        report = null; live.clear()
        val metrics = windowBounds()
        val r = BasicRecordingRunner(
            manager = getSystemService(CameraManager::class.java),
            previewHost = this,
            outputDir = cacheDir,
            windowWidth = metrics.first, windowHeight = metrics.second,
            listener = object : BasicRecordingRunner.Listener {
                override fun onCameraStarted(cameraId: String, index: Int, total: Int) = post {
                    live.getOrPut(cameraId) { ArrayList() }
                    status = "카메라 $cameraId 준비 중 (${index + 1}/$total)"; render()
                }
                override fun onProfileStarted(cameraId: String, quality: String, index: Int, total: Int) = post {
                    status = "카메라 $cameraId · $quality 녹화 중 (프로파일 ${index + 1}/$total)"; render()
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
        val head = Look.row(this)
        head.addView(Look.text(this, "CTS 케이스", 22, Look.onDark, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(IconButton(this, R.drawable.ic_action_close, "CTS 케이스 화면 닫기") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        body.addView(head)
        body.addView(Look.text(this, BasicRecordingRules.SOURCE, 14, Look.onDark, mono = true), lp(top = 8))
        val cameras = runCatching { getSystemService(CameraManager::class.java).cameraIdList.size }.getOrDefault(0)
        body.addView(Look.text(this, "카메라 ${cameras}대 × CamcorderProfile 최대 ${BasicRecordingRules.PROFILE_ORDER.size}개를 각각 ${BasicRecordingRules.RECORDING_DURATION_MS / 1000}초씩 녹화합니다(약 ${cameras * BasicRecordingRules.PROFILE_ORDER.size * 4 / 60 + 1}분). 길이 오차 ${(BasicRecordingRules.DURATION_MARGIN * 100).toInt()} %, 프레임 드롭률 ${BasicRecordingRules.FRMDRP_RATE_TOLERANCE.toInt()} % 미만을 검사하고 소리도 함께 녹음됩니다.", 12, Look.onDarkMuted), lp(top = 4))
        body.addView(Look.text(this, CaseReportPresenter.DISCLAIMER, 11, Look.onDarkMuted), lp(top = 4))

        // The preview keeps its view size; only the buffer is resized per profile, as in the CTS activity.
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        body.addView(frame, lp(top = 12).apply { height = dp(180) })

        statusView = Look.text(this, status, 13, Look.onDarkMuted)
        body.addView(statusView, lp(top = 8))
        val actions = Look.row(this)
        runButton = Look.ghostButton(this, "실행", dark = true) { if (runner != null) runner?.cancel() else requestAndStart() }
        actions.addView(runButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(Look.ghostButton(this, "복사", dark = true) { copy() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        actions.addView(Look.ghostButton(this, "공유", dark = true) { share() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        body.addView(actions, lp(top = 8))
        headlineView = Look.text(this, "", 19, Look.onDark, bold = true)
        body.addView(headlineView, lp(top = 18))
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(results, lp())
    }

    private fun render() {
        statusView.text = status
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
            putExtra(Intent.EXTRA_SUBJECT, "HAL CAM CTS case · ${BasicRecordingRules.SOURCE}")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "결과 공유"))
    }

    private fun wide(view: TextView): HorizontalScrollView = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(view, LinearLayout.LayoutParams(-2, -2))
    }

    private fun dp(v: Int) = Look.dp(this, v)
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); gravity = Gravity.START }
}
