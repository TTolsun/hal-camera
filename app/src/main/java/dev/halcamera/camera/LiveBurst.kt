package dev.halcamera.camera

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The LIVE screen's burst (#178): holding the shutter in photo mode takes stills one after another as fast as the
 * camera and storage allow, and letting go stops it. There is nothing to choose; [BurstRun.MAX_COUNT] only bounds
 * a hold that never ends. Each shot goes through the engine's ordinary photo path, so it is saved like a single
 * photo and carries "burst-<id>-NN" as its request id in the capture metadata. The screen supplies the camera,
 * the clock, the scheduler and telemetry.
 */
class LiveBurst(
    private val clock: () -> Long,
    private val schedule: (delayMs: Long, block: () -> Unit) -> Unit,
    /** The camera that takes the shots, or null once it is closed. */
    private val camera: () -> MediaCapture?,
    /** True when the screen can take a still right now: camera open, photo mode, no recording or CLI command. */
    private val ready: () -> Boolean,
    private val event: (session: String, kind: String, values: Map<String, Any?>) -> Unit,
    private val changed: () -> Unit,
    private val report: (String) -> Unit,
) {
    /** The burst in progress; letting go of the shutter stops it, and anything that closes the camera cancels it. */
    var run: BurstRun<PhotoResult>? = null
        private set

    /** The shutter is being held: start shooting, unless the screen is no longer ready. */
    fun hold(session: String) {
        if (run != null || camera() == null || !ready()) return
        val burst = BurstRun<PhotoResult>(SimpleDateFormat("HHmmss_SSS", Locale.US).format(Date()), BurstRun.MAX_COUNT, 0L,
            clock, schedule,
            { ready() && camera()?.mediaBusy == false },
            { id, done -> camera()?.capturePhoto(id, done) ?: done(Result.failure(IllegalStateException("Camera closed"))) },
            { changed() },
            { summary ->
                run = null
                event(session, "burst_done", fields(summary))
                report(describe(summary))
                changed()
            })
        run = burst
        event(session, "burst_start", mapOf("burstId" to burst.id, "maxCount" to burst.count))
        changed()
        burst.start()
    }

    /** The shutter was let go: no further shot. The one in flight is still saved. */
    fun release() { run?.cancel(RELEASED) }

    /** Anything that closes the camera ends the burst before its next shot. */
    fun close() { run?.cancel("Camera closed") }

    /** The shutter's description while a burst runs, or null. */
    val label: String? get() = run?.let { "Burst: ${it.saved} saved. Release to stop" }

    private fun fields(summary: BurstSummary<PhotoResult>) = mapOf("burstId" to summary.id,
        "maxCount" to summary.requested, "saved" to summary.saved, "stopReason" to summary.stopReason,
        "startGapsMs" to summary.startGapsMs,
        "shots" to summary.shots.map { shot -> mapOf("requestId" to shot.requestId,
            "name" to shot.result.getOrNull()?.name, "error" to shot.result.exceptionOrNull()?.message) })

    companion object {
        const val RELEASED = "Released"

        /** "Burst · 7 saved · #4 failed: Capture timed out". A hold has no target count, so none is shown. */
        fun describe(summary: BurstSummary<*>): String = buildString {
            append("Burst · ${summary.saved} saved")
            summary.failedShots.forEach { append(" · #${it.index + 1} failed: ${it.result.exceptionOrNull()?.message ?: "unknown"}") }
            when (summary.stopReason) {
                RELEASED -> Unit
                null -> append(" (limit ${summary.requested})")
                else -> append(" (${summary.stopReason})")
            }
        }
    }
}
