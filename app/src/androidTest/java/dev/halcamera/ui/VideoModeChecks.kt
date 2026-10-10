package dev.halcamera.ui

import android.app.Instrumentation
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import dev.halcamera.MainActivity
import dev.halcamera.camera.*
import dev.halcamera.telemetry.FlightRecorder
import java.util.concurrent.atomic.AtomicReference

/** Explicit hardware checks, including audio for EIS; run with -e video_mode true. */
object VideoModeChecks {
    fun run(test: Instrumentation): String {
        fun <T> ui(block: () -> T): T {
            val result = AtomicReference<Result<T>>()
            test.runOnMainSync { result.set(runCatching(block)) }
            return result.get().getOrThrow()
        }
        fun waitFor(label: String, condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 25_000
            while (!ui(condition)) {
                check(SystemClock.uptimeMillis() < end) { "Timed out: $label" }
                SystemClock.sleep(100)
            }
        }
        val activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun <T> field(name: String): T {
            @Suppress("UNCHECKED_CAST")
            return try { MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T }
            catch (_: NoSuchFieldException) {
                (MainActivity::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }.get(activity) as Lazy<T>).value
            }
        }
        val log = mutableListOf<String>()
        val recorder = ui { field<FlightRecorder>("recorder") }
        fun pinch() {
            val area = ui {
                val view = field<View>("previewHost")
                val xy = IntArray(2); view.getLocationOnScreen(xy)
                floatArrayOf(xy[0] + view.width * .5f, xy[1] + view.height * .45f, view.width * .1f)
            }
            val start = SystemClock.uptimeMillis()
            fun event(action: Int, count: Int, spread: Float) {
                val properties = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                val coords = Array(count) { i -> MotionEvent.PointerCoords().apply {
                    x = area[0] + (if (i == 0) -1 else 1) * spread; y = area[1]; pressure = 1f; size = 1f
                } }
                val value = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, count, properties, coords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                test.sendPointerSync(value); value.recycle(); SystemClock.sleep(25)
            }
            event(MotionEvent.ACTION_DOWN, 1, area[2])
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, area[2])
            repeat(12) { event(MotionEvent.ACTION_MOVE, 2, area[2] * (1 + it / 8f)) }
            event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, area[2] * 2.4f)
            event(MotionEvent.ACTION_UP, 1, area[2] * 2.4f)
        }
        for (api in listOf("Camera2", "CameraX")) {
            waitFor("idle before Photo") { !activity.closing && !activity.mediaBusy() }
            val photoStart = SystemClock.elapsedRealtimeNanos()
            ui {
                activity.engineName = api; activity.cameraId = "0"; activity.videoMode = false
                activity.resetModeSettings()
                val defaults = liveStreamSupport(activity.getSystemService(android.hardware.camera2.CameraManager::class.java).getCameraCharacteristics("0")).defaults()
                activity.streamSettings[activity.streamKey()] = defaults.copy(stabilization = LiveStabilization.VIDEO)
                activity.restartCamera()
            }
            waitFor("$api Photo stabilization off") {
                recorder.snapshot().any { it.atNs > photoStart && it.kind == "capture_result" &&
                    (it.values["opticalStabilization"] as? Number)?.toInt() == 0 &&
                    (it.values["videoStabilization"] as? Number)?.toInt() == 0 }
            }
            log += "$api Photo PASS: requested EIS is gated to OIS/EIS OFF in capture results"
            test.sendStatus(0,android.os.Bundle().apply { putString("stream","${log.last()}\n") })
            for (kind in listOf("jpeg", "yuv", "eis-yuv", "service-pip") + if (api == "Camera2") listOf("preview-eis-yuv", "physical-pip") else emptyList()) {
                waitFor("idle") { !activity.closing && !activity.mediaBusy() }
                ui {
                    activity.engineName = api; activity.cameraId = "0"; activity.videoMode = true
                    activity.resetModeSettings()
                    if (kind.endsWith("yuv")) {
                        val defaults = liveStreamSupport(activity.getSystemService(android.hardware.camera2.CameraManager::class.java).getCameraCharacteristics("0")).defaults()
                        activity.streamSettings[activity.streamKey()] = defaults.copy(yuv = LiveSize(640,480), jpeg = null, jpegFromYuv = true,
                            stabilization = when (kind) {
                                "eis-yuv" -> LiveStabilization.VIDEO
                                "preview-eis-yuv" -> LiveStabilization.PREVIEW
                                else -> LiveStabilization.AUTO
                            })
                    }
                    activity.restartCamera()
                }
                waitFor("$api $kind video ready") { field<Boolean>("ready") && !activity.mediaBusy() }
                if (kind.endsWith("pip")) {
                    val source = ui { (activity.engine as PipCamera).pipSources.firstOrNull { it.physical == (kind == "physical-pip") } }
                    checkNotNull(source) { "$api $kind source unavailable" }
                    ui { field<LivePipController>("pipUi").select(source) }
                    waitFor("$api $kind PIP ready") { !field<LivePipController>("pipUi").busy }
                    check(ui { field<LivePipController>("pipUi").selected } != null) { "$api $kind PIP rejected" }
                }
                val media = ui { activity.engine as MediaCapture }
                val start = SystemClock.elapsedRealtimeNanos()
                val saved = AtomicReference<Result<android.net.Uri>?>()
                val audio = kind.contains("eis")
                ui { media.startRecording(audio, {}, { saved.set(it) }) }
                waitFor("$api $kind recording") { activity.recordingVideo }
                pinch()
                check(ui { activity.zoomRatio } > 1.2f) { "$api $kind pinch did not zoom" }
                val requestedZoom = ui { activity.zoomRatio }
                waitFor("$api $kind recorded zoom applied") {
                    recorder.snapshot().any { it.atNs > start && it.kind == "capture_result" &&
                        ((it.values["zoomRatio"] as? Number)?.toFloat()?.let { actual ->
                            kotlin.math.abs(actual - requestedZoom) < .05f
                        } == true) }
                }
                val photo = AtomicReference<Result<PhotoResult>?>()
                waitFor("$api $kind snapshot ready") { media.snapshot.canCapture }
                ui { media.captureSnapshot { photo.set(it) } }
                waitFor("$api $kind snapshot saved") { photo.get() != null }
                val directory = java.io.File(activity.getExternalFilesDir(null), "video-validation").apply { mkdirs() }
                test.uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(directory, "$api-$kind.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                val result = photo.get()!!.getOrThrow()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                activity.contentResolver.openInputStream(result.uris.first())!!.use { BitmapFactory.decodeStream(it, null, bounds) }
                check(bounds.outWidth > 0 && bounds.outHeight > 0)
                if (kind.endsWith("yuv")) check(setOf(bounds.outWidth, bounds.outHeight) == setOf(640,480))
                SystemClock.sleep(1200)
                val events = recorder.snapshot().filter { it.atNs > start }
                check(events.none { it.kind in setOf("session_configured", "video_session_configured", "bound", "open_call") }) {
                    "$api $kind rebuilt session on Start"
                }
                val timestamps = events.filter { it.kind == "preview_available" }.map { it.atNs }
                val gapMs = timestamps.zipWithNext { a,b -> (b-a)/1_000_000 }.maxOrNull()
                ui { media.stopRecording() }
                waitFor("$api $kind saved video") { saved.get() != null }
                val uri = saved.get()!!.getOrThrow()
                MediaMetadataRetriever().use { metadata ->
                    metadata.setDataSource(activity,uri)
                    check((metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0) > 500)
                    check(metadata.getFrameAtTime(500_000) != null) { "Video could not be decoded" }
                    if (audio) check(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes")
                }
                val message = "$api $kind PASS: stable session, pinch, snapshot ${bounds.outWidth}x${bounds.outHeight}, playable MP4, max preview callback gap ${gapMs}ms"
                log += message
                test.sendStatus(0,android.os.Bundle().apply { putString("stream","$message\n") })
            }
        }
        return log.joinToString("\n")
    }
}
