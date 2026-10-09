package dev.halcamera.camera

import java.util.Locale

/** LIVE-only manual requests. Null exposure/focus means automatic; ISO and time are always paired. */
data class ManualExposure(val iso: Int, val timeNs: Long)

enum class WhiteBalance(val key: Int, val label: String) {
    AUTO(1, "Auto"), INCANDESCENT(2, "Incandescent"), FLUORESCENT(3, "Fluorescent"),
    WARM_FLUORESCENT(4, "Warm Fluorescent"), DAYLIGHT(5, "Daylight"), CLOUDY(6, "Cloudy"),
    TWILIGHT(7, "Twilight"), SHADE(8, "Shade"), CUSTOM(0, "Custom")
}

data class ManualColor(
    val gains: List<Float> = listOf(1f, 1f, 1f, 1f),
    val transform: List<Float> = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
) {
    // Camera2 gains are >= 1; the transform uses signed rationals. Keep input finite and representable.
    fun valid() = gains.size == 4 && gains.all { it.isFinite() && it in 1f..100f } &&
        transform.size == 9 && transform.all { it.isFinite() && it in -100f..100f }
}

data class ManualControls(
    val exposure: ManualExposure? = null,
    val focusDiopters: Float? = null,
    val wb: WhiteBalance = WhiteBalance.AUTO,
    val color: ManualColor = ManualColor(),
) {
    val active get() = exposure != null || focusDiopters != null || wb != WhiteBalance.AUTO
    fun normalized(support: ManualSupport): ManualControls = copy(
        exposure = exposure?.let { support.exposure(it.iso, it.timeNs) },
        focusDiopters = focusDiopters?.takeIf { it.isFinite() && support.maxFocus > 0f }?.coerceIn(0f, support.maxFocus),
        wb = wb.takeIf { it in support.whiteBalances &&
            (it != WhiteBalance.CUSTOM || (exposure != null && support.canExpose && color.valid())) }
            ?: WhiteBalance.AUTO,
    )

    fun summary(): String = listOfNotNull(
        exposure?.let { "ISO ${it.iso} · ${shutter(it.timeNs)}" },
        focusDiopters?.let { "MF ${"%.2f".format(Locale.US, it)} D" },
        wb.takeIf { it != WhiteBalance.AUTO }?.let { "WB ${it.label}" },
    ).joinToString(" · ")

    companion object {
        fun shutter(ns: Long): String = if (ns in 1..999_999_999) "1/${(1e9 / ns).toInt().coerceAtLeast(1)}"
            else "${"%.2f".format(Locale.US, ns / 1e9)} s"
    }
}

data class ManualSupport(
    val iso: IntRange? = null,
    val exposureNs: LongRange? = null,
    val maxFocus: Float = 0f,
    val whiteBalances: List<WhiteBalance> = listOf(WhiteBalance.AUTO),
    /** Fixed manual frame duration policy, derived from LIVE's FPS request (30 fps for Auto FPS). */
    val frameNs: Long = 1_000_000_000L / 30,
    val camera2: Boolean = true,
) {
    val maxExposureNs get() = exposureNs?.last?.coerceAtMost(frameNs)
    val canExpose get() = camera2 && iso != null && exposureNs != null && exposureNs.first <= (maxExposureNs ?: 0)
    fun exposure(iso: Int, ns: Long): ManualExposure? = if (canExpose)
        ManualExposure(iso.coerceIn(this.iso!!), ns.coerceIn(exposureNs!!.first, maxExposureNs!!)) else null

    fun rejection(value: ManualControls): String? = when {
        !camera2 && value.active -> "Manual capture requires Camera2."
        value.exposure != null && !canExpose -> "This camera does not support manual exposure."
        value.exposure != null && value.exposure.iso !in iso!! -> "Supported ISO range: ${iso.first}–${iso.last}."
        value.exposure != null && value.exposure.timeNs !in exposureNs!!.first..maxExposureNs!! ->
            "Exposure range at the current FPS: ${exposureNs.first / 1e6}–${maxExposureNs!! / 1e6} ms."
        value.focusDiopters != null && (maxFocus <= 0f || !value.focusDiopters.isFinite() || value.focusDiopters !in 0f..maxFocus) ->
            "Supported focus range: 0–$maxFocus D."
        value.wb !in whiteBalances -> "This camera does not support the selected white balance mode."
        value.wb == WhiteBalance.CUSTOM && value.exposure == null -> "Custom white balance requires manual exposure."
        value.wb == WhiteBalance.CUSTOM && !value.color.valid() -> "Gains require four values from 1–100; the matrix requires nine values from −100–100."
        else -> null
    }
}
