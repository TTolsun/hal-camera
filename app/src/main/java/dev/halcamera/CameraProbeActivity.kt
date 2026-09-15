package dev.halcamera

import android.content.ClipData
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.halcamera.camera.CameraProbeEntry
import dev.halcamera.camera.CameraProbeReader
import dev.halcamera.camera.CameraProbeSnapshot
import dev.halcamera.camera.CameraProbeText
import dev.halcamera.ui.IconButton
import dev.halcamera.ui.Look
import dev.halcamera.ui.showSelectionPopup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * PROBE: the static capability report of [CameraProbeReader], one camera at a time on screen and every camera
 * in the exported file. Nothing here opens a camera, so the screen can be reached from LIVE without stopping
 * the preview.
 */
class CameraProbeActivity : ComponentActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private var snapshot: CameraProbeSnapshot? = null
    private var error: String? = null
    private var cameraKey: String? = null
    private var busy = false
    private var listScrollY = 0
    private var lastRenderFull = false
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraKey = savedInstanceState?.getString("camera") ?: intent.getStringExtra(EXTRA_CAMERA_ID)
        scroll = ScrollView(this).apply { setBackgroundColor(Look.expertTile) }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left + dp(16), bars.top + dp(16), bars.right + dp(16), bars.bottom + dp(16))
            insets
        }
        reload()
    }

    override fun onDestroy() { io.shutdown(); super.onDestroy() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("camera", cameraKey)
        super.onSaveInstanceState(outState)
    }

    private fun reload() {
        val manager = getSystemService(CameraManager::class.java)
        work({ CameraProbeReader(manager).read() }) { result ->
            snapshot = result
            error = null
            if (result.camera(cameraKey.orEmpty()) == null) cameraKey = result.cameras.firstOrNull()?.key
            render()
        }
    }

    private fun render() {
        // The loading screen is short, so its scroll position is not the one to come back to.
        if (lastRenderFull) listScrollY = scroll.scrollY
        lastRenderFull = false
        body.removeAllViews()
        text("PROBE", 24, true)
        text("CameraCharacteristics를 읽어 HAL이 공개한 사양을 그대로 표시합니다. 카메라를 열지 않으므로 측정값이 아닙니다. 실제 동작은 BENCHMARK로 확인합니다.")
        backButton("돌아가기") { finish() }
        if (busy) { text("카메라 사양을 읽고 있습니다."); return }
        error?.let { text("읽지 못했습니다: $it"); button("다시 읽기") { reload() }; return }
        val snap = snapshot ?: return
        val cameras = snap.cameras
        val current = snap.camera(cameraKey.orEmpty()) ?: cameras.firstOrNull()
        if (cameras.isNotEmpty()) {
            button("Camera · ${current?.title ?: "—"} ▾") { anchor ->
                showSelectionPopup(anchor, cameras.map { it.title }, cameras.indexOf(current)) { cameraKey = cameras[it].key; render() }
            }
        }
        val exports = Look.row(this)
        exports.addView(Look.ghostButton(this, "TXT 공유", dark = true) { export(snap, "txt") }, LinearLayout.LayoutParams(0, dp(48), 1f))
        exports.addView(Look.ghostButton(this, "JSON 공유", dark = true) { export(snap, "json") }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        body.addView(exports, lp())
        text("공유 파일에는 모든 카메라(${cameras.size}개)가 들어갑니다. 이미지 픽셀은 포함되지 않습니다.", 12)
        section("DEVICE", snap.device.joinToString("\n") { "${it.key.padEnd(16)}${it.value}" })
        if (current == null) { text("공개된 카메라가 없습니다."); return }
        current.sections.forEach { s -> section("${s.title} (${s.rows.size})", CameraProbeText.section(s).lines().drop(1).joinToString("\n").trimEnd()) }
        if (snap.errors.isNotEmpty()) section("ERRORS", snap.errors.joinToString("\n"))
        lastRenderFull = true
        scroll.post { scroll.scrollTo(0, listScrollY) }
    }

    private fun section(title: String, content: String) {
        val card = Look.card(this, dark = true)
        card.addView(Look.text(this, title, 14, Look.onDarkMuted, bold = true))
        card.addView(Look.text(this, content.ifBlank { "(none)" }, 12, Look.onDark, mono = true).apply { setTextIsSelectable(true) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        body.addView(card, lp())
    }

    private fun export(snap: CameraProbeSnapshot, kind: String) {
        work({
            val dir = File(cacheDir, "benchmark-exports").apply { check(isDirectory || mkdirs()) }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val model = Build.MODEL.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val file = File(dir, "camera-probe-$model-$stamp.$kind")
            val content = if (kind == "json") (json(snap.toJsonMap()) as JSONObject).toString(2) else CameraProbeText.render(snap)
            file.writeText(content, Charsets.UTF_8)
            file
        }, fail = { message(it.message ?: "내보내기에 실패했습니다."); render() }) { share(it, if (kind == "json") "application/json" else "text/plain") }
    }

    /** org.json stays at the file boundary; the snapshot itself is a Map. */
    private fun json(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { obj -> value.forEach { (k, v) -> obj.put(k.toString(), json(v)) } }
        is List<*> -> JSONArray().also { arr -> value.forEach { arr.put(json(it)) } }
        else -> value
    }

    private fun share(file: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                clipData = ClipData.newRawUri("camera-probe", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "카메라 사양 내보내기"))
        } catch (e: Exception) { message(e.message ?: "내보내기에 실패했습니다.") }
    }

    private fun <T> work(task: () -> T, fail: (Throwable) -> Unit = { error = it.message ?: it.javaClass.simpleName; render() }, done: (T) -> Unit) {
        if (busy) return
        busy = true
        render()
        io.execute {
            val result = runCatching(task)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                result.fold(done, fail)
            }
        }
    }

    private fun text(value: String, size: Int = 14, bold: Boolean = false) {
        body.addView(Look.text(this, value, size, if (size < 14) Look.onDarkMuted else Look.onDark, bold = bold), lp())
    }
    private fun button(label: String, click: (View) -> Unit) {
        body.addView(Look.ghostButton(this, label, dark = true) {}.apply {
            setOnClickListener { if (!busy) click(it) }
            isEnabled = !busy
            minHeight = dp(48)
        }, lp())
    }
    private fun backButton(label: String, click: () -> Unit) {
        body.addView(IconButton(this, R.drawable.ic_action_back, label) { click() }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { topMargin = dp(10) })
    }
    private fun message(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun lp() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    private fun dp(value: Int) = Look.dp(this, value)

    companion object {
        /** A logical camera id, or "logical.physical" for a physical camera behind it ([CameraProbeEntry.key]). */
        const val EXTRA_CAMERA_ID = "camera_id"
    }
}
