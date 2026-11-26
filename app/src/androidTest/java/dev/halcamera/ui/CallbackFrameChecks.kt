package dev.halcamera.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.View
import dev.halcamera.DualPreviewActivity
import dev.halcamera.MainActivity
import dev.halcamera.camera.LiveSize
import dev.halcamera.camera.LiveStreamSettings
import dev.halcamera.camera.LiveVideo
import java.util.concurrent.FutureTask

/** Opt-in hardware regression: takes a pair of real sensor photos for each Hold setting. */
class CallbackFrameChecks(private val test: Instrumentation) {
    private fun <T> ui(action: () -> T): T {
        val task = FutureTask(action)
        test.runOnMainSync(task)
        return task.get()
    }

    private fun waitFor(label: String, predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 25000
        while (!ui(predicate)) {
            check(SystemClock.uptimeMillis() < until) { "Timed out waiting for $label" }
            SystemClock.sleep(20)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> Any.field(key: String): T =
        javaClass.getDeclaredField(key).apply { isAccessible = true }.get(this) as T

    private fun Activity.frame(): CallbackTimelineFrame? =
        field<ResultCallbackGraph>("callbackGraph").field("renderedFrame")

    private fun CallbackTimelineFrame?.outputs() = this?.rows.orEmpty().map { it.id }
        .filterNot { it == "start" || it == "all" || it.startsWith("metadata:") }

    fun run(): String {
        val context = test.targetContext
        val prefs = context.getSharedPreferences("callback_timeline", 0)
        val original = prefs.all["shot_seconds"] as? Int
        val log = mutableListOf<String>()
        try {
            for (seconds in listOf(0, 5)) {
                prefs.edit().putInt("shot_seconds", seconds).commit()
                val dual = test.startActivitySync(Intent(context, DualPreviewActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DualPreviewActivity
                try {
                    val preview = listOf("preview_main", "preview_sub")
                    val photo = listOf("photo_main", "photo_sub")
                    waitFor("Dual shutter") { dual.field<View?>("recordButton")?.isEnabled == true }
                    ui { dual.field<View>("callbackButton").performClick() }
                    waitFor("live preview rows") { dual.frame()?.number != null && dual.frame().outputs() == preview }
                    ui { dual.field<View>("recordButton").performClick() }
                    try {
                        waitFor("still frame with only two YUV rows") { dual.frame()?.number != null && dual.frame().outputs() == photo }
                    } catch (failure: IllegalStateException) {
                        val history = dual.recorder.snapshot().filter { it.kind == "callback_streams" ||
                            it.kind == "image_available" || it.values["requestTag"] == "dual_photo" }
                        val events = history.joinToString { "${it.kind}/${it.frame}/${it.sensorNs}/${it.values["stream"]}/${it.values["frameConfiguration"]}/${it.values["physicalTimestamps"]}" }
                        error("Hold ${seconds}s: ${failure.message}; displayed=${ui { dual.frame() }}, events=$events")
                    }
                    val capture = dual.recorder.snapshot().last { it.kind == "capture_started" && it.values["requestTag"] == "dual_photo" }
                    waitFor("resumed native preview") { dual.recorder.snapshot().any {
                        it.atNs > capture.atNs && it.kind == "capture_started" && it.values["frameConfiguration"] == "preview"
                    } }
                    if (seconds > 0) {
                        waitFor("held still and both sensor callbacks during live preview") {
                            val frame = dual.frame()
                            frame != null && frame.number == capture.frame && frame.outputs() == photo &&
                                frame.rows.filter { it.id in photo }.all { it.latenciesMs.isNotEmpty() }
                        }
                        val until = ui { dual.field<ResultCallbackGraph>("callbackGraph")
                            .field<ResultCallbackTimeline>("timeline").autoHoldUntilNs!! }
                        val remainingMs = (until - SystemClock.elapsedRealtimeNanos()) / 1_000_000
                        if (remainingMs > 400) SystemClock.sleep(remainingMs - 400)
                        check(ui { dual.frame()?.number == capture.frame && dual.frame().outputs() == photo })
                    }
                    waitFor("preview after Hold") { val frame = dual.frame()
                        (frame?.number ?: -1) > capture.frame!! && frame.outputs() == preview }
                    log += "Dual Hold ${seconds}s: preview -> still #${capture.frame} -> preview"
                } finally { ui { dual.finish() }; test.waitForIdleSync() }
            }
            val live = test.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            try {
                waitFor("single camera ready") { live.ready && !live.closing }
                ui {
                    live.engineName = "CameraX"; live.videoMode = true
                    live.streamSettings[live.streamKey()] = LiveStreamSettings(LiveSize(1280,720), LiveSize(640,480),
                        LiveSize(1920,1080), null, LiveVideo(LiveSize(1920,1080),30,"H264"))
                    live.restartCamera()
                    live.showCallbacks(true)
                }
                waitFor("CameraX prepare failure with live base output rows") {
                    live.recorder.snapshot().any { it.session == live.sessionId && it.kind == "camera_error" &&
                        it.values["message"].toString().contains("chooses the recording codec") } &&
                        live.frame()?.number != null && live.frame().outputs() == listOf("preview","analysis_keep_latest","still") &&
                        live.frame()!!.rows.any { it.id == "analysis_keep_latest" && it.latenciesMs.isNotEmpty() }
                }
                log += "CameraX rejected fixed codec; Preview, YUV and JPEG remained, YUV delivered, no Recording row"
            } finally { ui { live.finish() } }
        } finally {
            val edit = prefs.edit()
            if (original == null) edit.remove("shot_seconds") else edit.putInt("shot_seconds",original)
            edit.commit()
        }
        return log.joinToString("\n")
    }
}
