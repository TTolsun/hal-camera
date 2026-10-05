package dev.halcamera.ui

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import dev.halcamera.camera.LiveControlSupport
import dev.halcamera.camera.LiveControls
import dev.halcamera.telemetry.Event
import dev.halcamera.telemetry.FlightRecorder
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** UI updates run on the main thread; system telemetry sampling runs on the supplied worker. */
class LiveReadings(
    private val context: Context,
    private val metrics: TextView,
    private val recorder: FlightRecorder,
    private val systemWorker: Executor,
) {
    private val readout = LiveReadout()
    /** The latest readout; a Mark keeps the one current when it was pressed. */
    var last: LiveReading? = null
        private set
    private var previousCpu = Process.getElapsedCpuTime()
    private var previousSample = SystemClock.elapsedRealtime()
    private var lastSystemNs = 0L
    private val samplingSystem = AtomicBoolean(false)

    fun update(events: List<Event>, frames: List<Event>, time: Long, sessionId: String, controls: LiveControls, support: LiveControlSupport, zoomRatio: Float, panelObservedKey: String? = null) {
        last = readout.read(events, sessionId, time)
        if (metrics.visibility == View.VISIBLE) updateReadings(frames, time, controls, support, zoomRatio, panelObservedKey)
        if (time - lastSystemNs >= 1_000_000_000L && samplingSystem.compareAndSet(false, true)) {
            lastSystemNs = time
            // PSS collection can block for tens of milliseconds. Never run it on the preview's UI thread.
            systemWorker.execute { try { sampleSystem() } finally { samplingSystem.set(false) } }
        }
    }

    private fun updateReadings(frames: List<Event>, time: Long, controls: LiveControls, support: LiveControlSupport, zoomRatio: Float, panelObservedKey: String?) {
        val frame = frames.lastOrNull()?.takeIf { time - it.atNs < 1_500_000_000L }
        fun num(key: String) = (frame?.values?.get(key) as? Number)?.toDouble()
        fun fmt(value: Double?, pattern: String) = value?.let { pattern.format(Locale.US, it) } ?: "—"
        // Two lines at most over the preview: the measurement, then the camera's state. Values the screen already shows
        // are left out (the zoom rail's ratio, the buttons' EV), and the extras join line 2 in priority order only while
        // they fit its width. With a flash mode on, the flash state leads, since no button can show it. An applied EV or
        // zoom that differs from the request appears only once the last ten results all differ, not for the few frames
        // the pipeline lags behind every change. Lens position and everything else stay in the incident ZIP.
        val recent = frames.takeLast(10)
        fun differs(key: String, want: Double, tolerance: Double) = recent.size == 10 &&
            recent.all { e -> (e.values[key] as? Number)?.toDouble()?.let { kotlin.math.abs(it - want) > tolerance } == true }
        val extras = listOfNotNull(
            LiveControlBar.afState(num("af")?.toInt()),
            LiveControlBar.evApplied(num("evApplied")?.toInt()?.takeIf { differs("evApplied", controls.evIndex.toDouble(), 0.5) }, controls, support),
            num("zoomRatio")?.takeIf { differs("zoomRatio", zoomRatio.toDouble(), 0.01 * zoomRatio) }?.let { "Zoom ${"%.2f".format(Locale.US, it)}x applied" },
            frame?.values?.get("physicalId")?.let { "Phys $it" })
        val room = (metrics.width - metrics.paddingLeft - metrics.paddingRight).toFloat()
        var state = listOfNotNull(LiveControlBar.aeState(num("ae")?.toInt()), LiveControlBar.flashState(num("flashState")?.toInt(), controls)).joinToString(" · ")
        for (extra in extras) { val next = "$state · $extra"; if (room > 0f && metrics.paint.measureText(next) <= room) state = next else break }
        val measurement = listOfNotNull(
            "FPS ${fmt(num("resultFps"), "%.1f")}",
            "ISO ${num("iso")?.toInt() ?: "—"}".takeUnless { panelObservedKey == "iso" },
            "Exp ${fmt(num("exposureNs")?.div(1e6), "%.2fms")}".takeUnless { panelObservedKey == "exposureNs" },
        ).joinToString(" · ")
        val text = "$measurement\n$state"
        if (metrics.text.toString() != text) metrics.text = text
    }

    private fun sampleSystem() {
        val elapsed = SystemClock.elapsedRealtime(); val cpu = Process.getElapsedCpuTime()
        val percent = if (elapsed > previousSample) 100.0 * (cpu - previousCpu) / (elapsed - previousSample) else 0.0
        previousSample = elapsed; previousCpu = cpu
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024.0
        val thermal = if (Build.VERSION.SDK_INT >= 29) context.getSystemService(PowerManager::class.java).currentThermalStatus else null
        val thermalName = thermal?.let { listOf("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN").getOrNull(it) ?: "$it" } ?: "N/A"
        recorder.record("app", "system_sample", values = mapOf("appCpuPercentOneCore" to percent, "pssMb" to memory, "thermalStatus" to thermal, "thermalName" to thermalName))
    }
}
