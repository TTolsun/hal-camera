package dev.halcamera.cts.suite

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.hardware.camera2.cts.Camera2SurfaceViewCtsActivity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.halcamera.R
import dev.halcamera.cts.CameraCaseResult
import dev.halcamera.cts.CaseEnvironment
import dev.halcamera.cts.CaseReport
import dev.halcamera.cts.CtsCatalog
import dev.halcamera.cts.CtsRunner
import dev.halcamera.cts.CtsRunners
import dev.halcamera.cts.Dim
import dev.halcamera.cts.StepResult
import dev.halcamera.cts.vendored.VendoredReportPresenter
import dev.halcamera.ctsvendor.VendoredCatalog
import dev.halcamera.ctsvendor.VendoredCts
import dev.halcamera.ctsvendor.VendoredResult
import dev.halcamera.ctsvendor.VendoredRun
import dev.halcamera.ctsvendor.VendoredTest
import dev.halcamera.ui.IconButton
import dev.halcamera.ui.Look
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs the suite items named by [EXTRA_KEYS] one after another and shows one row per item. The screen is
 * both hosts at once: it *is* the Camera2SurfaceViewCtsActivity the vendored tests' ActivityTestRule expects,
 * and it implements [CtsRunner.PreviewHost] for the transcribed cases, so the two kinds share the one
 * SurfaceView the vendored onCreate builds. Items run strictly in sequence and each closes its camera before
 * it reports, so the next item opens a free camera. 중단 stops the running item and marks the rest 실행 안 함.
 */
class CtsSuiteRunActivity : Camera2SurfaceViewCtsActivity(), CtsRunner.PreviewHost {
    private lateinit var queue: List<SuiteItem>
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var statusView: TextView
    private lateinit var runButton: Button
    private lateinit var headlineView: TextView
    private lateinit var list: LinearLayout
    private val entries = ArrayList<SuiteEntry>()
    private var report: SuiteReport? = null
    private var running = false
    private var stopRequested = false
    private var pendingStart = false
    private var customRunner: CtsRunner? = null
    private var vendoredRun: VendoredRun? = null
    private var status = "대기 중"
    private var itemStartedAt = 0L
    private val liveSteps = LinkedHashMap<String, ArrayList<StepResult>>()
    private val liveFailures = ArrayList<String>()
    private val expanded = HashSet<Int>()
    private var destroyed = false

    // Preview surface state shared with the custom runner thread.
    private val surfaceLock = Object()
    private var surfaceCreated = false
    private var surfaceSize: Dim? = null
    private var awaited: Dim? = null
    private var waiter: CountDownLatch? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        VendoredCts.install(this)
        super.onCreate(savedInstanceState)
        val keys = intent.getStringArrayExtra(EXTRA_KEYS)?.toList() ?: emptyList()
        val vendored: List<VendoredTest> =
            if (Build.VERSION.SDK_INT >= VendoredCts.MIN_SDK && keys.any { it.startsWith(SuiteItem.VENDORED_PREFIX) }) VendoredCatalog.tests() else emptyList()
        queue = SuitePlan.select(SuitePlan.items(CtsCatalog.cases, vendored), keys)
        if (queue.isEmpty()) {
            Toast.makeText(this, "실행할 CTS 항목이 없습니다", Toast.LENGTH_SHORT).show(); finish(); return
        }
        VendoredCts.attachActivity(this)

