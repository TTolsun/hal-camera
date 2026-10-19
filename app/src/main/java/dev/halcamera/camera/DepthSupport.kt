package dev.halcamera.camera

/** Static HAL declarations, not a guarantee that a particular output combination will configure. */
data class DepthSupport(
    val advertised: Boolean?,
    val sizes: List<LiveSize>,
    val exclusive: Boolean?,
    val color: Boolean?,
    val errors: List<String> = emptyList(),
) {
    val available get() = advertised == true && sizes.isNotEmpty()
    val unavailableReason get() = when {
        advertised == null -> "Depth capability could not be read."
        !advertised -> "This camera does not advertise DEPTH_OUTPUT."
        sizes.isEmpty() -> "No readable DEPTH16 resolutions."
        else -> null
    }
    val colorSummary get() = when {
        advertised == false -> "Not applicable: no DEPTH_OUTPUT"
        advertised == null -> "Unknown: capability unavailable"
        color == false -> "Not applicable: no color output formats reported"
        color == null -> "Unknown: color output formats unavailable"
        exclusive == true -> "No: interleave color and depth requests"
        exclusive == false -> "Allowed by HAL; output combination must still be checked"
        else -> "Unknown: DEPTH_DEPTH_IS_EXCLUSIVE not reported"
    }
    fun probeRows(independentlyOpenable: Boolean = true): List<ProbeRow> = listOf(
        ProbeRow("DEPTH_OUTPUT", when (advertised) { true -> "Supported"; false -> "Not supported"; null -> "Unknown" }),
        ProbeRow("DEPTH16 resolutions", sizes.joinToString().ifEmpty { "None reported" }),
        ProbeRow("Color + depth (same request)", colorSummary),
        ProbeRow("LIVE depth map", if (!independentlyOpenable) "Physical camera: select a publicly listed camera ID in LIVE"
            else if (available) "Available in Camera2 · Depth-only view" else unavailableReason.orEmpty()),
    ) + errors.map { ProbeRow("Read error", it) }
}
