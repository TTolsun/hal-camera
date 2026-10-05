package dev.halcamera.camera

/** Exclusive choices: simultaneous OIS and video stabilization is not portable across cameras. */
enum class LiveStabilization(val label: String, val optical: Int?, val video: Int?) {
    AUTO("Auto (camera defaults)", null, null),
    OFF("Off", 0, 0),
    OIS("OIS", 1, 0),
    VIDEO("EIS (Video)", 0, 1),
    PREVIEW("EIS (Preview + Video)", 0, 2);

    companion object {
        fun supported(optical: List<Int>, video: List<Int>, api: Int): List<LiveStabilization> =
            entries.filter { mode -> mode == AUTO ||
                ((mode.optical == 0 || mode.optical in optical) &&
                    (mode.video == 0 || mode.video in video) && (mode != PREVIEW || api >= 33)) }

        fun observed(optical: Int?, video: Int?): String {
            fun name(value: Int?, labels: List<String>) = value?.let { labels.getOrNull(it) ?: "Unknown ($it)" } ?: "Unavailable"
            return "OIS ${name(optical, listOf("Off", "On"))} · EIS ${name(video, listOf("Off", "On", "Preview"))}"
        }
    }
}