        // The vendored layout is a bare LinearLayout around the SurfaceView; take the view, drop the layout.
        val preview = surfaceView
        (preview.parent as? ViewGroup)?.removeView(preview)
        preview.contentDescription = "테스트 프리뷰"
        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                synchronized(surfaceLock) { surfaceCreated = true }
                main.post { if (pendingStart) start() }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                synchronized(surfaceLock) {
                    surfaceSize = Dim(width, height)
                    if (surfaceSize == awaited) { waiter?.countDown(); waiter = null }
                }
                main.post { fitPreview(width, height) }
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                synchronized(surfaceLock) { surfaceCreated = false; surfaceSize = null }
                customRunner?.cancel()
            }
        })

        val scroll = ScrollView(this).apply { setBackgroundColor(Look.expertTile) }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left + dp(16), bars.top + dp(16), bars.right + dp(16), bars.bottom + dp(16))
            insets
        }

        val head = Look.row(this)
        head.addView(Look.text(this, "CTS 실행", 22, Look.onDark, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(IconButton(this, R.drawable.ic_action_close, "CTS 실행 화면 닫기") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        body.addView(head)
        body.addView(Look.text(this, "${queue.size}개 항목을 위에서부터 차례로 실행합니다. 화면을 나가면 실행이 중단됩니다.", 12, Look.onDarkMuted), lp(top = 4))
        body.addView(Look.text(this, SuiteReportPresenter.disclaimer(queue), 11, Look.onDarkMuted), lp(top = 4))

        // The preview keeps its frame; each item resizes only the buffer, as in the CTS activity.
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        body.addView(frame, lp(top = 12).apply { height = dp(180) })

        statusView = Look.text(this, status, 13, Look.onDarkMuted)
        body.addView(statusView, lp(top = 8))
        val actions = Look.row(this)
        runButton = Look.ghostButton(this, "실행", dark = true) { if (running) stop() else requestAndStart() }
        actions.addView(runButton, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Look.ghostButton(this, "복사", dark = true) { copy() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        actions.addView(Look.ghostButton(this, "공유", dark = true) { share() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        body.addView(actions, lp(top = 8))
        headlineView = Look.text(this, "", 19, Look.onDark, bold = true)
        body.addView(headlineView, lp(top = 18))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list, lp(top = 4))
        render()
        // The user pressed 실행 on the checklist; the surface is the only thing still to wait for.
        requestAndStart()
    }

    override fun onStop() { stop(); super.onStop() }

    override fun onDestroy() {
        destroyed = true
        stop()
        io.shutdown()
        VendoredCts.detachActivity(this)
        super.onDestroy()
    }

    // ---- run ----

    private fun requestAndStart() {
        val audio = queue.any { it.needsAudio }
        val needed = (if (audio) listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO) else listOf(Manifest.permission.CAMERA))
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) start() else requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) start() else { status = if (queue.any { it.needsAudio }) "카메라와 마이크 권한이 필요합니다" else "카메라 권한이 필요합니다"; render() }
    }

    private fun start() {
        if (running || destroyed) return
        synchronized(surfaceLock) {
            if (!surfaceCreated || !surfaceView.holder.surface.isValid) { pendingStart = true; status = "프리뷰 surface를 기다리는 중"; render(); return }
        }
        pendingStart = false
        entries.clear(); expanded.clear(); report = null; stopRequested = false
        running = true
        // A screen-off would stop the activity and cancel the run.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, 1000)
        runNext()
    }

    private fun runNext() {
        val index = entries.size
        if (index >= queue.size || stopRequested) { finishSuite(); return }
        val item = queue[index]
        itemStartedAt = SystemClock.elapsedRealtime()
        liveSteps.clear(); liveFailures.clear()
        status = "${index + 1}/${queue.size} · ${item.title} · 시작"
        render()
        when (item) {
            is SuiteItem.Custom -> startCustom(item)
            is SuiteItem.Vendored -> startVendored(item)
        }
    }

    private fun startCustom(item: SuiteItem.Custom) {
        val metrics = windowBounds()
        val env = CaseEnvironment(
            manager = getSystemService(CameraManager::class.java),
            previewHost = this,
            outputDir = cacheDir,
            windowWidth = metrics.first, windowHeight = metrics.second,
            listener = object : CtsRunner.Listener {
                override fun onCameraStarted(cameraId: String, index: Int, total: Int) = post {
                    liveSteps.getOrPut(cameraId) { ArrayList() }
                    status = "${prefix(item)} · 카메라 $cameraId 준비 중 (${index + 1}/$total)"; render()
                }
                override fun onProgress(cameraId: String, stage: String, index: Int, total: Int) = post {
                    status = "${prefix(item)} · 카메라 $cameraId · $stage (${index + 1}/$total)"; render()
                }
                override fun onStep(cameraId: String, step: StepResult) = post {
                    liveSteps.getOrPut(cameraId) { ArrayList() }.add(step); render()
                }
                override fun onFinished(report: CaseReport) = post {
                    customRunner = null
                    entries += SuiteEntry.of(item, report, SystemClock.elapsedRealtime() - itemStartedAt)
                    runNext()
                }
            }
        )
        val r = CtsRunners.create(item.spec.id, env)
        customRunner = r
        io.execute { r.run() }
    }

    private fun startVendored(item: SuiteItem.Vendored) {
        val r = VendoredRun(item.test, object : VendoredRun.Listener {
            override fun onStarted(displayName: String) = post { status = "${prefix(item)} · 실행 중 · $displayName"; render() }
            override fun onFailure(displayName: String, message: String) = post { liveFailures += message; render() }
            override fun onFinished(result: VendoredResult) = post {
                vendoredRun = null
                entries += SuiteEntry.of(item, result)
                runNext()
            }
        })
        vendoredRun = r
        Thread({ r.run() }, "cts-suite-vendored").start()
    }

    private fun prefix(item: SuiteItem): String = "${entries.size + 1}/${queue.size} · ${item.title}"

    private fun finishSuite() {
        while (entries.size < queue.size) entries += SuiteEntry.notRun(queue[entries.size])
        report = SuiteReport(entries.toList(), stopRequested)
        running = false
        status = if (stopRequested) "중단됨 · 완료된 항목까지만 표시합니다" else "완료"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
    }

    /** Stops the running item; the queue ends when that item reports, and the items after it are marked 실행 안 함. */
    private fun stop() {
        if (!running) return
        stopRequested = true
        status = "중단 중…"
        customRunner?.cancel()
        vendoredRun?.stop()
        render()
    }

    /**
     * Once a second while running: a JUnit body gives no progress of its own, so the elapsed time stands in. Only
     * the status line is redrawn; rebuilding the cards would reset a detail table the user has scrolled sideways.
     */
    private val ticker = object : Runnable {
        override fun run() {
            if (destroyed || !running) return
            renderStatus()
            main.postDelayed(this, 1000)
        }
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

    // ---- PreviewHost: Camera2SurfaceViewTestCase.updatePreviewSurface for the custom runners ----

    override fun acquirePreview(size: Dim, timeoutMs: Long): Surface? {
        val holder = surfaceView.holder
        val latch: CountDownLatch
        synchronized(surfaceLock) {
            if (surfaceSize == size && holder.surface.isValid) return holder.surface
            latch = CountDownLatch(1)
            awaited = size; waiter = latch
        }
        main.post { holder.setFixedSize(size.width, size.height) }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        return holder.surface.takeIf { it.isValid }
    }

    /** Letterbox the SurfaceView to the buffer aspect inside its frame; the buffer size itself is fixed by the holder. */
    private fun fitPreview(width: Int, height: Int) {
        val preview = surfaceView
        val frame = preview.parent as? FrameLayout ?: return
        val fw = frame.width; val fh = frame.height
        if (fw == 0 || fh == 0 || width <= 0 || height <= 0) return
        val scale = minOf(fw.toFloat() / width, fh.toFloat() / height)
        preview.layoutParams = FrameLayout.LayoutParams((width * scale).toInt(), (height * scale).toInt(), Gravity.CENTER)
    }

    // ---- screen ----

    private fun renderStatus() {
        val elapsed = if (running && itemStartedAt > 0) " · ${VendoredReportPresenter.duration(SystemClock.elapsedRealtime() - itemStartedAt)}" else ""
        statusView.text = status + elapsed
    }

    private fun render() {
        renderStatus()
        runButton.text = when { running -> "중단"; report != null -> "다시 실행"; else -> "실행" }
        val done = report
        headlineView.visibility = if (done == null) View.GONE else View.VISIBLE
        if (done != null) {
            headlineView.text = SuiteReportPresenter.headline(done)
            headlineView.setTextColor(when { done.cancelled -> Look.statusWarn; done.failed > 0 -> Look.statusFail; else -> Look.statusPass })
        }
        list.removeAllViews()
        queue.forEachIndexed { index, item ->
            val entry = entries.getOrNull(index)
            val card = when {
                entry != null -> entryCard(index, entry)
                running && index == entries.size -> liveCard(item)
                else -> pendingCard(item)
            }
            list.addView(card, lp(top = 8))
        }
    }

    private fun entryCard(index: Int, entry: SuiteEntry): View {
        val color = when (entry.outcome) {
            SuiteOutcome.PASS -> Look.statusPass
            SuiteOutcome.FAIL -> Look.statusFail
            SuiteOutcome.SKIP -> Look.statusUnknown
            SuiteOutcome.CANCELLED -> Look.statusWarn
            SuiteOutcome.NOT_RUN -> Look.onDarkMuted
        }
        val open = index in expanded
        val card = itemCard(SuiteReportPresenter.outcomeLabel(entry.outcome), color, entry.item.title, SuiteReportPresenter.entryLine(entry))
        if (entry.detail.isNotEmpty()) {
            card.isClickable = true; card.isFocusable = true
            card.contentDescription = "${entry.item.title} ${SuiteReportPresenter.outcomeLabel(entry.outcome)}, 상세 ${if (open) "접기" else "펼치기"}"
            card.setOnClickListener { if (open) expanded -= index else expanded += index; render() }
            if (open) card.addView(wide(Look.text(this, entry.detail, 11, Look.onDark, mono = true)), lp(top = 10))
        }
        return card
    }

    private fun liveCard(item: SuiteItem): View {
        val card = itemCard("실행 중", Look.primaryOnDark, item.title, item.source)
        val detail = when (item) {
            is SuiteItem.Custom -> SuiteReportPresenter.customDetail(liveSteps.map { CameraCaseResult(it.key, it.value) })
            is SuiteItem.Vendored -> SuiteReportPresenter.vendoredDetail(liveFailures)
        }
        if (detail.isNotEmpty()) card.addView(wide(Look.text(this, detail, 11, Look.onDark, mono = true)), lp(top = 10))
        return card
    }

    private fun pendingCard(item: SuiteItem): View = itemCard("대기", Look.onDarkMuted, item.title, item.source).apply { alpha = 0.7f }

    /** A dark card with the outcome tag on the left and the title and its second line on the right. */
    private fun itemCard(tag: String, tagColor: Int, title: String, line: String): LinearLayout {
        val card = Look.card(this, dark = true)
        val row = Look.row(this)
        row.addView(Look.text(this, tag, 12, tagColor, bold = true).apply { minWidth = dp(64) }, LinearLayout.LayoutParams(-2, -2))
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(Look.text(this, title, 15, Look.onDark, bold = true))
        column.addView(Look.text(this, line, 12, Look.onDarkMuted), lp(top = 2))
        row.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        card.addView(row)
        return card
    }

    private fun fullText(): String? = report?.let {
        SuiteReportPresenter.fullText(
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            build = "Android ${Build.VERSION.RELEASE} (${Build.DISPLAY})",
            app = runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "" }.getOrDefault(""),
            report = it
        )
    }

    private fun copy() {
        val text = fullText() ?: return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("cts-suite", text))
        Toast.makeText(this, "결과를 복사했습니다", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val text = fullText() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "HAL CAM CTS suite")
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

    companion object {
        const val EXTRA_KEYS = "keys"
        private const val REQUEST_PERMISSIONS = 42
    }
}
