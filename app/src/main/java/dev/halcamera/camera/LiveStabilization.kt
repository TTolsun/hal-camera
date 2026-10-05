package dev.halcamera.camera

/** Exclusive choices: simultaneous OIS and video stabilization is not portable across cameras. */
enum class LiveStabilization(val label: String, val optical: Int?, val video: Int?) {
    AUTO("Auto (camera defaults)", null, null),
    OFF("Off", 0, 0),
    OIS("OIS", 1, 0),
    VIDEO("EIS (Video)", 0, 1),
    PREVIEW("EIS (Preview + Video)", 0, 2);

    companion object {
        fun supportedCameraX(hardware: List<LiveStabilization>, video: Boolean, preview: Boolean): List<LiveStabilization> =
            hardware.filter { it != VIDEO || video }.filter { it != PREVIEW || preview }

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

/** Null leaves a CameraX builder option unspecified; explicit false can veto the other use case. */
data class CameraXStabilizationPlan(val preview: Boolean?, val video: Boolean?, val optical: Int?) {
    companion object {
        fun forMode(mode: LiveStabilization): CameraXStabilizationPlan = when (mode) {
            LiveStabilization.AUTO -> CameraXStabilizationPlan(null, null, null)
            LiveStabilization.OFF -> CameraXStabilizationPlan(false, false, 0)
            LiveStabilization.OIS -> CameraXStabilizationPlan(false, false, 1)
            LiveStabilization.VIDEO -> CameraXStabilizationPlan(null, true, 0)
            LiveStabilization.PREVIEW -> CameraXStabilizationPlan(true, null, 0)
        }
    }
}

/** VideoCapture is unbound outside recording in CameraX; there is no video request to compare yet. */
fun LiveStabilization.eisComparisonMode(engine: String, recording: Boolean): LiveStabilization =
    if (engine == "CameraX" && this == LiveStabilization.VIDEO && !recording) LiveStabilization.AUTO else this

/** Capture-result status, not a measurement of stabilization effectiveness. */
data class LiveEisStatus(val video: Int? = null, val warning: String? = null) {
    val label: String get() = when (video) {
        0 -> "EIS inactive"
        1, 2 -> "EIS active"
        else -> "EIS status unknown"
    }
}

/** Reject old phases/sessions and wait for sustained result mismatch before warning. */
class LiveEisTracker {
    private var context: Triple<String, Boolean, LiveStabilization>? = null
    private var sinceNs = 0L
    private var mismatchVideo: Int? = null
    private var mismatchSinceNs = 0L

    fun reset() {
        context = null
        mismatchVideo = null
    }

    fun update(session: String, recording: Boolean, requested: LiveStabilization,
               resultAtNs: Long?, video: Int?, nowNs: Long, active: Boolean): LiveEisStatus {
        if (!active) { reset(); return LiveEisStatus() }
        val next = Triple(session, recording, requested)
        if (context != next) {
            context = next
            sinceNs = nowNs
            mismatchVideo = null
        }
        if (resultAtNs == null || resultAtNs < sinceNs || resultAtNs > nowNs ||
            nowNs - resultAtNs >= 1_500_000_000L || video !in 0..2) {
            mismatchVideo = null
            return LiveEisStatus()
        }
        if (requested.video == null || requested.video == video) {
            mismatchVideo = null
            return LiveEisStatus(video)
        }
        if (mismatchVideo != video) {
            mismatchVideo = video
            mismatchSinceNs = resultAtNs
        }
        val actual = when (video) {
            1 -> LiveStabilization.VIDEO.label
            2 -> LiveStabilization.PREVIEW.label
            else -> "EIS Off"
        }
        val warning = if (resultAtNs - mismatchSinceNs >= 1_000_000_000L)
            "Mode mismatch\nRequested: ${requested.label}\nReported: $actual" else null
        return LiveEisStatus(video, warning)
    }
}
