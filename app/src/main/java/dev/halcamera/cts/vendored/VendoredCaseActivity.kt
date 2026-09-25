package dev.halcamera.cts.vendored

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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.halcamera.ctsvendor.VendoredCatalog
import dev.halcamera.ctsvendor.VendoredCts
import dev.halcamera.ctsvendor.VendoredResult
import dev.halcamera.ctsvendor.VendoredRun
import dev.halcamera.ctsvendor.VendoredTest
import dev.halcamera.ctsvendor.VendoredVerdict
import dev.halcamera.ui.Look

/**
 * Runs one vendored CTS test method, chosen by [EXTRA_TEST_ID], with JUnit on a worker thread. This activity
 * *is* the Camera2SurfaceViewCtsActivity the test's ActivityTestRule expects: the vendored onCreate builds the
 * SurfaceView and registers the holder callback, and this class moves that SurfaceView into the app's own
 * screen without re-creating it, so the test's updatePreviewSurface() keeps working on the same holder.
 */
class VendoredCaseActivity : Camera2SurfaceViewCtsActivity() {
    private lateinit var test: VendoredTest
    private val main = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var runButton: android.widget.Button
    private lateinit var headlineView: TextView
    private lateinit var results: LinearLayout
    private var run: VendoredRun? = null
    private var result: VendoredResult? = null
    private var status = "대기 중"
    private var startedAt = 0L
    private val liveFailures = ArrayList<String>()
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        VendoredCts.install(this)
        super.onCreate(savedInstanceState)
        test = VendoredCatalog.byId(intent.getStringExtra(EXTRA_TEST_ID)) ?: run {
            Toast.makeText(this, "알 수 없는 CTS 테스트입니다", Toast.LENGTH_SHORT).show(); finish(); return
        }
        VendoredCts.attachActivity(this)

        // The vendored layout is a bare LinearLayout around the SurfaceView; take the view, drop the layout.
        val preview = surfaceView
        (preview.parent as? ViewGroup)?.removeView(preview)
        preview.contentDescription = "테스트 프리뷰"
        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { main.post { fitPreview(width, height) } }
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
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

        body.addView(Look.titleBar(this, test.method, 22, "이전 화면으로 돌아가기") { finish() })
        body.addView(Look.text(this, test.className, 12, Look.onDark, mono = true), lp(top = 8))
        body.addView(Look.text(this, "카메라 전부를 차례로 검사합니다. 녹화 테스트는 카메라마다 CamcorderProfile 하나에 몇 초씩 걸리므로 수 분이 걸릴 수 있습니다.", 12, Look.onDarkMuted), lp(top = 4))
        body.addView(Look.text(this, VendoredReportPresenter.DISCLAIMER, 11, Look.onDarkMuted), lp(top = 4))

        // The preview keeps its frame; the test resizes only the buffer, as in the CTS activity.
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        body.addView(frame, lp(top = 12).apply { height = dp(180) })

