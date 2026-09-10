package dev.halcamera.benchmark

import dev.halcamera.check.CameraEndpoint

/**
 * Benchmark state machine (docs/PLAN-BenchMarker-v0.3.md 3.2). Pure Kotlin: the camera is driven through [Driver],
 * time through [Scheduler], so the whole sequence is unit-testable with fakes. One runner instance runs one run
 * against one camera.
 *
 * The sequence is a warm reopen (METRICS.md 0.2 `warm_sequence`), not a process cold launch:
 *
 *     LAUNCH_CYCLE x launchIterations   OPEN -> CONFIGURE -> FIRST_FRAME -> CYCLE_CLOSE
 *     OPEN (one more, kept open)        WARMUP -> OBSERVE -> STILL x stillCount -> CLOSE
 *
 * The extra open is the observation session: its launch timings are not part of the launch metrics, because the
 * profile promises exactly [BenchmarkProfile.launchIterations] comparable cycles and this one is followed by a
 * warm-up instead of a close.
 *
 * Failures never abort the run on their own. A failed cycle is recorded with `failed = true` and the next cycle
 * starts, so a run with one bad open still carries eight good samples and gets INSUFFICIENT_SAMPLES from
 * [RunValidityEvaluator] rather than disappearing. Only [Config.maxConsecutiveFailures] cycles failing in a row,
 * a failure of the observation session, or an explicit [abort] ends the run early.
 */
