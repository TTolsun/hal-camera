package dev.halcamera.camera

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The LIVE screen's burst (#178): the shutter's long-press menu choices and the one [BurstRun] in progress.
 * Each shot goes through the engine's ordinary photo path, so it is saved like a single photo and carries
 * "burst-<id>-NN" as its request id in the capture metadata. The summary says how many were saved, which failed
 * and how many were never taken. The screen supplies the camera, the clock, the scheduler and telemetry.
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
    /** The burst in progress; the shutter stops it, and anything that closes the camera cancels it. */
    var run: BurstRun<PhotoResult>? = null
        private set

    /** Starts the burst picked from [COUNT_LABELS] and [INTERVAL_LABELS], unless the screen is no longer ready. */
    fun start(session: String, countChoice: Int, intervalChoice: Int) {
        if (run != null || camera() == null || !ready()) return
        val count = COUNTS[countChoice]
        val intervalMs = INTERVALS_MS[intervalChoice]
        val burst = BurstRun<PhotoResult>(SimpleDateFormat("HHmmss_SSS", Locale.US).format(Date()), count, intervalMs,
            clock, schedule,
            { ready() && camera()?.mediaBusy == false },
            { id, done -> camera()?.capturePhoto(id, done) ?: done(Result.failure(IllegalStateException("Camera closed"))) },
            { changed() },
            { summary ->
                run = null
                event(session, "burst_done", fields(summary))
                report(summary.describe())
                changed()
            })
        run = burst
        event(session, "burst_start", mapOf("burstId" to burst.id, "count" to count, "intervalMs" to intervalMs))
        changed()
        burst.start()
    }

    /** Anything that closes the camera ends the burst before its next shot. */
    fun close() { run?.cancel("Camera closed") }

    /** The shutter's description while a burst runs, or null. */
    val label: String? get() = run?.let { "Stop burst: ${it.saved} of ${it.count} saved" }

    /** The shutter during a burst stops it; false when no burst is running. */
    fun stop(): Boolean = run?.let { it.cancel("Stopped"); true } ?: false

    private fun fields(summary: BurstSummary<PhotoResult>) = mapOf("burstId" to summary.id,
        "requested" to summary.requested, "intervalMs" to summary.intervalMs, "saved" to summary.saved,
        "notTaken" to summary.notTaken, "stopReason" to summary.stopReason, "startGapsMs" to summary.startGapsMs,
        "shots" to summary.shots.map { shot -> mapOf("requestId" to shot.requestId,
            "name" to shot.result.getOrNull()?.name, "error" to shot.result.exceptionOrNull()?.message) })

    companion object {
        val COUNTS = listOf(3, 5, 10, BurstRun.MAX_COUNT)
        val COUNT_LABELS = COUNTS.map { "Burst · $it shots" }
        val INTERVALS_MS = listOf(0L, 500L, 1000L, 2000L)
        val INTERVAL_LABELS = listOf("As fast as possible", "Every 0.5 s", "Every 1 s", "Every 2 s")
    }
}