        statusView = Look.text(this, status, 13, Look.onDarkMuted)
        body.addView(statusView, lp(top = 8))
        val actions = Look.row(this)
        runButton = Look.ghostButton(this, "실행", dark = true) { if (run != null) stop() else requestAndStart() }
        actions.addView(runButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(Look.ghostButton(this, "복사", dark = true) { copy() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        actions.addView(Look.ghostButton(this, "공유", dark = true) { share() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        body.addView(actions, lp(top = 8))
        headlineView = Look.text(this, "", 19, Look.onDark, bold = true)
        body.addView(headlineView, lp(top = 18))
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(results, lp())
        render()
    }

    override fun onStop() { stop(); super.onStop() }

    override fun onDestroy() {
        destroyed = true
        stop()
        VendoredCts.detachActivity(this)
        super.onDestroy()
    }

    // ---- run ----

    private fun requestAndStart() {
        val needed = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) start() else requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) start() else { status = "카메라와 마이크 권한이 필요합니다"; render() }
    }

    private fun start() {
        if (run != null) return
        if (!surfaceView.holder.surface.isValid) { status = "프리뷰 surface를 기다리는 중입니다. 다시 눌러 주세요."; render(); return }
        result = null; liveFailures.clear()
        val r = VendoredRun(test, object : VendoredRun.Listener {
            override fun onStarted(displayName: String) = post { status = "실행 중 · $displayName"; render() }
            override fun onFailure(displayName: String, message: String) = post { liveFailures += message; render() }
            override fun onFinished(result: VendoredResult) = post {
                this@VendoredCaseActivity.result = result; run = null
                status = if (result.cancelled) "중단됨" else "완료"
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                render()
            }
        })
        run = r
        startedAt = SystemClock.elapsedRealtime()
        status = "시작"
        // A screen-off would stop the activity and abort the run.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, 1000)
        Thread({ r.run() }, "cts-vendored").start()
    }

    private fun stop() { run?.stop() }

    /** Once a second while running: the elapsed time is the only sign of progress a JUnit body gives. */
    private val ticker = object : Runnable {
        override fun run() {
            if (destroyed || this@VendoredCaseActivity.run == null) return
            render()
            main.postDelayed(this, 1000)
        }
    }

    private fun post(action: () -> Unit) { main.post { if (!destroyed) action() } }

    /** Letterbox the SurfaceView to the buffer aspect inside its frame; the buffer size itself is fixed by the test. */
    private fun fitPreview(width: Int, height: Int) {
        val preview = surfaceView
        val frame = preview.parent as? FrameLayout ?: return
        val fw = frame.width; val fh = frame.height
        if (fw == 0 || fh == 0 || width <= 0 || height <= 0) return
        val scale = minOf(fw.toFloat() / width, fh.toFloat() / height)
        preview.layoutParams = FrameLayout.LayoutParams((width * scale).toInt(), (height * scale).toInt(), Gravity.CENTER)
    }

    // ---- screen ----

    private fun render() {
        val running = run != null
        statusView.text = if (running && startedAt > 0) "$status · ${VendoredReportPresenter.duration(SystemClock.elapsedRealtime() - startedAt)}" else status
        runButton.text = if (running) "중단" else "실행"
        val done = result
        headlineView.visibility = if (done == null) View.GONE else View.VISIBLE
        if (done != null) {
            headlineView.text = VendoredReportPresenter.headline(done)
            headlineView.setTextColor(
                when {
                    done.cancelled -> Look.statusWarn
                    done.verdict == VendoredVerdict.PASS -> Look.statusPass
                    done.verdict == VendoredVerdict.FAIL -> Look.statusFail
                    else -> Look.statusUnknown
                }
            )
        }
        results.removeAllViews()
        if (done != null && done.verdict == VendoredVerdict.SKIP && !done.cancelled) {
            val card = Look.card(this, dark = true)
            card.addView(Look.text(this, "건너뛴 이유", 14, Look.statusUnknown, bold = true))
            card.addView(wide(Look.text(this, VendoredReportPresenter.detail(done), 11, Look.onDark, mono = true)), lp(top = 10))
            results.addView(card, lp(top = 12))
        }
        val failures = done?.failures ?: liveFailures
        failures.forEachIndexed { index, failure ->
            val card = Look.card(this, dark = true)
            card.addView(Look.text(this, "실패 ${index + 1}", 14, Look.statusFail, bold = true))
            card.addView(wide(Look.text(this, failure, 11, Look.onDark, mono = true)), lp(top = 10))
            results.addView(card, lp(top = 12))
        }
    }

    private fun fullText(): String? = result?.let {
        VendoredReportPresenter.fullText(
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            build = "Android ${Build.VERSION.RELEASE} (${Build.DISPLAY})",
            app = runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "" }.getOrDefault(""),
            result = it
        )
    }

    private fun copy() {
        val text = fullText() ?: return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("cts-vendored", text))
        Toast.makeText(this, "결과를 복사했습니다", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val text = fullText() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "HAL CAM CTS vendored · ${test.source}")
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
        const val EXTRA_TEST_ID = "test_id"
        private const val REQUEST_PERMISSIONS = 41
    }
}