class BenchmarkRunner(
    private val driver: Driver,
    private val scheduler: Scheduler,
    private val clock: () -> Long,
    private val profile: BenchmarkProfile,
    private val endpoint: CameraEndpoint,
    private val runId: String,
    private val config: Config = Config(),
    private val listener: Listener
) {
    data class Config(
        val openTimeoutMs: Long = 5000,
        val configureTimeoutMs: Long = 5000,
        val firstFrameTimeoutMs: Long = 5000,
        val stillTimeoutMs: Long = 5000,
        val closeTimeoutMs: Long = 5000,
        /** Consecutive failed launch cycles after which the run is aborted instead of retrying forever. */
        val maxConsecutiveFailures: Int = 3
    )

    /** Runner steps. The screen shows [Phase] instead; several steps map to one phase. */
    enum class Step { IDLE, OPEN, CONFIGURE, FIRST_FRAME, CYCLE_CLOSE, WARMUP, OBSERVE, STILL, CLOSE, DONE, ABORTED }

    /** The six phases of the progress screen (3.2). */
    enum class Phase { CAMERA_OPEN, FIRST_PREVIEW, PREVIEW_STABILITY, THREE_A, STILL_CAPTURE, CAMERA_CLOSE }

    /**
     * Camera-side events the runner reacts to. Timestamps are elapsedRealtimeNanos. Still callbacks are not
     * signals: they carry a request tag or a sensor timestamp and go through [stillSubmitted], [stillImage] and
     * [stillResult] so each callback lands on the request it belongs to.
     */
    enum class Signal { OPENED, CONFIGURED, FIRST_FRAME, CLOSED, ERROR }

    interface Driver {
        /** Open [endpoint] with the profile's streams and start repeating. Must report OPENED, CONFIGURED, FIRST_FRAME. */
        fun open(endpoint: CameraEndpoint, session: String)
        /** Submit one JPEG still. Must report STILL_RECEIVED or ERROR. */
        fun still(session: String)
        /** Close the camera. Must report CLOSED. */
        fun close(session: String)
    }

    interface Scheduler {
        /** Run [action] after [delayMs]; returns a token to cancel. */
        fun after(delayMs: Long, action: () -> Unit): Any
        fun cancel(token: Any)
    }

    interface Listener {
        /** [iteration] and [total] count launch cycles; both are 0 outside the launch phase. */
        fun onProgress(phase: Phase, step: Step, iteration: Int, total: Int)
        fun onFinished(result: Result)
    }

    /**
     * Everything the evaluator and the validity rules need. [observeFirstFrameNs] is the first frame of the
     * observation session: the 3A convergence window starts there (3.2), while H.1 - H.5 only use the frames
     * from [observeStartNs] on.
     */
    data class Result(
        val runId: String,
        val endpoint: CameraEndpoint,
        val cycles: List<LaunchCycle>,
        val stills: List<StillSample>,
        val observeSession: String?,
        val observeFirstFrameNs: Long?,
        val observeStartNs: Long?,
        val observeEndNs: Long?,
        /** Name of the step that failed, null when every step completed. */
        val hardFailure: String?,
        val aborted: String?,
        val sessions: List<String>
    ) {
        val observed: Boolean get() = observeStartNs != null && observeEndNs != null
        val validLaunchSamples: Int get() = cycles.count { !it.warmup && !it.failed }
        val validStillSamples: Int get() = stills.count { !it.warmup && it.imageNs != null }
    }

    var step = Step.IDLE
        private set

    private var cycleIndex = 0
    private var session = ""
    private var timer: Any? = null
    private val marks = LinkedHashMap<String, Long>()
    private val cycles = mutableListOf<LaunchCycle>()
    private val stills = mutableListOf<PendingStill>()
    /** JPEGs whose request is not known yet, by sensor timestamp; claimed when the matching result arrives. */
    private val unmatchedImages = LinkedHashMap<Long, Long>()
    private val sessions = mutableListOf<String>()
    private var cycleFailed = false
    private var consecutiveFailures = 0
    private var hardFailure: String? = null
    private var aborted: String? = null
    private var stillIndex = 0
    private var observeSession: String? = null
    private var observeFirstFrameNs: Long? = null
    private var observeStartNs: Long? = null
    private var observeEndNs: Long? = null

    val currentSession: String get() = session

    /** True while the runner holds the observation session open (after the launch cycles). */
    private val onObservationSession: Boolean get() = cycleIndex >= profile.launchIterations

    fun start() {
        check(step == Step.IDLE) { "runner already used" }
        cycleIndex = 0
        enter(Step.OPEN)
    }

    fun abort(reason: String) {
        if (step == Step.DONE || step == Step.ABORTED) return
        aborted = reason
        when (step) {
            Step.IDLE -> { cancelTimer(); finish() }
            // Already closing: its timer must survive, otherwise a camera that never reports CLOSED hangs the run.
            Step.CYCLE_CLOSE, Step.CLOSE -> Unit
            else -> { cancelTimer(); if (hardFailure == null) hardFailure = step.name; enter(Step.CLOSE) }
        }
    }

    /** Camera-side signal. Signals for an earlier session are ignored, so a late close cannot disturb a new cycle. */
    fun signal(session: String, signal: Signal, atNs: Long = clock(), detail: String? = null) {
        if (session != this.session) return
        when (signal) {
            Signal.OPENED -> if (step == Step.OPEN) { marks["opened"] = atNs; enter(Step.CONFIGURE) }
            Signal.CONFIGURED -> if (step == Step.CONFIGURE) { marks["configured"] = atNs; enter(Step.FIRST_FRAME) }
            Signal.FIRST_FRAME -> if (step == Step.FIRST_FRAME) {
                marks["first_yuv"] = atNs
                if (onObservationSession) { observeFirstFrameNs = atNs; enter(Step.WARMUP) } else enter(Step.CYCLE_CLOSE)
            }
            Signal.CLOSED -> when (step) {
                Step.CYCLE_CLOSE -> { marks["closed"] = atNs; cycleDone() }
                Step.CLOSE -> { marks["closed"] = atNs; finish() }
                else -> Unit
            }
            Signal.ERROR -> onFailure(detail ?: step.name, atNs)
        }
    }

    /**
     * The engine reported the actual submission of a still (Camera2Engine's `capture_submit`) together with the
     * request tag it used. The submission time comes from here rather than from the moment [Driver.still] was
     * called: the engine posts the request to its own thread, and METRICS.md measures from just before the API
     * call, so counting the queue wait would inflate 2.2, 2.3 and 2.5.
     */
    fun stillSubmitted(session: String, tag: String, atNs: Long = clock()) {
        if (session != this.session) return
        val p = stills.lastOrNull { it.tag == null && !it.closed } ?: return
        p.tag = tag
        p.submitNs = atNs
    }

    /**
     * A JPEG arrived. [sensorNs] is the image timestamp, which equals the SENSOR_TIMESTAMP of the request's
     * result, so it identifies the request even when the callbacks overtake each other. An image whose request
     * is not known yet is held until its result names it, and it never silently becomes a later sample.
     */
    fun stillImage(session: String, sensorNs: Long?, atNs: Long = clock()) {
        if (session != this.session) return
        val current = stills.lastOrNull()
        val known = sensorNs?.let { s -> stills.firstOrNull { it.sensorNs == s } }
        if (known != null) {
            if (known.imageNs == null) known.imageNs = atNs
            if (known === current && step == Step.STILL) stillAdvance()
            return
        }
        if (sensorNs != null) unmatchedImages[sensorNs] = atNs
        // Not attributable yet: it still means one capture came back, so the current sample takes it for now and
        // the result that names the image corrects the attribution.
        if (current != null && !current.closed && current.imageNs == null && step == Step.STILL) {
            current.imageNs = atNs
            stillAdvance()
        }
    }

    /**
     * Result callback of a still request (onCaptureCompleted); used for 2.3. Matched by request tag, so a result
     * that arrives after the next capture was submitted, or after the run moved on to CLOSE, still lands on its
     * own sample.
     */
    fun stillResult(session: String, tag: String?, sensorNs: Long? = null, atNs: Long = clock()) {
        if (session != this.session) return
        val p = tag?.let { t -> stills.firstOrNull { it.tag == t } } ?: return
        if (p.resultNs == null) p.resultNs = atNs
        if (sensorNs == null || p.sensorNs != null) return
        p.sensorNs = sensorNs
        unmatchedImages.remove(sensorNs)?.let { imageAt ->
            // Take the image back from whichever sample had provisionally claimed it.
            stills.firstOrNull { it !== p && it.imageNs == imageAt }?.imageNs = null
            p.imageNs = imageAt
        }
    }

    /** The first repeating capture started (onCaptureStarted); used for 1.3. */
    fun firstStarted(session: String, atNs: Long = clock()) {
        if (session == this.session) marks.putIfAbsent("first_started", atNs)
    }

    /** The driver marks the moment right before an API call so the runner never has to guess a submission time. */
    fun mark(session: String, name: String, atNs: Long = clock(), override: Boolean = false) {
        if (session != this.session) return
        if (override) marks[name] = atNs else marks.putIfAbsent(name, atNs)
    }

    // ---- internals ----

    private fun enter(next: Step) {
        cancelTimer()
        step = next
        when (next) {
            Step.OPEN -> {
                marks.clear()
                cycleFailed = false
                session = "bm-$runId-${endpoint.key}-$cycleIndex"
                sessions += session
                if (onObservationSession) observeSession = session
                progress()
                marks["open_call"] = clock()
                driver.open(endpoint, session)
                arm(config.openTimeoutMs)
            }
            Step.CONFIGURE -> { progress(); arm(config.configureTimeoutMs) }
            Step.FIRST_FRAME -> { progress(); arm(config.firstFrameTimeoutMs) }
            Step.CYCLE_CLOSE -> { progress(); marks["close_call"] = clock(); driver.close(session); armClose { cycleDone() } }
            Step.WARMUP -> {
                progress()
                marks["warmup_start"] = clock()
                timer = scheduler.after(profile.warmupMs) { enter(Step.OBSERVE) }
            }
            Step.OBSERVE -> {
                observeStartNs = clock()
                marks["observe_start"] = observeStartNs!!
                progress()
                timer = scheduler.after(profile.observeMs) {
                    observeEndNs = clock()
                    marks["observe_end"] = observeEndNs!!
                    // 3A values are fixed the moment the window closes; the screen shows that as its own phase (3.2).
                    listener.onProgress(Phase.THREE_A, Step.OBSERVE, 0, 0)
                    enter(Step.STILL)
                }
            }
            Step.STILL -> { stillIndex = 0; progress(); submitStill() }
            Step.CLOSE -> { progress(); marks["close_call"] = clock(); driver.close(session); armClose { finish() } }
            else -> Unit
        }
    }

    private fun progress() {
        val phase = when (step) {
            Step.OPEN, Step.CONFIGURE, Step.FIRST_FRAME -> if (onObservationSession) Phase.FIRST_PREVIEW else Phase.CAMERA_OPEN
            Step.CYCLE_CLOSE -> Phase.CAMERA_OPEN
            Step.WARMUP, Step.OBSERVE -> Phase.PREVIEW_STABILITY
            Step.STILL -> Phase.STILL_CAPTURE
            else -> Phase.CAMERA_CLOSE
        }
        val iteration = if (onObservationSession) profile.launchIterations else cycleIndex
        listener.onProgress(phase, step, iteration, profile.launchIterations)
    }

    /** A timeout or a camera error. Inside a launch cycle it fails that cycle; anywhere else it ends the run. */
    private fun onFailure(reason: String, atNs: Long) {
        if (step == Step.DONE || step == Step.ABORTED) return
        marks["failure"] = atNs
        if (hardFailure == null) hardFailure = reason
        when (step) {
            // Close this sample so a late JPEG is still attributed to it, then try the next capture.
            Step.STILL -> { stills.lastOrNull()?.closed = true; stillAdvance() }
            Step.CYCLE_CLOSE -> cycleDone()
            Step.CLOSE -> finish()
            Step.OPEN, Step.CONFIGURE, Step.FIRST_FRAME -> {
                cycleFailed = true
                // The observation session cannot be retried: without a preview there is nothing left to measure.
                if (onObservationSession) { aborted = aborted ?: "observation_failed"; enter(Step.CLOSE) } else enter(Step.CYCLE_CLOSE)
            }
            else -> enter(Step.CLOSE)
        }
    }

    private fun cycleDone() {
        cancelTimer()
        if (step != Step.CYCLE_CLOSE) return
        cycles += buildCycle()
        if (cycleFailed) consecutiveFailures++ else consecutiveFailures = 0
        if (consecutiveFailures >= config.maxConsecutiveFailures) { aborted = aborted ?: "repeated_failure"; finish(); return }
        if (aborted != null) { finish(); return }
        cycleIndex++
        enter(Step.OPEN)
    }

    private fun buildCycle(): LaunchCycle {
        fun ms(a: String, b: String): Double? {
            val x = marks[a]; val y = marks[b]
            return if (x != null && y != null) (y - x) / 1e6 else null
        }
        return LaunchCycle(
            iteration = cycleIndex,
            warmup = profile.excludeFirst && cycleIndex == 0,
            openMs = ms("open_call", "opened"),
            configureMs = ms("configure_call", "configured"),
            firstStartedMs = ms("repeating_call", "first_started"),
            yuvProxyMs = ms("repeating_call", "first_yuv"),
            previewTotalMs = ms("open_call", "first_yuv"),
            // A close latency only means something after a successful open: after a failure the engine has often
            // closed itself already and the observed value is meaningless.
            closeMs = if (cycleFailed) null else ms("close_call", "closed")?.takeIf { it >= 0.0 },
            failed = cycleFailed,
            timestampsNs = marks.toMap()
        )
    }

    private fun submitStill() {
        cancelTimer()
        // The submission time is provisional until the engine reports capture_submit for this request.
        stills += PendingStill(stillIndex, profile.excludeFirst && stillIndex == 0, clock())
        driver.still(session)
        arm(config.stillTimeoutMs)
    }

    /** Moves on to the next capture, or to CLOSE when the profile's captures are done. */
    private fun stillAdvance() {
        cancelTimer()
        stillIndex++
        if (aborted != null || stillIndex >= profile.stillCount) enter(Step.CLOSE) else submitStill()
    }

    /** One still capture while the run is in flight; [StillSample] is the immutable form stored in the result. */
    private class PendingStill(val index: Int, val warmup: Boolean, var submitNs: Long) {
        var tag: String? = null
        var imageNs: Long? = null
        var resultNs: Long? = null
        var sensorNs: Long? = null
        /** Timed out: a JPEG arriving later belongs here, but must not advance the run any further. */
        var closed = false

        fun toSample() = StillSample(index, warmup, submitNs, imageNs, resultNs)
    }

    private fun arm(ms: Long) {
        timer = scheduler.after(ms) { onFailure("${step.name}_timeout", clock()) }
    }

    /** A close that never reports back must not hang the run; the timeout continues with [andThen]. */
    private fun armClose(andThen: () -> Unit) {
        timer = scheduler.after(config.closeTimeoutMs) {
            if (hardFailure == null) hardFailure = "${step.name}_timeout"
            if (step == Step.CYCLE_CLOSE) cycleFailed = true
            andThen()
        }
    }

    private fun cancelTimer() { timer?.let { scheduler.cancel(it) }; timer = null }

    private fun finish() {
        cancelTimer()
        if (step == Step.DONE || step == Step.ABORTED) return
        step = if (aborted != null) Step.ABORTED else Step.DONE
        listener.onFinished(
            Result(
                runId = runId, endpoint = endpoint, cycles = cycles.toList(), stills = stills.map { it.toSample() },
                observeSession = observeSession, observeFirstFrameNs = observeFirstFrameNs,
                observeStartNs = observeStartNs, observeEndNs = observeEndNs,
                hardFailure = hardFailure, aborted = aborted, sessions = sessions.toList()
            )
        )
    }
}
