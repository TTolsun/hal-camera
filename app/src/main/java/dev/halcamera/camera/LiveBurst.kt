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
    /** Makes a bracket's fourth image from its three shots; null leaves a bracket at three files. */
    private val fusion: BracketFusion? = null,
) {
    /** The burst in progress; letting go of the shutter stops it, and anything that closes the camera cancels it. */
    var run: BurstRun<PhotoResult>? = null
        private set
    var bracketing = false
        private set
    var fusing = false
        private set
    private var disposed = false

    /** All scene controls stay fixed until the last source has been saved. */
    val controlsLocked get() = run != null

    /** The shutter is being held: start shooting, unless the screen is no longer ready. */
    fun hold(session: String) {
        if (disposed || run != null || camera() == null || !ready()) return
        bracketing = false
        val burst = BurstRun<PhotoResult>(SimpleDateFormat("HHmmss_SSS", Locale.US).format(Date()), BurstRun.MAX_COUNT, 0L,
            clock, schedule,
            { ready() && camera()?.mediaBusy == false },
            { id, done -> camera()?.capturePhoto(id, done) ?: done(Result.failure(IllegalStateException("Camera closed"))) },
            { if (!disposed) changed() },
            { summary ->
                run = null
                event(session, "burst_done", fields(summary))
                if (!disposed) { report(describe(summary)); changed() }
            })
        run = burst
        event(session, "burst_start", mapOf("burstId" to burst.id, "maxCount" to burst.count))
        changed()
        burst.start()
    }

    /**
     * Exposure bracketing (#178): three stills at [BracketPlan] EVs around [base], then [base] again. Before each
     * shot the EV goes to the camera and the shot waits [SETTLE_MS] for AE to follow, because a camera that does not
     * apply controls per frame would otherwise take the shot at the previous exposure. The wait is not a check: the
     * exposure each shot actually got is in its capture metadata, next to the EV its request id asked for.
     */
    fun bracket(session: String, base: LiveControls, support: LiveControlSupport) {
        val range = support.evRange
        if (disposed || fusing || run != null || camera() == null || !ready() || range == null ||
            base.manual.exposure != null) return
        val tuning = camera() as? LiveTuning ?: return
        bracketing = true
        val evs = BracketPlan.evIndices(base.evIndex, range, support.evStep)
        val id = SimpleDateFormat("HHmmss_SSS", Locale.US).format(Date())
        val burst = BurstRun<PhotoResult>(id, evs.size, 0L, clock, schedule,
            { ready() && camera()?.mediaBusy == false },
            { requestId, done -> camera()?.capturePhoto(requestId, done) ?: done(Result.failure(IllegalStateException("Camera closed"))) },
            { if (!disposed) changed() },
            { summary ->
                run = null
                bracketing = false
                if (camera() === tuning) tuning.setControls(base)
                val labels = evs.map { support.evLabel(it) }
                event(session, "bracket_done", fields(summary) + ("evRequested" to labels))
                if (disposed) return@BurstRun
                val sources = summary.shots.mapNotNull { it.result.getOrNull()?.let(BracketFusion::pick) }
                val skipped = when {
                    fusion == null -> ""
                    summary.saved < evs.size -> " · HDR skipped"
                    sources.size < evs.size -> " · HDR skipped: no JPEG output"
                    else -> null
                }
                if (skipped != null || fusion == null) {
                    report(describeBracket(summary) + (skipped ?: ""))
                    changed()
                } else {
                    fusing = true
                    changed()
                    fusion.fuse(id, sources) { fused ->
                        fusing = false
                        event(session, "bracket_fused", mapOf("burstId" to id, "file" to fused.getOrNull()?.name,
                            "error" to fused.exceptionOrNull()?.message))
                        if (!disposed) {
                            report(describeBracket(summary) + (fused.exceptionOrNull()?.let { " · HDR failed: ${it.message}" } ?: " · HDR saved"))
                            changed()
                        }
                    }
                }
            },
            name = { "bracket-$id-${it + 1}-${BracketPlan.tag(evs[it], support.evStep)}" },
            prepare = { index, go -> tuning.setControls(base.copy(evIndex = evs[index])); schedule(SETTLE_MS, go) })
        run = burst
        event(session, "bracket_start", mapOf("burstId" to id, "evRequested" to evs.map { support.evLabel(it) }, "settleMs" to SETTLE_MS))
        changed()
        burst.start()
    }

    /** The shutter was let go: no further shot. The one in flight is still saved. */
    fun release() { if (!bracketing) run?.cancel(RELEASED) }

    /** Tapping the stop-shaped shutter cancels either sequence, keeping a shot already in flight. */
    fun stop() { run?.cancel("Stopped"); if (!disposed) changed() }

    /** Anything that closes the camera ends the burst before its next shot. */
    fun close() { run?.cancel("Camera closed") }

    /** Finish already accepted file work, but never deliver UI updates to a destroyed screen. */
    fun dispose() { disposed = true; close(); fusion?.close() }

    /** The shutter's description while a burst runs, or null. */
    val label: String? get() = run?.let {
        if (it.stopping) "Stopping…" else if (bracketing) "AEB ${it.saved}/${it.count}" else "Burst · ${it.saved}"
    } ?: if (fusing) "HDR…" else null

    private fun fields(summary: BurstSummary<PhotoResult>) = mapOf("burstId" to summary.id,
        "maxCount" to summary.requested, "saved" to summary.saved, "stopReason" to summary.stopReason,
        "startGapsMs" to summary.startGapsMs,
        "shots" to summary.shots.map { shot -> mapOf("requestId" to shot.requestId,
            "name" to shot.result.getOrNull()?.name, "error" to shot.result.exceptionOrNull()?.message) })

    companion object {
        const val RELEASED = "Released"
        /** How long a bracket shot waits after its EV is sent; about 15 preview frames at 30 fps. */
        const val SETTLE_MS = 500L

        /** Requested EVs stay in capture metadata; the shutter line reports the outcome. */
        fun describeBracket(summary: BurstSummary<*>): String = buildString {
            append("AEB · ${summary.saved}/${summary.requested} saved")
            summary.failedShots.forEach { append(" · #${it.index + 1} failed: ${it.result.exceptionOrNull()?.message ?: "unknown"}") }
            summary.stopReason?.let { append(" ($it)") }
        }

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
