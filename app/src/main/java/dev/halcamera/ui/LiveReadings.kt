package dev.halcamera.ui

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.widget.TextView
import dev.halcamera.camera.LiveControlSupport
import dev.halcamera.camera.LiveControls
import dev.halcamera.telemetry.Event
import dev.halcamera.telemetry.FlightRecorder
import java.util.Locale

/**
 * The numbers the Live screen reads from the flight recorder on every tick: the two lines over the preview
 * ([metrics]), and in the diagnostics [panel] the readout card, the strip, the callback timeline and the app's
 * CPU, memory and thermal sample. Main thread only.
 */
class LiveReadings(
    private val context: Context,
    private val metrics: TextView,
    private val panel: DiagnosticsPanel,
    private val recorder: FlightRecorder,
) {
    private val readout = LiveReadout()
    /** The latest readout; a Mark keeps the one current when it was pressed. */
    var last: LiveReading? = null
        private set
    private var previousCpu = Process.getElapsedCpuTime()
    private var previousSample = SystemClock.elapsedRealtime()
    private var lastSystemNs = 0L

    fun update(events: List<Event>, frames: List<Event>, time: Long, sessionId: String, controls: LiveControls, support: LiveControlSupport, zoomRatio: Float) {
        updateReadout(events, frames, time, sessionId)
        updateReadings(events, frames, time, sessionId, controls, support, zoomRatio)
        if (time - lastSystemNs >= 1_000_000_000L) { sampleSystem(); lastSystemNs = time }
    }

    private fun updateReadings(events: List<Event>, frames: List<Event>, time: Long, sessionId: String, controls: LiveControls, support: LiveControlSupport, zoomRatio: Float) {
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
        metrics.text = "FPS ${fmt(num("resultFps"), "%.1f")} · ISO ${num("iso")?.toInt() ?: "—"} · Exp ${fmt(num("exposureNs")?.div(1e6), "%.2fms")}\n$state"
        if (frame == null) { panel.timeline.text = "수신 중인 프레임 없음"; panel.stripText.text = "Partial —   Buffer — ms"; return }
        val imageEvents = events.filter { it.session == sessionId && it.kind == "image_available" }
        val matched = frames.asReversed().firstOrNull { r -> r.sensorNs != null && imageEvents.any { it.sensorNs == r.sensorNs } } ?: frame
        val start = events.lastOrNull { it.session == sessionId && it.kind == "capture_started" && it.frame == matched.frame }
        val image = imageEvents.lastOrNull { it.sensorNs == matched.sensorNs }
        fun offset(e: Event?) = if (e != null && start != null) "%+.2f ms".format(Locale.US, (e.atNs - start.atNs) / 1e6) else "—"
        panel.timeline.text = "Frame #${matched.frame} · observed callbacks\nStart    ${if (start != null) "+0.00 ms" else "—"}\nPartial  ${offset(matched)}\nBuffer   ${offset(image)}\n센서 시각으로 연결 · HAL 처리 시간과 다름"
        fun ms(e: Event?) = if (e != null && start != null) (e.atNs - start.atNs) / 1e6 else null
        panel.timelineView.update(matched.frame, ms(matched), ms(image), last?.baselinePartialMs, last?.baselineBufferMs)
        fun offset(value: Double?) = value?.let { "%+.1f".format(Locale.US, it) } ?: "—"
        panel.stripText.text = "Partial ${offset(ms(matched))}   Buffer ${offset(ms(image))} ms"
    }

    /**
     * The live numbers, read once per tick and nothing more. The old version of this also recorded a
     * `health_assessment` event on every change of verdict; the flight recorder now carries only what was
     * observed, which is the only thing a ZIP opened months later can still be checked against.
     */
    private fun updateReadout(events: List<Event>, frames: List<Event>, time: Long, sessionId: String) {
        val r = readout.read(events, sessionId, time)
        panel.strip.update(frames, r.intervalRefMs, time)
        val note = when {
            !r.hasCurrentFrame -> "수신 중인 프레임 없음"
            !r.hasReference -> "기준 수집 중 (${r.baselineFrames}프레임)"
            else -> null
        }
        panel.readoutCard.text = listOfNotNull(note, LiveReadout.panelText(r)).joinToString("\n")
        last = r
    }

    private fun sampleSystem() {
        val elapsed = SystemClock.elapsedRealtime(); val cpu = Process.getElapsedCpuTime()
        val percent = if (elapsed > previousSample) 100.0 * (cpu - previousCpu) / (elapsed - previousSample) else 0.0
        previousSample = elapsed; previousCpu = cpu
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024.0
        val thermal = if (Build.VERSION.SDK_INT >= 29) context.getSystemService(PowerManager::class.java).currentThermalStatus else null
        val thermalName = thermal?.let { listOf("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN").getOrNull(it) ?: "$it" } ?: "N/A"
        panel.system.text = "App CPU ${"%.1f".format(Locale.US, percent)}% (1코어=100%)\nPSS ${"%.0f".format(Locale.US, memory)} MB · Thermal $thermalName"
        recorder.record("app", "system_sample", values = mapOf("appCpuPercentOneCore" to percent, "pssMb" to memory, "thermalStatus" to thermal, "thermalName" to thermalName))
    }
}
