package dev.halcamera.camera

import java.io.Serializable
import kotlin.math.abs

data class PortraitConfig(val preview: LiveSize, val jpeg: LiveSize) : Serializable

/** Manufacturer declarations; a listed size pair still needs session validation. */
data class PortraitSupport(
    val advertised: Boolean?,
    val previews: List<LiveSize> = emptyList(),
    val jpegs: List<LiveSize> = emptyList(),
    val reason: String? = null,
    val errors: List<String> = emptyList(),
) {
    val available get() = reason == null && advertised == true && previews.isNotEmpty() && jpegs.isNotEmpty()
    val unavailableReason get() = reason ?: when {
        advertised == null -> "Manufacturer Bokeh capability could not be read."
        !advertised -> "This camera does not advertise the BOKEH extension."
        previews.isEmpty() -> "No readable Bokeh preview resolutions."
        jpegs.isEmpty() -> "No readable Bokeh JPEG resolutions."
        else -> null
    }

    /** A visible initial pairing, never a retry fallback. Prefer matching aspect and a ~1080p preview. */
    fun config(jpeg: LiveSize): PortraitConfig? {
        if (!available || jpeg !in jpegs) return null
        val ratio = jpeg.width.toDouble() / jpeg.height
        val preview = previews.minWithOrNull(compareBy<LiveSize> { abs(it.width.toDouble() / it.height - ratio) }
            .thenBy { abs(it.width.toLong() * it.height - 1920L * 1080) }) ?: return null
        return PortraitConfig(preview, jpeg)
    }

    fun probeRows(): List<ProbeRow> = listOf(
        ProbeRow("Manufacturer BOKEH", when (advertised) { true -> "Supported"; false -> "Not supported"; null -> "Unknown" }),
        ProbeRow("Bokeh preview resolutions", previews.joinToString().ifEmpty { "None reported" }),
        ProbeRow("Bokeh JPEG resolutions", jpegs.joinToString().ifEmpty { "None reported" }),
        ProbeRow("LIVE Portrait", if (available) "Photo only; size combination must still be checked" else unavailableReason.orEmpty()),
    ) + errors.map { ProbeRow("Read error", it) }
}
