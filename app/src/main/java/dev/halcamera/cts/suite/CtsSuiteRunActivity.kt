package dev.halcamera.cts.suite

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.cts.Camera2SurfaceViewCtsActivity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
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
import dev.halcamera.cli.CliJson
import dev.halcamera.cli.CommandCoordinator
import dev.halcamera.cli.CtsController
import dev.halcamera.cts.vendored.VendoredReportPresenter
import dev.halcamera.ctsvendor.VendoredCatalog
import dev.halcamera.ctsvendor.VendoredCts
import dev.halcamera.ctsvendor.VendoredResult
import dev.halcamera.ctsvendor.VendoredRun
import dev.halcamera.ctsvendor.VendoredTest
import dev.halcamera.ui.IconButton
import dev.halcamera.ui.Look
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Runs the suite items named by [EXTRA_KEYS] one after another and shows one row per item. The screen *is*
 * the Camera2SurfaceViewCtsActivity the vendored tests' ActivityTestRule expects, so every method draws its
 * preview on the one SurfaceView the vendored onCreate builds. Items run strictly in sequence and each closes
 * its camera before it reports, so the next item opens a free camera. 중단 stops the running item and marks
 * the rest 실행 안 함.
 */
class CtsSuiteRunActivity : Camera2SurfaceViewCtsActivity() {
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
    private var vendoredRun: VendoredRun? = null
    private var status = "대기 중"
    private var itemStartedAt = 0L
    private val liveFailures = ArrayList<String>()
    private val expanded = HashSet<Int>()
    private var destroyed = false

    // The CLI drives this screen the way it drives Benchmark: Live hands over, the adapter starts the queue and
    // files the report. Without a CLI request the adapter is only attached, so a CLI status query sees "cts".
    private val cli by lazy { CommandCoordinator.get(this) }
    private val ctsCli by lazy {
        CtsController(cli, object : CtsController.Driver {
            override fun busy() = running
            override fun begin(): String? {
                if (missingPermissions().isNotEmpty()) return "PERMISSION_REQUIRED"
                start()
                return null
            }
            override fun stop() = this@CtsSuiteRunActivity.stop()
        })
    }

    // The surface must exist before JUnit opens the first camera on it.
    private val surfaceLock = Object()
    private var surfaceCreated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        VendoredCts.install(this)
        super.onCreate(savedInstanceState)
        val keys = intent.getStringArrayExtra(EXTRA_KEYS)?.toList() ?: emptyList()
        val vendored: List<VendoredTest> = if (Build.VERSION.SDK_INT >= VendoredCts.MIN_SDK) VendoredCatalog.tests() else emptyList()
        queue = SuitePlan.select(SuitePlan.items(vendored), keys)
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
                main.post { fitPreview(width, height) }
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                synchronized(surfaceLock) { surfaceCreated = false }
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
        body.addView(Look.text(this, SuiteReportPresenter.disclaimer(), 11, Look.onDarkMuted), lp(top = 4))

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
        // The user pressed 실행 on the checklist; the surface is the only thing still to wait for. A CLI request
        // starts through its adapter instead, so a missing permission is reported rather than asked for.
        if (intent.getStringExtra("cli_request_id") == null) requestAndStart()
    }

    override fun onStart() {
        super.onStart()
        if (intent.getStringExtra("cli_request_id") == cli.active?.id && cli.active != null) cli.continueHandover(ctsCli)
        else cli.attach(ctsCli)
    }

    override fun onStop() { cli.detach(ctsCli); stop(); super.onStop() }

    override fun onDestroy() {
        destroyed = true
        stop()
        // Once destroyed, the running item's report is dropped, so a CLI request would wait for its timeout.
        if (running) ctsCli.abandoned()
        io.shutdown()
        VendoredCts.detachActivity(this)
        super.onDestroy()
    }

    // ---- run ----

    private fun missingPermissions(): List<String> {
        val audio = queue.any { it.needsAudio }
        return (if (audio) listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO) else listOf(Manifest.permission.CAMERA))
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun requestAndStart() {
        val needed = missingPermissions()
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
        if (index == 0) ctsCli.started()
        itemStartedAt = SystemClock.elapsedRealtime()
        liveFailures.clear()
        status = "${index + 1}/${queue.size} · ${item.title} · 시작"
        render()
        startVendored(item)
    }

    private fun startVendored(item: SuiteItem) {
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
        val finished = SuiteReport(entries.toList(), stopRequested)
        report = finished
        running = false
        status = if (stopRequested) "중단됨 · 완료된 항목까지만 표시합니다" else "완료"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
        if (ctsCli.driving()) file(finished)
    }

    /** The CLI's copy of the report: the JSON map and the same text the 공유 button sends, written off the main thread. */
    private fun file(finished: SuiteReport) {
        val id = intent.getStringExtra("cli_request_id") ?: return
        val device = "${Build.MANUFACTURER} ${Build.MODEL}"
        val build = "Android ${Build.VERSION.RELEASE} (${Build.DISPLAY})"
        val app = appVersion()
        io.execute {
            try {
                val dir = cli.artifactDir(id)
                val json = File(dir, "cts-suite.json").apply {
                    writeText((CliJson.of(finished.toJsonMap(device, build, app)) as JSONObject).toString(2), Charsets.UTF_8)
                }
                val text = File(dir, "cts-suite.txt").apply { writeText(SuiteReportPresenter.fullText(device, build, app, finished), Charsets.UTF_8) }
                ctsCli.reportSaved(finished.cancelled, finished.passed, finished.failed, finished.skipped, finished.notRun, json, text)
            } catch (e: Exception) { ctsCli.saveFailed(e.message ?: "Cannot write the suite report") }
        }
    }

    private fun appVersion(): String = runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "" }.getOrDefault("")

    /** Stops the running item; the queue ends when that item reports, and the items after it are marked 실행 안 함. */
    private fun stop() {
        if (!running) return
        stopRequested = true
        status = "중단 중…"
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
        val detail = SuiteReportPresenter.vendoredDetail(liveFailures)
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
            app = appVersion(),
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
