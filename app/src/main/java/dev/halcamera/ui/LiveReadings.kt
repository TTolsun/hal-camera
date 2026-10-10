package dev.halcamera.ui

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.View

import dev.halcamera.camera.LiveControls
import dev.halcamera.camera.FlashMode

import dev.halcamera.camera.WhiteBalance
import dev.halcamera.telemetry.Event
import dev.halcamera.telemetry.FlightRecorder
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** UI updates run on the main thread; system telemetry sampling runs on the supplied worker. */
class LiveReadings(
    private val context: Context,
    private val metrics: LiveMeasurementView,
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

    fun update(events: List<Event>, frames: List<Event>, time: Long, sessionId: String, controls: LiveControls) {
        last = readout.read(events, sessionId, time)
        if (metrics.visibility == View.VISIBLE) updateReadings(frames, time, controls)
        if (time - lastSystemNs >= 1_000_000_000L && samplingSystem.compareAndSet(false, true)) {
            lastSystemNs = time
            // PSS collection can block for tens of milliseconds. Never run it on the preview's UI thread.
            systemWorker.execute { try { sampleSystem() } finally { samplingSystem.set(false) } }
        }
    }

    private fun updateReadings(frames: List<Event>, time: Long, controls: LiveControls) {
        val frame = frames.lastOrNull()?.takeIf { time - it.atNs < 1_500_000_000L }
        fun num(key: String) = (frame?.values?.get(key) as? Number)?.toDouble()
        // Measurements and camera state come first; active settings remain visible after the controls close.
        val extras = listOfNotNull(
            LiveControlBar.flashState(num("flashState")?.toInt(), controls))
        val settings = listOfNotNull(
            controls.flash.takeIf { it != FlashMode.OFF }?.label,
            "AE Lock".takeIf { controls.aeLock },
            "AF Lock".takeIf { controls.afLock },
            "AEB".takeIf { controls.bracket },
            "M".takeIf { controls.manual.exposure != null },
            controls.manual.focusDiopters?.let { "MF ${"%.2f".format(Locale.US,it)} D" },
            controls.manual.wb.takeIf { it != WhiteBalance.AUTO }?.let { "WB ${it.label}" },
        ).plus(extras).joinToString(" · ")
        metrics.bind(frame?.values.orEmpty(), details = settings)
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
