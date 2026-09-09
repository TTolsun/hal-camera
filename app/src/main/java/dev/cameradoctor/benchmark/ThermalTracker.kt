package dev.cameradoctor.benchmark

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Tracks the thermal status for the length of one run (docs/PLAN-BenchMarker-v0.3.md chapter 6).
 *
 * The run-wide maximum decides comparability, not the value at the start: a run that begins at LIGHT and
 * throttles to SEVERE in the middle would look comparable if only the start and end values were kept, so every
 * change is also reported to [onChange] and lands in the events as `thermal_status`.
 *
 * Below API 29 there is no listener and no current status; every value stays null and no THERMAL flag is raised.
 */
class ThermalTracker(context: Context, private val onChange: (Int) -> Unit = {}) {
    private val power = context.getSystemService(PowerManager::class.java)
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    var start: Int? = null
        private set
    var max: Int? = null
        private set
    var current: Int? = null
        private set

    val available: Boolean get() = Build.VERSION.SDK_INT >= 29 && power != null

    /** Registers the listener and records the starting status. Safe to call once per run. */
    fun start() {
        if (Build.VERSION.SDK_INT < 29) return
        val pm = power ?: return
        val initial = pm.currentThermalStatus
        start = initial
        current = initial
        max = initial
        val l = PowerManager.OnThermalStatusChangedListener { status ->
            current = status
            max = maxOf(max ?: status, status)
            onChange(status)
        }
        listener = l
        pm.addThermalStatusListener({ it.run() }, l)
    }

    /** Unregisters the listener and returns the status at the end of the run. */
    fun stop(): Int? {
        val pm = power
        val l = listener
        if (Build.VERSION.SDK_INT >= 29 && pm != null && l != null) {
            pm.removeThermalStatusListener(l)
            val end = pm.currentThermalStatus
            current = end
            max = maxOf(max ?: end, end)
        }
        listener = null
        return current
    }
}
