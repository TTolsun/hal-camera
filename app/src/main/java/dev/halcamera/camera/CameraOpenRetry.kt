package dev.halcamera.camera

/**
 * Camera-thread decision for a LIVE open that failed before its first frame (#224).
 *
 * Right after another engine released a camera, the camera service can still refuse the next open for a moment:
 * the device reports disconnected or in use. Camera2Engine used to close the device and stop there, so a CLI
 * preview waited for its 30 s timeout while the next identical request succeeded. A failure on a fresh open is
 * retried after a short backoff; once the budget is spent the caller reports the failure instead of waiting.
 */
internal class CameraOpenRetry(private val delaysMs: List<Long> = DEFAULT_DELAYS_MS) {
    var attempts = 0
        private set

    /** Milliseconds to wait before opening again, or null when [cause] is final or the retries are spent. */
    fun next(cause: Cause): Long? {
        if (!cause.retriable || attempts >= delaysMs.size) return null
        return delaysMs[attempts++]
    }

    /** Why the open failed, mapped from [android.hardware.camera2.CameraDevice.StateCallback] and CameraAccessException. */
    enum class Cause(val retriable: Boolean) {
        DISCONNECTED(true),
        IN_USE(true),
        MAX_IN_USE(true),
        DEVICE_ERROR(true),
        SERVICE_ERROR(true),
        DISABLED(false),
        OTHER(false);

        companion object {
            /** StateCallback.ERROR_* codes. */
            fun fromStateError(code: Int) = when (code) {
                1 -> IN_USE
                2 -> MAX_IN_USE
                3 -> DISABLED
                4 -> DEVICE_ERROR
                5 -> SERVICE_ERROR
                else -> OTHER
            }

            /** CameraAccessException reasons. */
            fun fromAccessReason(reason: Int) = when (reason) {
                1 -> DISABLED
                2 -> DISCONNECTED
                3 -> SERVICE_ERROR
                4 -> IN_USE
                5 -> MAX_IN_USE
                else -> OTHER
            }
        }
    }

    companion object {
        /** About 5 s in total, well inside the CLI's 30 s request timeout. */
        val DEFAULT_DELAYS_MS = listOf(200L, 400L, 800L, 1600L, 2000L)
    }
}
