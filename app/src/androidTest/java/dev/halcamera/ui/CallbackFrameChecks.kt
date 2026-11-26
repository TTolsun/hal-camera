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
import dev.halcamera.camera.DualOutputs
import dev.halcamera.telemetry.Event
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

    fun dualGl(): String {
        val log = mutableListOf<String>()
        for (video in listOf(false, true)) {
            val dual = test.startActivitySync(Intent(test.targetContext, DualPreviewActivity::class.java)
                .putExtra(DualPreviewActivity.EXTRA_ENGINE, "CameraX")
                .putExtra(DualPreviewActivity.EXTRA_VIDEO, video)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DualPreviewActivity
            try {
                ui { dual.field<View>("callbackButton").performClick() }
                waitFor("both GL preview streams") {
                    dual.recorder.snapshot().filter { it.kind == DualOutputs.GL_INPUT }
                        .map { it.values["stream"] }.distinct().size == 2 && dual.frame()?.number != null
                }
                log += checkGlFrames(dual, if (video) "video preview" else "photo preview")
                if (video) {
                    ui { dual.field<View>("recordButton").performClick() }
                    waitFor("dual recording") { dual.recording }
                    log += checkGlFrames(dual, "recording")
                    ui { dual.field<View>("recordButton").performClick() }
                    waitFor("video preview reopened") {
                        !dual.recording && !dual.closing && dual.field<View?>("recordButton")?.isEnabled == true
                    }
                }
                ui {
                    if (dual.field<View>("callbackGraph").visibility == View.VISIBLE) dual.onBackPressedDispatcher.onBackPressed()
                    dual.onBackPressedDispatcher.onBackPressed()
                }
                waitFor("Dual exit after camera close") { dual.isFinishing }
            } catch (failure: Exception) {
                throw IllegalStateException("${log.joinToString("\n")}\n${failure.message}", failure)
            } finally { ui { if (!dual.isFinishing) dual.finish() }; test.waitForIdleSync() }
        }
        return log.joinToString("\n")
    }

    /** Reconstruct each displayed value from the graph's own input snapshot, even on devices
     * whose preview timestamps do not match either physical result. */
    private fun checkGlFrames(dual: DualPreviewActivity, phase: String): String {
        val session = ui { dual.field<String>("sessionId") }
        val since = SystemClock.elapsedRealtimeNanos()
        val displayed = mutableSetOf<Long>()
        val numbers = mutableMapOf<String, Int>()
        val ids = listOf("preview_main", "preview_sub")
        val sensors = ids.zip(ui { requireNotNull(dual.pair).toList() }).toMap()
        repeat(24) {
            SystemClock.sleep(130)
            ui {
                val graph = dual.field<ResultCallbackGraph>("callbackGraph")
                val frame = dual.frame() ?: return@ui
                val number = frame.number ?: return@ui
                val cutoff = graph.field<Long>("nowNs")
                val events = graph.field<List<Event>>("latestEvents").filter { it.session == session && it.atNs <= cutoff }
                val start = events.singleOrNull { it.kind == "capture_started" && it.frame == number } ?: return@ui
                if (start.atNs < since || !displayed.add(number)) return@ui
                check(frame.outputs() == ids)
                val origin = if (start.values["firstStart"] == true) start.atNs else start.values["previousStartAtNs"] as Long
                val result = events.singleOrNull { it.kind == "capture_result" && it.frame == number }
                val times = result?.values?.get("physicalTimestamps") as? Map<*, *>
                val outputs = events.last { it.kind == "callback_streams" }.values["outputs"] as List<*>
                for (row in frame.rows.filter { it.id in ids }) {
                    val physical = outputs.filterIsInstance<Map<*, *>>().single { it["id"] == row.id }["physicalId"] as String
                    check(physical == sensors[row.id])
                    val stamp = times?.get(physical) as? Long
                    val expected = events.filter { it.kind == DualOutputs.GL_INPUT && it.values["stream"] == row.id &&
                        it.values["physicalId"] == physical && stamp != null && stamp > 0 && it.sensorNs == stamp }
                        .map { (it.atNs - origin) / 1e6 }
                    check(expected == row.latenciesMs) { "$phase #$number ${row.id} physical $physical: expected $expected, saw $row" }
                    if (expected.isEmpty()) check(row.state == "Awaiting data")
                    numbers[row.id] = numbers.getOrDefault(row.id, 0) + expected.size
                }
            }
        }
        check(displayed.size >= 3) {
            "$phase: only ${displayed.size} frames advanced, status=${ui { dual.field<String>("status") }}, " +
                "errors=${dual.recorder.snapshot().filter { it.kind == "camera_error" }}"
        }
        val end = SystemClock.elapsedRealtimeNanos()
        // Give the last buffer's metadata a chance to arrive, and keep results just before the sample too.
        SystemClock.sleep(300)
        val events = dual.recorder.snapshot().filter { it.session == session }
        val results = events.filter { it.kind == "capture_result" }
        val logical = results.mapNotNull { it.sensorNs }.toSet()
        val counts = ids.map { id ->
            val inputs = events.filter { it.kind == DualOutputs.GL_INPUT && it.values["stream"] == id && it.atNs in since..end }
            check(inputs.size >= 3 && inputs.map { it.sensorNs }.distinct().size == inputs.size)
            val physical = inputs.map { it.values["physicalId"] }.distinct().single()
            check(physical == sensors[id])
            val stamps = results.mapNotNull {
                (it.values["physicalTimestamps"] as? Map<*, *>)?.get(physical) as? Long }.toSet()
            val logicalOnly = inputs.count { it.sensorNs !in stamps && it.sensorNs in logical }
            "$id (ID $physical): raw=${inputs.size}, exact=${inputs.count { it.sensorNs in stamps }}, " +
                "logicalOnly=$logicalOnly, shown=${numbers[id]}"
        }
        return "$phase: ${displayed.size} displayed frames; ${counts.joinToString()}"
    }

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
