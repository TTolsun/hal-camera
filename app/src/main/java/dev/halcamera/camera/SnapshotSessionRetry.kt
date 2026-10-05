package dev.halcamera.camera

/** Camera-thread ownership of the one retry after a recording session with JPEG was rejected. */
internal class SnapshotSessionRetry {
    var replaced = false
        private set

    /** Mark the old session replaced before closing its outputs or constructing its replacement. */
    fun onRejected(hasSnapshot: Boolean, active: Boolean, stopping: Boolean, retryWithoutSnapshot: () -> Unit): Boolean {
        if (replaced) return true
        if (!hasSnapshot || !active || stopping) return false
        replaced = true
        retryWithoutSnapshot()
        return true
    }
}
