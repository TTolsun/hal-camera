package dev.halcamera.ui

import android.app.Instrumentation
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import dev.halcamera.MainActivity
import dev.halcamera.chooseDepth
import dev.halcamera.selectMode
import dev.halcamera.camera.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in hardware checks. Saves ordinary OEM JPEGs to MediaLibrary, never pixels to the report. */
object PortraitChecks {
    fun uiChecks(test: Instrumentation): String {
        fun <T> ui(block: () -> T): T {
            val result = AtomicReference<Result<T>>()
            test.runOnMainSync { result.set(runCatching(block)) }
            return result.get().getOrThrow()
        }
        fun nodes(root: android.view.View): Sequence<android.view.View> = sequence {
            yield(root)
            if (root is android.view.ViewGroup) for (index in 0 until root.childCount) yieldAll(nodes(root.getChildAt(index)))
        }
        fun roots() = android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.isShown }
        fun text(value: String) = roots().asReversed().asSequence().flatMap(::nodes)
            .filterIsInstance<android.widget.TextView>().firstOrNull { it.text.toString() == value }
        fun click(value: String) {
            var target: android.view.View = requireNotNull(text(value))
            while (!target.isClickable) target = target.parent as android.view.View
            check(target.isEnabled); check(target.performClick())
        }
        fun waitFor(label: String, condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 25_000
            while (!ui(condition)) {
                check(SystemClock.uptimeMillis() < end) { "Timed out: $label" }; SystemClock.sleep(100)
            }
        }
        val activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val directory = File(test.targetContext.getExternalFilesDir(null), "portrait-ui").apply { mkdirs() }
        fun screenshot(name: String) = ui {
            // App-owned windows only; camera pixels and SystemUI notifications are excluded.
            val alpha = activity.previewHost.alpha
            val thumbnails = nodes(activity.window.decorView).filterIsInstance<RecentMediaButton>().map { it to it.alpha }.toList()
            activity.previewHost.alpha = 0f
            thumbnails.forEach { it.first.alpha = 0f }
            val bitmap = android.graphics.Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height,
                android.graphics.Bitmap.Config.ARGB_8888)
            try {
                val canvas = android.graphics.Canvas(bitmap); canvas.drawColor(android.graphics.Color.BLACK)
                roots().forEach { root ->
                    val xy = IntArray(2); root.getLocationOnScreen(xy)
                    canvas.save(); canvas.translate(xy[0].toFloat(), xy[1].toFloat()); root.draw(canvas); canvas.restore()
                }
                File(directory, name).outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { activity.previewHost.alpha = alpha; thumbnails.forEach { it.first.alpha = it.second }; bitmap.recycle() }
        }
        fun enterPortrait() {
            ui { activity.chooseDepth() }
            waitFor("Depth menu") { text("Portrait (Bokeh)") != null }
            screenshot("depth-portrait-menu.png")
            ui { click("Portrait (Bokeh)") }
            waitFor("Portrait resolution picker") { text("JPEG 640x480") != null }
            screenshot("portrait-resolutions.png")
            ui { click("JPEG 640x480") }
            waitFor("Portrait preview") { activity.portraitConfig != null && activity.ready && !activity.closing }
            ui { check(activity.depthModeButton.isSelected); check(activity.mediaButton.isEnabled); check(!activity.zoomControl.isEnabled) }
        }
        try {
            waitFor("initial Photo") { activity.ready && !activity.closing }
            enterPortrait()
            screenshot("live-portrait.png")
            ui { activity.selectMode(true) }
            waitFor("Video recovery") { activity.ready && activity.videoMode && activity.portraitConfig == null }
            ui { activity.selectMode(false) }
            waitFor("Photo recovery") { activity.ready && !activity.videoMode && activity.portraitConfig == null }
            enterPortrait()
            ui { activity.chooseDepth() }
            waitFor("Depth menu Off") { text("Off") != null }
            ui { click("Off") }
            waitFor("Off recovery") { activity.ready && activity.portraitConfig == null && activity.depthSize == null }
        } finally { ui { activity.finish() } }
        return "PORTRAIT_UI_CHECKS_OK · Depth entry, resolution picker, selected controls, Photo/Video/Off recovery · $directory"
    }

    fun metadata(test: Instrumentation): String {
        check(Build.VERSION.SDK_INT >= 31)
        val manager = test.targetContext.getSystemService(android.hardware.camera2.CameraManager::class.java)
        val values = manager.cameraIdList.map { id ->
            val extension = manager.getCameraExtensionCharacteristics(id)
            val result = runCatching { extension.getExtensionSupportedSizes(
                android.hardware.camera2.CameraExtensionCharacteristics.EXTENSION_BOKEH, android.graphics.ImageFormat.DEPTH_JPEG)
                .map { it.toString() } }
            mapOf("camera" to id, "bokehDepthJpegSizes" to result.getOrNull(), "readError" to result.exceptionOrNull()?.toString())
        }
        val output = File(test.targetContext.getExternalFilesDir(null), "portrait-depth-metadata.json")
        output.writeText(JSONObject(mapOf("model" to Build.MODEL, "api" to Build.VERSION.SDK_INT, "cameras" to values)).toString(2))
        return "PORTRAIT_METADATA_OK · ${output.absolutePath}"
    }

    fun run(test: Instrumentation): String {
        check(Build.VERSION.SDK_INT >= 31)
        fun <T> ui(block: () -> T): T {
            val result = AtomicReference<Result<T>>()
            test.runOnMainSync { result.set(runCatching(block)) }
            return result.get().getOrThrow()
        }
        val activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val report = File(test.targetContext.getExternalFilesDir(null), "portrait-checks.json")
        val checks = mutableListOf<Map<String, Any?>>()
        fun persist() = report.writeText(JSONObject(mapOf("model" to Build.MODEL, "android" to Build.VERSION.RELEASE,
            "api" to Build.VERSION.SDK_INT, "checks" to checks)).toString(2))
        fun waitReady(label: String) {
            val end = SystemClock.uptimeMillis() + 25_000
            while (!ui { activity.ready && !activity.closing }) {
                check(SystemClock.uptimeMillis() < end) { "Timed out: $label" }
                SystemClock.sleep(100)
            }
        }
        fun configure(id: String, config: PortraitConfig?) {
            ui {
                activity.cameraId = id; activity.engineName = "Camera2"; activity.videoMode = false
                activity.resetModeSettings(); activity.portraitConfig = config; activity.paused = false
                activity.restartCamera()
            }
            waitReady("camera $id / $config")
        }
        try {
            waitReady("initial Photo")
            val manager = activity.manager
            val ids = manager.cameraIdList.filter { portraitSupport(manager, it).available }
            check(ids.isNotEmpty()) { "No OEM Bokeh cameras connected" }
            for (id in ids) {
                val support = portraitSupport(manager, id)
                val largest = support.jpegs.filter { it.width.toLong() * it.height <= 16_000_000 }.lastOrNull() ?: support.jpegs.first()
                val sizes = listOf(support.jpegs.first(), largest).distinct()
                for (size in sizes) {
                    val config = requireNotNull(support.config(size))
                    configure(id, config)
                    check(ui { activity.engine is PortraitCameraEngine })
                    checks += mapOf("scenario" to "preview", "camera" to id, "preview" to config.preview.toString(),
                        "jpeg" to size.toString(), "result" to "PASS")
                    persist()
                }
                val latch = CountDownLatch(1)
                val captured = AtomicReference<Result<PhotoResult>>()
                ui { (activity.engine as MediaCapture).capturePhoto("portrait-device-$id") { captured.set(it); latch.countDown() } }
                check(latch.await(65, TimeUnit.SECONDS)) { "JPEG wait timed out for $id" }
                val photo = captured.get().getOrThrow()
                val file = photo.artifacts.single { it.mime == "image/jpeg" }
                val bytes = test.targetContext.contentResolver.openInputStream(file.uri)!!.use { it.readBytes() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                check(setOf(bounds.outWidth, bounds.outHeight) == setOf(largest.width, largest.height)) { "JPEG resolution mismatch" }
                val metadata = photo.artifacts.single { it.mime == "application/json" }
                val text = test.targetContext.contentResolver.openInputStream(metadata.uri)!!.bufferedReader().use { it.readText() }
                check(JSONObject(text).getJSONObject("capture").getString("effect") == "manufacturer_bokeh")
                checks += mapOf("scenario" to "capture", "camera" to id, "jpeg" to largest.toString(), "name" to file.name,
                    "bytes" to bytes.size, "sha256" to MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
                    "encodedWidth" to bounds.outWidth, "encodedHeight" to bounds.outHeight,
                    "exifOrientation" to JpegOrientation.read(bytes), "effect" to "manufacturer_bokeh", "result" to "PASS")
                configure(id, null)
                checks += mapOf("scenario" to "return_to_photo", "camera" to id, "result" to "PASS")
                persist()
            }
            // Repeated OEM/ordinary ownership transfer, plus a close immediately after open was requested.
            val config = requireNotNull(portraitSupport(manager, ids.first()).config(portraitSupport(manager, ids.first()).jpegs.first()))
            repeat(3) { configure(ids.first(), config); configure(ids.first(), null) }
            ui {
                activity.portraitConfig = config; activity.restartCamera()
                activity.portraitConfig = null; activity.restartCamera()
            }
            waitReady("cancelled Portrait opening")
            checks += mapOf("scenario" to "three_reopens_and_cancel_open", "result" to "PASS")
            persist()
        } catch (error: Throwable) {
            checks += mapOf("scenario" to "failure", "error" to error.stackTraceToString(), "result" to "FAIL")
            persist(); throw error
        } finally { ui { activity.finish() } }
        return "PORTRAIT_CHECKS_OK · ${checks.size} checks · ${report.absolutePath}"
    }
}
