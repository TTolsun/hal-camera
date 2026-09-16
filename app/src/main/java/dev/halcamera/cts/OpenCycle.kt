package dev.halcamera.cts

import java.util.Locale

/**
 * One open → preview session → first frame → close pass as the cases measure it, in ms. A null phase was
 * never reached; [error] carries the exception that stopped the pass, in CameraTestUtils words where the
 * check has a counterpart there. Pure Kotlin so every case judges the same pass the same way.
 */
data class OpenCycle(
    val openMs: Double? = null,
    val configureMs: Double? = null,
    val firstFrameMs: Double? = null,
    val closeMs: Double? = null,
    val closedInTime: Boolean = true,
    val error: String? = null
) {
    /** The timings a row should still show, or null when nothing was measured. */
    fun summary(openLabel: String = "open"): String? {
        val parts = ArrayList<String>()
        openMs?.let { parts += "$openLabel ${ms(it)}" }
        configureMs?.let { parts += "configure ${ms(it)}" }
        firstFrameMs?.let { parts += "first frame ${ms(it)}" }
        closeMs?.let { parts += "close ${ms(it)}" }
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    /** Why the pass is not a PASS; empty when it reached a frame and closed in time. */
    fun failures(): List<String> {
        val out = ArrayList<String>()
        error?.let { out += it }
        if (error == null && firstFrameMs == null) out += "No capture result was completed"
        if (!closedInTime) out += "Timeout waiting for the camera to close"
        return out
    }

    companion object {
        fun ms(v: Double): String = String.format(Locale.US, "%.1f ms", v)
    }
}
