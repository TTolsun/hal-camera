package dev.halcamera.camera

/**
 * Camera-thread wait for the camera the previous LIVE engine released (#230).
 *
 * An engine's close(done) can come before the camera service lets go of its camera: CameraX reports CLOSED about
 * 1 s early, and a Camera2 open in that window is refused with "existing client(s) with higher priority". The
 * retry of #224 then guesses the release with its backoff. Waiting for CameraManager.AvailabilityCallback to
 * report the released camera available opens right after the release instead. The wait settles once: on that
 * report or on the time limit, whichever comes first; past the limit the open goes ahead as before.
 */
internal class CameraReleaseWait(val cameraId: String) {
    var outcome: Outcome? = null
        private set

    /** True when this report ends the wait. */
    fun available(id: String): Boolean = id == cameraId && settle(Outcome.AVAILABLE)

    /** True when the time limit ends the wait. */
    fun timedOut(): Boolean = settle(Outcome.TIMEOUT)

    /** True when closing the engine ends the wait. */
    fun cancel(): Boolean = settle(Outcome.CANCELLED)

    private fun settle(result: Outcome): Boolean {
        if (outcome != null) return false
        outcome = result
        return true
    }

    enum class Outcome(val label: String) { AVAILABLE("available"), TIMEOUT("timeout"), CANCELLED("cancelled") }

    companion object {
        /** The release seen on device took about 1 s; the #224 backoff spent up to 1.9 s guessing it. */
        const val LIMIT_MS = 2000L
    }
}
