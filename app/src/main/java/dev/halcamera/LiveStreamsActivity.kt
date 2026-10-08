package dev.halcamera

import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.halcamera.camera.LiveStreamSettings
import dev.halcamera.camera.liveStreamSupport
import dev.halcamera.camera.cameraXStreamSupport
import dev.halcamera.ui.LiveStreamSettingsView
import dev.halcamera.ui.Look
import java.util.concurrent.Executors

/** Camera-free Lab destination. Only an explicit save returns settings to its parent. */
class LiveStreamsActivity : ComponentActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private var draft: LiveStreamSettings? = null
    private var scroll: ScrollView? = null
    private val backDescription get() = if (intent.getBooleanExtra(EXTRA_FROM_LIVE, false)) "Live 프리뷰로 돌아가기" else "Lab으로 돌아가기"

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.LabTheme)
        super.onCreate(savedInstanceState)
        Look.configureLabWindow(this)
        if (intent.getBooleanExtra(EXTRA_DUAL, false)) {
            showDualStreams()
            return
        }
        draft = (savedInstanceState?.getSerializable(EXTRA_SETTINGS)
            ?: intent.getSerializableExtra(EXTRA_SETTINGS)) as? LiveStreamSettings
        showMessage("설정을 불러오는 중입니다.")
        val id = intent.getStringExtra(WorkbenchActivity.EXTRA_CAMERA_ID).orEmpty()
        io.execute {
            val result = runCatching {
                val support = liveStreamSupport(getSystemService(CameraManager::class.java).getCameraCharacteristics(id))
                if (intent.getStringExtra(WorkbenchActivity.EXTRA_ENGINE) == "CameraX") cameraXStreamSupport(this, id, support) else support
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                result.fold({ support ->
                    val restore: (() -> Unit)? = if (intent.getBooleanExtra(EXTRA_HAS_GOOD, false)) ({
                        save(intent.getSerializableExtra(EXTRA_GOOD_SETTINGS) as? LiveStreamSettings)
                    }) else null
                    val page = LiveStreamSettingsView.create(this, support, draft ?: support.defaults(),
                        intent.getStringExtra(EXTRA_STATUS).orEmpty(), restore, ::finish, { draft = it }, ::save,
                        backDescription,
                        (intent.getSerializableExtra(EXTRA_SETTINGS) as? LiveStreamSettings) ?: support.defaults(),
                        intent.getStringExtra(WorkbenchActivity.EXTRA_ENGINE) ?: "Camera2")
                    showPage(page.root, page.scroll)
                    savedInstanceState?.getInt("scroll_y")?.let { y -> page.scroll.post { page.scroll.scrollTo(0, y) } }
                }, { showMessage("지원 정보를 불러오지 못했습니다. ${it.message.orEmpty()}") })
            }
        }
    }

    /** Dual owns its output combination; never offer single-camera controls that it cannot apply. */
    private fun showDualStreams() {
        val video = intent.getBooleanExtra(DualPreviewActivity.EXTRA_VIDEO, false)
        val engine = intent.getStringExtra(WorkbenchActivity.EXTRA_ENGINE) ?: "Camera2"
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Look.titleBar(this@LiveStreamsActivity, "Live Streams", 34, backDescription, ::finish))
        }
        fun row(label: String, value: String) {
            body.addView(Look.text(this, label, 13, Look.inkMuted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Look.dp(this@LiveStreamsActivity, 24) })
            body.addView(Look.text(this, value, 17, Look.ink), LinearLayout.LayoutParams(-1, -2))
        }
        row("Mode", "$engine · Dual · ${if (video) "V" else "P"}")
        row("Physical ID · 1 / 2", intent.getStringExtra(EXTRA_DUAL_PAIR) ?: "—")
        row("Preview × 2 · ${if (video && engine == "CameraX") "고정" else "자동"}",
            intent.getStringExtra(EXTRA_DUAL_SIZE)?.replace('x', '×') ?: "—")
        if (video) row("Recording · 고정", "H.264 · MP4 × 2 · 무음")
        showPage(ScrollView(this).apply { addView(body) })
    }

    private fun save(settings: LiveStreamSettings?) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SETTINGS, settings)
            .putExtra(WorkbenchActivity.EXTRA_ENGINE, intent.getStringExtra(WorkbenchActivity.EXTRA_ENGINE))
            .putExtra(WorkbenchActivity.EXTRA_CAMERA_ID, intent.getStringExtra(WorkbenchActivity.EXTRA_CAMERA_ID)))
        finish()
    }

    private fun showMessage(message: String) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Look.titleBar(this@LiveStreamsActivity, "Live Streams", 34, backDescription, ::finish))
            addView(Look.text(this@LiveStreamsActivity, message, 15, Look.inkMuted))
        }
        showPage(ScrollView(this).apply { addView(body) })
    }

    private fun showPage(page: View, scrolling: ScrollView? = page as? ScrollView) {
        scroll = scrolling
        page.setBackgroundColor(Look.canvas)
        setContentView(page)
        ViewCompat.setOnApplyWindowInsetsListener(page) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val margin = Look.pageMargin(view, bars.left + bars.right)
            view.setPadding(bars.left + margin, bars.top + Look.dp(this, 16), bars.right + margin, bars.bottom + Look.dp(this, 24))
            insets
        }
        page.addOnLayoutChangeListener { view, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l != oldR - oldL) ViewCompat.requestApplyInsets(view)
        }
        ViewCompat.requestApplyInsets(page)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putSerializable(EXTRA_SETTINGS, draft)
        outState.putInt("scroll_y", scroll?.scrollY ?: 0)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_DUAL = "live_stream_dual"
        const val EXTRA_DUAL_SIZE = "live_stream_dual_size"
        const val EXTRA_DUAL_PAIR = "live_stream_dual_pair"
        const val EXTRA_SETTINGS = "live_stream_settings"
        const val EXTRA_GOOD_SETTINGS = "live_stream_good_settings"
        const val EXTRA_HAS_GOOD = "live_stream_has_good"
        const val EXTRA_STATUS = "live_stream_status"
        const val EXTRA_FROM_LIVE = "live_stream_from_live"
    }
}
