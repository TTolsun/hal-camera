package dev.halcamera.ui

/** Holds a successful engine status until its first displayed frame, without masking errors. */
internal class PreviewStartup {
    private var awaitingFrame = true
    private var pendingStatus: String? = null

    fun reset() {
        awaitingFrame = true
        pendingStatus = null
    }

    /** True means the caller must defer this status instead of enabling capture. */
    fun defer(text: String, ok: Boolean): Boolean {
        if (!ok) pendingStatus = null
        if (!ok || !awaitingFrame) return false
        pendingStatus = text
        return true
    }

    fun frameArrived(): String? {
        if (!awaitingFrame) return null
        awaitingFrame = false
        return pendingStatus.also { pendingStatus = null }
    }
}
