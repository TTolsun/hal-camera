package dev.halcamera.ui

import android.app.Instrumentation
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import dev.halcamera.ConcurrentCameraActivity
import dev.halcamera.MainActivity
import dev.halcamera.camera.ConcurrentPlan
import dev.halcamera.camera.LiveSize
import dev.halcamera.telemetry.FlightRecorder
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Explicit device journey: button actions, HAL controls, native JPEGs and independent playable MP4s. */
object MultiSplitChecks {
    fun run(test: Instrumentation): String {
        fun <T> ui(block: () -> T): T {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
            val value = AtomicReference<Result<T>>()
            test.runOnMainSync { value.set(runCatching(block)) }
            return value.get().getOrThrow()
        }
        fun <T> field(owner: Any,name: String): T {
            @Suppress("UNCHECKED_CAST")
            return owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
        }
        fun waitFor(label: String, condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis()+30_000
            while (!ui(condition)) {
                check(SystemClock.uptimeMillis() < end) { "Timed out: $label" }
                SystemClock.sleep(100)
            }
        }
        val log = mutableListOf<String>()
        fun pass(text: String) { log += text; test.sendStatus(0,Bundle().apply { putString("stream","$text\n") }) }
        val directory = File(test.targetContext.getExternalFilesDir(null),"multi-split-checks").apply { mkdirs() }
        fun screenshot(name: String) {
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(directory,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            }
        }
        fun descendants(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
            add(root); repeat(root.childCount) { i -> root.getChild(i)?.let { addAll(descendants(it)) } }
        }
        fun accessible(label: String): AccessibilityNodeInfo? {
            val root = test.uiAutomation.rootInActiveWindow ?: return null
            check(root.packageName?.toString() == "dev.halcamera") { "Another app owns the foreground" }
            return descendants(root).firstOrNull { it.isVisibleToUser &&
                (it.contentDescription?.toString() == label || it.text?.toString() == label) }
        }
        fun click(label: String) {
            repeat(8) {
                val node = accessible(label)
                if (node != null && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    SystemClock.sleep(250); return
                }
                test.uiAutomation.rootInActiveWindow?.let { root ->
                    descendants(root).firstOrNull { it.isScrollable && it.className?.toString() == "android.widget.ScrollView" }
                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
                SystemClock.sleep(150)
            }
            error("Button not found: $label")
        }
        val mainActivity = test.startActivitySync(Intent(test.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        ui { mainActivity.engineName = "Camera2"; mainActivity.videoMode = false; mainActivity.resetModeSettings(); mainActivity.restartCamera() }
        waitFor("Live ready") { field<Boolean>(mainActivity,"ready") && !mainActivity.closing }
        val monitor = test.addMonitor(ConcurrentCameraActivity::class.java.name,null,false)
        ui { field<View>(mainActivity,"multiButton").performClick() }
        var activity = checkNotNull(test.waitForMonitorWithTimeout(monitor,30_000)) as ConcurrentCameraActivity
        fun ready() = waitFor("Multi ready") { field<Boolean>(activity,"ready") && !field<Boolean>(activity,"closing") }
        fun press(name: String) { ui { check(field<View>(activity,name).isEnabled); field<View>(activity,name).performClick() }; SystemClock.sleep(200) }
        fun mode(index: Int) { ui { field<List<Button>>(activity,"modeButtons")[index].performClick() }; SystemClock.sleep(300) }
        fun ids() = ui { field<List<String>>(activity,"selectedIds").toList() }
        fun latest(key: String): Any? = ui {
            val recorder = field<FlightRecorder>(activity,"recorder")
            val id = ids().first()
            recorder.snapshot().lastOrNull { it.kind == "capture_result" && it.session.endsWith("_$id") }?.values?.get(key)
        }
        ready(); screenshot("photo-stacked")
        check(ui { field<List<String>>(activity,"selectedIds").size } == 2)
        val originalIds = ids()
        val originalSession = ui { field<Any>(activity,"session") }
        press("splitButton"); click("Layout"); click("Side by side"); click("Close Split")
        check(ui { field<Any>(activity,"splitLayout").toString() } == "STACKED")
        press("splitButton"); click("Layout"); click("Side by side"); click("Apply")
        check(ui { field<Any>(activity,"session") === originalSession })
        check(ui { field<Any>(activity,"splitLayout").toString() } == "SIDE_BY_SIDE")
        screenshot("photo-side-by-side")
        pass("Split PASS: top sheet, cancel, Apply, side-by-side without camera restart")
        press("cameraButton"); click("Camera for Split screen 1"); click("Camera ${originalIds.last()}"); click("Apply"); ready()
        check(ids() == originalIds.reversed())
        press("splitButton"); click("Camera for Split screen 1"); click("Camera ${originalIds.first()}"); click("Apply"); ready()
        check(ids() == originalIds)
        press("splitButton"); click("Layout"); click("Grid"); click("Apply"); screenshot("photo-grid")
        pass("Split PASS: camera icon, screen assignment, reordered devices, Grid")
        val bar = ui { field<LiveControlBar>(activity,"controlBar") }
        ui { bar.handle.performClick() }
        check(ui { field<View>(bar,"manual").visibility == View.GONE })
        check(ui { !field<View>(bar,"bracket").isEnabled })
        fun control(name: String) { ui { field<View>(bar,name).performClick() }; SystemClock.sleep(250) }
        control("aeLock"); waitFor("AE locked") { latest("aeLock") == true }; control("aeLock")
        waitFor("AE unlocked") { latest("aeLock") == false }
        control("afLock"); check(ui { bar.controls.afLock }); control("afLock"); check(ui { !bar.controls.afLock })
        control("ev"); click("Increase exposure compensation by one step")
        waitFor("EV applied") { (latest("evApplied") as? Number)?.toInt() == 1 }
        click("Reset exposure compensation to EV 0"); waitFor("EV reset") { (latest("evApplied") as? Number)?.toInt() == 0 }
        control("ev"); control("flash"); click("Torch"); check(ui { bar.controls.flash.name == "TORCH" })
        control("flash"); click("Flash off"); check(ui { bar.controls.flash.name == "OFF" })
        ui { bar.handle.performClick() }
        val zoom = ui { field<ExpandingZoomControl>(activity,"zoomControl") }
        ui { zoom.getChildAt(0).performClick() }
        ui { (0 until zoom.childCount).map(zoom::getChildAt).first { it.contentDescription?.startsWith("2.0x zoom") == true }.performClick() }
        waitFor("zoom 2x") { (latest("zoomRatio") as? Number)?.toFloat() == 2f }
        ui { (0 until zoom.childCount).map(zoom::getChildAt).first { it.contentDescription?.startsWith("1.0x zoom") == true }.performClick() }
        waitFor("zoom reset") { (latest("zoomRatio") as? Number)?.toFloat() == 1f }
        pass("Controls PASS: Manual absent, AEB disabled, AE/AF, EV reset, Torch/Off, zoom HAL result")
        press("callbackButton")
        val graph = ui { field<ResultCallbackGraph>(activity,"callbackGraph") }
        waitFor("Callback camera selector") { field<View>(graph,"camera").visibility == View.VISIBLE }
        ui { field<View>(graph,"camera").performClick(); field<View>(graph,"duration").performClick() }
        screenshot("callback-secondary"); press("callbackButton")
        pass("Callback PASS: show/hide, camera ID selection, Hold")
        press("shutter"); waitFor("photos saved") { !field<Boolean>(activity,"takingPhoto") }
        val report = ui { field<String>(activity,"report") }
        check(!report.contains("failed") && report.contains("saved")) { report }
        val group = report.substringAfter("Group ").substringBefore('\n')
        val resolver = test.targetContext.contentResolver
        val manifestUri = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,arrayOf("_id"),"_display_name = ?",
            arrayOf("${group}_concurrent.json"),null)!!.use { c -> check(c.moveToFirst()); ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,c.getLong(0)) }
        val manifest = JSONObject(resolver.openInputStream(manifestUri)!!.bufferedReader().use { it.readText() })
        val records = manifest.getJSONArray("cameras")
        repeat(records.length()) { i ->
            val entry = records.getJSONObject(i)
            check(entry.getString("composition") == "camera JPEG")
            check(entry.getLong("imageTimestampNs") == entry.getLong("sensorTimestampNs"))
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(android.net.Uri.parse(entry.getString("uri")))!!.use { BitmapFactory.decodeStream(it,null,options) }
            check("${options.outWidth}x${options.outHeight}" == entry.getString("configuredJpeg"))
            pass("Photo PASS: camera ${entry.getString("cameraId")} ${options.outWidth}x${options.outHeight}, native JPEG, matching sensor timestamp")
        }
        press("resultText"); click("Close Capture details")
        mode(3); ready()
        fun videos(): List<Pair<Long,String>> = resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,arrayOf("_id","_display_name"),
            "relative_path = ?",arrayOf("DCIM/HALCamera/"),"_id DESC")!!.use { c -> buildList {
                while (c.moveToNext()) add(c.getLong(0) to c.getString(1))
            } }
        val before = videos().map { it.first }.toSet()
        press("shutter"); waitFor("recording") { field<Boolean>(activity,"recording") }
        check(ui { !field<View>(activity,"splitButton").isEnabled && !field<View>(activity,"galleryButton").isEnabled && !field<View>(activity,"labButton").isEnabled })
        screenshot("video-recording"); SystemClock.sleep(3500); press("shutter")
        waitFor("videos saved") { !field<Boolean>(activity,"recording") && !field<Boolean>(activity,"videoPending") }
        val saved = videos().filter { it.first !in before }; check(saved.size == ids().size) { "Expected separate videos, got $saved" }
        val sizes = ui { field<List<LiveSize>>(activity,"outputSizes") }
        saved.forEach { (id,name) ->
            val index = ids().indexOfFirst { name.contains("_cam$it.") }; check(index >= 0) { name }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(test.targetContext,ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,id))
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
                check(width == sizes[index].width && height == sizes[index].height)
                check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() > 2000)
                check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes")
                val frame = checkNotNull(retriever.getFrameAtTime(1_000_000))
                File(directory,"video-cam${ids()[index]}.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG,100,it) }; frame.recycle()
                pass("Video PASS: camera ${ids()[index]} $width x $height, silent, independently playable")
            } finally { retriever.release() }
        }
        press("galleryButton"); SystemClock.sleep(600); test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ready()
        press("labButton"); SystemClock.sleep(600); test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ready()
        press("reportButton"); SystemClock.sleep(6000); click("Close Events ZIP saved")
        pass("Navigation PASS: Gallery and Lab return; Save Events ZIP completes")
        mode(0); waitFor("Photo Live restored") { field<Boolean>(mainActivity,"ready") && !mainActivity.closing && !mainActivity.videoMode }
        ui { field<View>(mainActivity,"multiVideoButton").performClick() }
        activity = checkNotNull(test.waitForMonitorWithTimeout(monitor,30_000)) as ConcurrentCameraActivity
        ready(); mode(1); waitFor("Video Live restored") { field<Boolean>(mainActivity,"ready") && !mainActivity.closing && mainActivity.videoMode }
        test.removeMonitor(monitor)
        pass("Modes PASS: Photo, Video, Multi P, Multi V; cameras release before Live restoration")
        return log.joinToString("\n")
    }
}
