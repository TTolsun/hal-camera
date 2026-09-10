package dev.halcamera.check

import dev.halcamera.diagnosis.MetricSample
import dev.halcamera.diagnosis.UnknownReason

/**
 * Auto Check state machine (docs/PRODUCT-v0.2.md chapter 10). Pure Kotlin: the camera is driven through [Driver],
 * time through [Scheduler], so the machine is unit-testable with fakes. One runner instance runs one check.
 *
 * Per endpoint: OPEN -> CONFIGURE -> FIRST_FRAME -> OBSERVE (fixed 10 s) -> STILL x3 -> CLOSE. A timeout in any step
 * records a hard failure for that endpoint and moves on to CLOSE; the next endpoint still runs. No automatic retry.
 */
class AutoCheckRunner(
    private val driver: Driver,
    private val scheduler: Scheduler,
    private val clock: () -> Long,
    private val config: Config = Config(),
    private val listener: Listener
) {
    data class Config(
        val openTimeoutMs: Long = 3000, val configureTimeoutMs: Long = 3000, val firstFrameTimeoutMs: Long = 3000,
        val observeMs: Long = 10_000, val stillCount: Int = 3, val stillTimeoutMs: Long = 5000, val closeTimeoutMs: Long = 3000,
        val maxEndpoints: Int = 4
    )

    enum class Step { IDLE, OPEN, CONFIGURE, FIRST_FRAME, OBSERVE, STILL, CLOSE, DONE, ABORTED }

    /** Camera-side events the runner reacts to. Timestamps are elapsedRealtimeNanos. */
    enum class Signal { OPENED, CONFIGURED, FIRST_FRAME, STILL_RECEIVED, CLOSED, ERROR }

    interface Driver {
        /** Open [endpoint], configure preview+yuv+jpeg and start repeating. Must report OPENED, CONFIGURED, FIRST_FRAME. */
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
        fun onStep(endpoint: CameraEndpoint, index: Int, total: Int, step: Step)
        fun onEndpointDone(result: EndpointResult)
        fun onFinished(results: List<EndpointResult>, aborted: String?)
    }

    data class EndpointResult(
        val endpoint: CameraEndpoint,
        val session: String,
        val hardFailure: String?,           // step name that failed, null when the endpoint completed
        val failedStep: Step?,
        val openMs: Double?, val configureMs: Double?, val firstStartedMs: Double?, val yuvProxyMs: Double?,
        val previewTotalMs: Double?, val closeMs: Double?,
        val stillLatenciesMs: List<Double>, val stillResultLatenciesMs: List<Double>, val shotToShotMs: List<Double>,
        val observeStartNs: Long?, val observeEndNs: Long?,
        val timestamps: Map<String, Long>,
        /** Number of still requests actually submitted; 0 when the endpoint failed before the STILL step. */
        val stillSubmitNsCount: Int = stillLatenciesMs.size
    ) {
        /** Group A samples for ThresholdEngine (4.3). Metrics not run in v0.2 are reported UNKNOWN(not_run) by the caller. */
        fun launchSamples(): List<MetricSample> {
            fun latency(id: String, v: Double?, step: Step) = MetricSample(id, v, n = if (v == null) 0 else 1,
                hardFailure = failedStep == step && v == null,
                unknownReason = if (v == null && failedStep != step) UnknownReason.NOT_RUN else null)
            fun p50(xs: List<Double>) = dev.halcamera.diagnosis.MetricExtractor.percentile(xs, 0.5)
            return listOf(
                latency("1.1", openMs, Step.OPEN),
                latency("1.2", configureMs, Step.CONFIGURE),
                latency("1.3", firstStartedMs, Step.FIRST_FRAME),
                latency("1.8", yuvProxyMs, Step.FIRST_FRAME),
                latency("1.6", previewTotalMs, Step.FIRST_FRAME),
                // Close latency only means something after a successful open; after a failure the engine has often
                // already closed itself, so the observed value is meaningless (even negative).
                if (failedStep != null && failedStep != Step.CLOSE) MetricSample("1.7", null, unknownReason = UnknownReason.NOT_RUN)
                else latency("1.7", closeMs?.takeIf { it >= 0.0 }, Step.CLOSE),
                MetricSample("2.2", p50(stillLatenciesMs), p95 = dev.halcamera.diagnosis.MetricExtractor.percentile(stillLatenciesMs, 0.95),
                    n = stillLatenciesMs.size, hardFailure = failedStep == Step.STILL,
                    unknownReason = if (stillLatenciesMs.isEmpty() && failedStep != Step.STILL) UnknownReason.NOT_RUN else null),
                MetricSample("2.3", p50(stillResultLatenciesMs), n = stillResultLatenciesMs.size,
                    unknownReason = if (stillResultLatenciesMs.isEmpty()) UnknownReason.NOT_RUN else null),
                // 2.5 needs a statistic; with 3 stills there is one valid interval, so it stays UNKNOWN(insufficient_samples).
                MetricSample("2.5", null, n = shotToShotMs.size,
                    unknownReason = if (stillSubmitNsCount == 0) UnknownReason.NOT_RUN else UnknownReason.INSUFFICIENT_SAMPLES)
            )
        }
    }

    private var endpoints: List<CameraEndpoint> = emptyList()
    private var index = -1
    var step = Step.IDLE; private set
    private var session = ""
    private var timer: Any? = null
    private val results = mutableListOf<EndpointResult>()
    private val marks = LinkedHashMap<String, Long>()
    private var failedStep: Step? = null
    private var stillsDone = 0
    private val stillSubmitNs = mutableListOf<Long>()
    private val stillImageNs = mutableListOf<Long>()
    private val stillResultNs = mutableListOf<Long>()
    private var aborted: String? = null

    val currentSession: String get() = session

    fun start(candidates: List<CameraEndpoint>) {
        check(step == Step.IDLE) { "runner already used" }
        endpoints = candidates.filter { it.independentlyOpenable }.take(config.maxEndpoints)
        if (endpoints.isEmpty()) { step = Step.ABORTED; aborted = "no_camera"; listener.onFinished(emptyList(), aborted); return }
        next()
    }

    fun abort(reason: String) {
        if (step == Step.DONE || step == Step.ABORTED) return
        cancelTimer()
        aborted = reason
        if (step != Step.IDLE && step != Step.CLOSE) { failedStep = step; enter(Step.CLOSE) } else finish()
    }

    /** Camera-side signal for the current session. Signals for other sessions are ignored. */
    fun signal(session: String, signal: Signal, atNs: Long = clock(), detail: String? = null) {
        if (session != this.session) return
        when (signal) {
            Signal.OPENED -> if (step == Step.OPEN) { marks["opened"] = atNs; enter(Step.CONFIGURE) }
            Signal.CONFIGURED -> if (step == Step.CONFIGURE) { marks["configured"] = atNs; enter(Step.FIRST_FRAME) }
            Signal.FIRST_FRAME -> if (step == Step.FIRST_FRAME) { marks["first_yuv"] = atNs; enter(Step.OBSERVE) }
            Signal.STILL_RECEIVED -> if (step == Step.STILL) { stillImageNs += atNs; stillsDone++; if (stillsDone >= config.stillCount) enter(Step.CLOSE) else submitStill() }
            Signal.CLOSED -> if (step == Step.CLOSE) { marks["closed"] = atNs; endpointDone() }
            Signal.ERROR -> if (step != Step.CLOSE && step != Step.DONE && step != Step.ABORTED) { failedStep = step; marks["error"] = atNs; enter(Step.CLOSE) }
        }
    }

    /** Result callback for a still request (onCaptureCompleted). Optional; used for 2.3. */
    fun stillResult(session: String, atNs: Long = clock()) { if (session == this.session && step == Step.STILL) stillResultNs += atNs }
    /** Called by the driver when the first repeating capture starts (onCaptureStarted); used for 1.3. */
    fun firstStarted(session: String, atNs: Long = clock()) { if (session == this.session && "first_started" !in marks) marks["first_started"] = atNs }
    /** Called by the driver right before the API call so the runner does not guess the submission time. */
    fun mark(session: String, name: String, atNs: Long = clock(), override: Boolean = false) {
        if (session != this.session) return
        if (override) marks[name] = atNs else marks.putIfAbsent(name, atNs)
    }

    private fun next() {
        index++
        if (index >= endpoints.size) { finish(); return }
        marks.clear(); failedStep = null; stillsDone = 0; stillSubmitNs.clear(); stillImageNs.clear(); stillResultNs.clear()
        session = "check-${endpoints[index].key}-${clock()}"
        enter(Step.OPEN)
    }

    private fun enter(next: Step) {
        cancelTimer()
        step = next
        val ep = endpoints[index]
        listener.onStep(ep, index, endpoints.size, next)
        when (next) {
            Step.OPEN -> { marks["open_call"] = clock(); driver.open(ep, session); arm(config.openTimeoutMs) }
            Step.CONFIGURE -> arm(config.configureTimeoutMs)
            Step.FIRST_FRAME -> arm(config.firstFrameTimeoutMs)
            Step.OBSERVE -> { marks["observe_start"] = clock(); timer = scheduler.after(config.observeMs) { marks["observe_end"] = clock(); enter(Step.STILL) } }
            Step.STILL -> submitStill()
            Step.CLOSE -> { marks["close_call"] = clock(); driver.close(session); timer = scheduler.after(config.closeTimeoutMs) { if (failedStep == null) failedStep = Step.CLOSE; endpointDone() } }
            else -> Unit
        }
    }

    private fun submitStill() {
        cancelTimer()
        stillSubmitNs += clock()
        driver.still(session)
        arm(config.stillTimeoutMs)
    }

    private fun arm(ms: Long) { timer = scheduler.after(ms) { failedStep = step; marks["timeout"] = clock(); enter(Step.CLOSE) } }
    private fun cancelTimer() { timer?.let { scheduler.cancel(it) }; timer = null }

    private fun endpointDone() {
        cancelTimer()
        fun ms(a: String, b: String): Double? { val x = marks[a]; val y = marks[b]; return if (x != null && y != null) (y - x) / 1e6 else null }
        val stills = stillImageNs.indices.filter { it < stillSubmitNs.size }.map { (stillImageNs[it] - stillSubmitNs[it]) / 1e6 }
        val stillResults = stillResultNs.indices.filter { it < stillSubmitNs.size }.map { (stillResultNs[it] - stillSubmitNs[it]) / 1e6 }
        val shot = stillSubmitNs.zipWithNext { a, b -> (b - a) / 1e6 }
        val r = EndpointResult(
            endpoint = endpoints[index], session = session, hardFailure = failedStep?.name, failedStep = failedStep,
            openMs = ms("open_call", "opened"), configureMs = ms("configure_call", "configured"),
            firstStartedMs = ms("repeating_call", "first_started"), yuvProxyMs = ms("repeating_call", "first_yuv"),
            previewTotalMs = ms("open_call", "first_yuv"), closeMs = ms("close_call", "closed"),
            stillLatenciesMs = stills, stillResultLatenciesMs = stillResults, shotToShotMs = shot,
            observeStartNs = marks["observe_start"], observeEndNs = marks["observe_end"], timestamps = marks.toMap(),
            stillSubmitNsCount = stillSubmitNs.size
        )
        results += r
        listener.onEndpointDone(r)
        if (aborted != null) finish() else next()
    }

    private fun finish() {
        cancelTimer()
        step = if (aborted != null) Step.ABORTED else Step.DONE
        listener.onFinished(results.toList(), aborted)
    }
}
