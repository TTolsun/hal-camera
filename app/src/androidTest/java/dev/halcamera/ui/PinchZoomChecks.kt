package dev.halcamera.ui

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import dev.halcamera.MainActivity
import dev.halcamera.camera.zoomRange
import dev.halcamera.telemetry.FlightRecorder
import java.io.File
import java.util.Locale
import kotlin.math.abs

/** Opt-in hardware regression: am instrument -w -e pinch_zoom true .../cli.CliStoreInstrumentation. */
object PinchZoomChecks {
    fun run(instrumentation: Instrumentation): String {
        fun <T> ui(action: () -> T): T {
            var value: T? = null
            var error: Throwable? = null
            instrumentation.runOnMainSync { try { value = action() } catch (t: Throwable) { error = t } }
            error?.let { throw it }
            @Suppress("UNCHECKED_CAST")
            return value as T
        }
        fun waitFor(label: String, predicate: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 15_000
            while (!ui(predicate)) {
                check(SystemClock.uptimeMillis() < end) { "Timed out: $label" }
                SystemClock.sleep(100)
            }
        }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun <T> field(name: String): T {
            @Suppress("UNCHECKED_CAST")
            return MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T
        }
        val rail = ui { field<ExpandingZoomControl>("zoomControl") }
        val recorder = ui { field<FlightRecorder>("recorder") }
        val log = mutableListOf<String>()
        fun ratio() = ui { activity.zoomRatio }
        fun pinch(outward: Boolean) {
            waitFor("Live window focus") { activity.hasWindowFocus() }
            val bounds = ui {
                val preview = field<View>("previewHost")
                val xy = IntArray(2).also { preview.getLocationOnScreen(it) }
                floatArrayOf(xy[0] + preview.width / 2f, xy[1] + preview.height * .4f, preview.width * .08f, preview.width * .32f)
            }
            val start = SystemClock.uptimeMillis()
            fun event(action: Int, count: Int, fraction: Float) {
                val spread = if (outward) bounds[2] + (bounds[3] - bounds[2]) * fraction
                    else bounds[3] - (bounds[3] - bounds[2]) * fraction
                val properties = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                val coords = Array(count) { i -> MotionEvent.PointerCoords().apply {
                    x = bounds[0] + if (i == 0) -spread else spread; y = bounds[1]; pressure = 1f; size = 1f
                } }
                val e = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, count, properties, coords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { instrumentation.sendPointerSync(e) } finally { e.recycle() }
            }
            event(MotionEvent.ACTION_DOWN, 1, 0f)
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 0f)
            repeat(24) { SystemClock.sleep(20); event(MotionEvent.ACTION_MOVE, 2, (it + 1) / 24f) }
            event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 1f)
            event(MotionEvent.ACTION_UP, 1, 1f)
            instrumentation.waitForIdleSync()
        }
        fun verifyLabel() = ui {
            val active = (0 until rail.childCount).map { rail.getChildAt(it) as Button }.filter { it.isSelected }
            check(active.size == 1 && active.single().visibility == View.VISIBLE) { "Missing selected zoom circle" }
            val value = activity.zoomRatio
            val number = if (value == value.toInt().toFloat()) "${value.toInt()}" else "%.1f".format(Locale.US, value).removePrefix("0")
            check(active.single().text.toString() == "${number}×") { "Label does not match $value" }
        }
        fun verifyApplied() {
            val value = ratio()
            val after = SystemClock.elapsedRealtimeNanos()
            waitFor("camera applied $value") {
                recorder.snapshot().any { it.atNs > after && it.kind == "capture_result" &&
                    abs(((it.values["zoomRatio"] as? Number)?.toFloat() ?: -1f) - value) < .03f }
            }
        }
        fun preset(value: Float) {
            ui { (0 until rail.childCount).map { rail.getChildAt(it) as Button }.first { it.isSelected }.performClick() }
            SystemClock.sleep(350)
            ui {
                (0 until rail.childCount).map { rail.getChildAt(it) as Button }
                    .first { it.contentDescription.toString().startsWith("${value}x zoom") }.performClick()
                rail.collapse(false)
            }
            check(abs(ratio() - value) < .001f)
            verifyLabel(); verifyApplied()
        }
        val originalEngine = ui { activity.engineName }
        try {
            waitFor("initial preview") { field<Boolean>("ready") }
            for (engine in listOf("Camera2", "CameraX")) {
                if (ui { activity.engineName } != engine) {
                    ui { field<Button>("engineButton").performClick() }
                    waitFor("$engine ready") { field<Boolean>("ready") && activity.engineName == engine }
                }
                ui { rail.collapse(false) }
                preset(1f)
                val start = SystemClock.elapsedRealtimeNanos()
                pinch(true)
                check(ratio() > 1.2f) { "$engine pinch did not zoom" }
                verifyLabel(); verifyApplied()
                val continuous = ratio()
                ui { activity.updateCameraChoices() }
                check(ratio() == continuous) { "Continuous zoom snapped to a preset" }
                check(recorder.snapshot().none { it.atNs > start && it.kind.startsWith("touch_") }) { "Pinch triggered touch metering" }
                val directory = File(activity.getExternalFilesDir(null), "pinch-validation").apply { mkdirs() }
                SystemClock.sleep(1200)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(directory, "$engine-pinch.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                pinch(false)
                check(ratio() < continuous) { "$engine pinch did not zoom out" }
                verifyLabel(); verifyApplied()
                preset(2f)
                val range = ui { zoomRange(activity.getSystemService(android.hardware.camera2.CameraManager::class.java), activity.cameraId) }
                repeat(6) { pinch(true) }
                check(abs(ratio() - range.second) < .001f) { "Maximum clamp: ${ratio()} vs ${range.second}" }
                verifyLabel(); verifyApplied()
                repeat(8) { pinch(false) }
                check(abs(ratio() - range.first) < .001f) { "Minimum clamp: ${ratio()} vs ${range.first}" }
                verifyLabel(); verifyApplied()
                preset(1f)
                log += "$engine PASS: pinch in/out, continuous label, refresh preservation, preset, min/max, applied capture results"
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "${log.last()}\n") })
            }
        } finally {
            ui {
                if (activity.engineName != originalEngine) field<Button>("engineButton").performClick()
            }
        }
        return log.joinToString("\n")
    }
}
