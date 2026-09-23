package dev.halcamera.benchmark.domain

/**
 * Preflight of the benchmark profile (docs/PLAN-BenchMarker-v0.3.md 3.6). The camera is never opened: an extra
 * open would add an unmeasured launch and blur what "warm reopen" means.
 *
 * The result is SUPPORTED or UNSUPPORTED, never a degraded variant. The app does not lower the streams and keep
 * the same profile id (METRICS.md 0.4, "no automatic fallback"); a device that cannot do 1080p needs its own
 * profile id, which is out of scope for v0.3.
 */
object ProfileCompatibility {
    const val METHOD_DEVICE_SETUP = "device_setup"
    const val METHOD_STATIC_TABLE = "static_table"

    const val REASON_PREVIEW_SIZE = "PREVIEW_SIZE"
    const val REASON_YUV_SIZE = "YUV_SIZE"
    const val REASON_JPEG_SIZE = "JPEG_SIZE"
    const val REASON_FPS_RANGE = "FPS_RANGE"
    const val REASON_RECORD_SIZE = "RECORD_SIZE"
    const val REASON_STREAM_COMBINATION = "STREAM_COMBINATION"

    /** 30 fps in nanoseconds. Only recorded in compatibility.frame_budget_ok; never part of the verdict. */
    const val FRAME_BUDGET_NS = 33_333_333L

    /**
     * What the static preflight reads from CameraCharacteristics, already normalized to strings so the decision
     * itself is pure Kotlin and testable on the JVM. Null means "could not be read".
     */
    data class Inputs(
        val previewSizes: List<String>,
        val yuvSizes: List<String>,
        val jpegSizes: List<String>,
        /** MediaRecorder output sizes. Only read when the profile records; empty for a non-recording profile. */
        val recordSizes: List<String>,
        val fpsRanges: List<String>,
        val hardwareLevel: Int?,
        /** Display size in pixels; the PREVIEW stream grade is the smaller of the display and 1080p. */
        val displayWidth: Int?,
        val displayHeight: Int?,
        val previewMinFrameDurationNs: Long?,
        val yuvMinFrameDurationNs: Long?
    )

    /** "1920x1080" from any of "1920x1080", "1920 x 1080" or Size.toString(). */
    fun normalizeSize(text: String): String = text.replace(" ", "").lowercase()

    /** "[30,30]" from any of "[30,30]", "[30, 30]" or Range<Int>.toString(). */
    fun normalizeRange(text: String): String = text.replace(" ", "")

    private fun parseSize(text: String): Pair<Int, Int>? {
        val parts = normalizeSize(text).split("x")
        if (parts.size != 2) return null
        val w = parts[0].toIntOrNull() ?: return null
        val h = parts[1].toIntOrNull() ?: return null
        return w to h
    }

    /**
     * Static verdict from the characteristics alone. The stream combination is judged from the guarantee table:
     * even LEGACY guarantees PRIV(PREVIEW) + YUV(PREVIEW) + JPEG(MAXIMUM), but the PREVIEW grade is the smaller
     * of the display resolution and 1080p, so a display below 1080p makes the profile unsupported.
     *
     * A recording profile also needs its record size in the MediaRecorder output list. Whether the recording
     * stream combination itself configures is not answered here: the record session is built after the camera
     * is already open, so a rejected combination is a run-time failure of the RECORD stage rather than a
     * preflight verdict (docs/PLAN-Recording-v0.1.md 4 and 9).
     */
    fun evaluateStatic(profile: BenchmarkProfile, x: Inputs): Compatibility {
        val reasons = ArrayList<String>()
        val preview = normalizeSize(profile.previewSize)
        val yuv = normalizeSize(profile.yuvSize)
        val jpeg = normalizeSize(profile.stillSize)
        if (preview !in x.previewSizes.map(::normalizeSize)) reasons += REASON_PREVIEW_SIZE
        if (yuv !in x.yuvSizes.map(::normalizeSize)) reasons += REASON_YUV_SIZE
        if (jpeg !in x.jpegSizes.map(::normalizeSize)) reasons += REASON_JPEG_SIZE
        if (normalizeRange(profile.fpsRange) !in x.fpsRanges.map(::normalizeRange)) reasons += REASON_FPS_RANGE
        profile.recordSize?.let { if (normalizeSize(it) !in x.recordSizes.map(::normalizeSize)) reasons += REASON_RECORD_SIZE }
        if (!previewGradeCovers(preview, x)) reasons += REASON_STREAM_COMBINATION
        return Compatibility(METHOD_STATIC_TABLE, reasons.isEmpty(), reasons, frameBudgetOk(x))
    }

    /**
     * The PREVIEW size grade is min(display, 1080p) per stream-combination table. A requested preview stream
     * larger than that grade is not covered by any guaranteed combination.
     */
    private fun previewGradeCovers(previewSize: String, x: Inputs): Boolean {
        val (w, h) = parseSize(previewSize) ?: return false
        val dw = x.displayWidth ?: return true      // unknown display: leave the verdict to the size lists
        val dh = x.displayHeight ?: return true
        val gradeLong = minOf(maxOf(dw, dh), 1920)
        val gradeShort = minOf(minOf(dw, dh), 1080)
        return maxOf(w, h) <= gradeLong && minOf(w, h) <= gradeShort
    }

    /** Recorded, never judged: whether 30 fps is even reachable is answered at runtime by CADENCE_NOT_FIXED (3.6). */
    fun frameBudgetOk(x: Inputs): Boolean? {
        val p = x.previewMinFrameDurationNs
        val y = x.yuvMinFrameDurationNs
        if (p == null || y == null) return null
        return p <= FRAME_BUDGET_NS && y <= FRAME_BUDGET_NS
    }
}
