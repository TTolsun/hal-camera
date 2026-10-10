package dev.halcamera.ui

import java.util.Locale

/** Each screen supplies its fresh frame and FPS source to the same readout formatter. */
internal object LiveMeasurementText {
    fun format(values: Map<String, Any?>, fps: String? = null): String =
        fields(values, fps).joinToString(" · ")

    fun fields(values: Map<String, Any?>, fps: String? = null): List<String> {
        fun num(key: String) = (values[key] as? Number)?.toDouble()
        fun number(value: Double?, pattern: String) = value?.let { String.format(Locale.US, pattern, it) } ?: "—"
        val exposure = num("exposureNs")?.div(1e6)
        return listOf("FPS ${fps ?: number(num("resultFps"), "%.1f")}",
            "EXP ${if (exposure != null && exposure >= 1000) number(exposure / 1000, "%.2fs") else number(exposure, "%.2fms")}",
            aeState(num("ae")?.toInt()), afState(num("af")?.toInt()), awbState(num("awb")?.toInt()))
    }

    fun reservedLabels(dual: Boolean): List<List<String>> = listOf(
        listOf(if (dual) "FPS 999.9 / 999.9" else "FPS 999.9"), listOf("EXP 999.99ms"),
        (0..5).map(::aeState), (0..6).map(::afState), (0..3).map(::awbState))
    private fun aeState(value: Int?) = "AE " + when (value) {
        null -> "—"; 0 -> "Idle"; 1 -> "Search"; 2 -> "OK"; 3 -> "Locked"; 4 -> "Flash"; 5 -> "Meter"; else -> "#$value"
    }
    private fun afState(value: Int?) = "AF " + when (value) {
        null -> "—"; 0 -> "Idle"; 1, 3 -> "Scan"; 2, 4 -> "Focus"; 5, 6 -> "No focus"; else -> "#$value"
    }
    private fun awbState(value: Int?) = "AWB " + when (value) {
        null -> "—"; 0 -> "Idle"; 1 -> "Search"; 2 -> "OK"; 3 -> "Locked"; else -> "#$value"
    }
}
