package dev.halcamera.ui

/** The camera keeps running: only the displayed frame is held. All clocks are app monotonic clocks. */
data class CallbackTimelineRow(val id: String, val label: String, val latenciesMs: List<Double>, val state: String?)
data class CallbackTimelineFrame(val number: Long?, val startAtNs: Long?, val rows: List<CallbackTimelineRow>)

class ResultCallbackTimeline {
    companion object {
        val HOLD_SECONDS = listOf(0, 1, 3, 5, 10)
    }

    fun cycleHoldSeconds() {
        holdSeconds = HOLD_SECONDS[(HOLD_SECONDS.indexOf(holdSeconds) + 1) % HOLD_SECONDS.size]
    }

    var autoHold: Boolean
        get() = holdSeconds > 0
        set(value) { holdSeconds = if (value) holdSeconds.takeIf { it > 0 } ?: 3 else 0 }
    var holdSeconds = 0
        set(value) {
            field = value.coerceIn(0, 10)
            autoHoldUntilNs = null
            heldTrigger = null
        }
    var axisMs = 200
        private set
    private var smallerAxisSinceNs: Long? = null
    var displayed: CallbackTimelineFrame? = null
        private set
    var autoHoldUntilNs: Long? = null
        private set
    private var key: Pair<String, Long>? = null
    private var observedUntilNs = Long.MIN_VALUE
    private var heldTrigger: Pair<String, Long>? = null

    fun reset() {
        key = null
        displayed = null
        autoHoldUntilNs = null
        heldTrigger = null
        axisMs = 200
        smallerAxisSinceNs = null
    }

    fun toggleAutoHold(nowNs: Long) {
        autoHold = !autoHold
        resume(nowNs)
    }

    fun resume(nowNs: Long) {
        autoHoldUntilNs = null
        heldTrigger = null
        observedUntilNs = nowNs // Explicit resume never replays a capture already in the ring.
    }

    fun remainingSeconds(nowNs: Long): Int? = autoHoldUntilNs?.let {
        ((it - nowNs).coerceAtLeast(0) + 999_999_999L).div(1_000_000_000L).toInt()
    }

    fun update(series: ResultCallbackSeries, session: String, configuredAtNs: Long, nowNs: Long) {
        val newKey = session to configuredAtNs
        if (key != newKey) {
            reset()
            key = newKey
            observedUntilNs = nowNs // Opening the overlay must not freeze an old photograph.
        }
        val trigger = series.tracks.filter { !it.repeating && it.unavailable == null }.flatMap { track ->
            track.points.filter { it.atNs > observedUntilNs }.map { track.id to it }
        }.maxByOrNull { it.second.atNs }
        observedUntilNs = maxOf(observedUntilNs, nowNs)
        if (!autoHold) { autoHoldUntilNs = null; heldTrigger = null }
        if (autoHold && trigger != null) {
            heldTrigger = trigger.first to trigger.second.atNs
            autoHoldUntilNs = nowNs + holdSeconds * 1_000_000_000L
            display(snapshot(series, series.frames.find { it.startAtNs == trigger.second.startAtNs }, heldTrigger), nowNs)
        }
        if (autoHoldUntilNs?.let { nowNs < it } == true) {
            // Late callbacks may complete this same frame, but never replace it with a preview frame.
            val point = series.tracks.find { it.id == heldTrigger?.first }?.points?.find { it.atNs == heldTrigger?.second }
            val frame = series.frames.find { it.startAtNs == point?.startAtNs }
            if (frame != null) display(snapshot(series, frame, heldTrigger), nowNs)
            return
        }
        if (autoHoldUntilNs != null) { autoHoldUntilNs = null; heldTrigger = null }
        // Index arrivals once instead of scanning every track's history for each candidate.
        val required = series.tracks.filter { it.repeating && it.unavailable == null }.associate { track ->
            track.id to track.points.mapNotNullTo(HashSet()) { it.startAtNs }
        }
        // Select the newest complete frame, or advance after 250 ms even if an output is missing.
        // An older complete frame must not pin the graph indefinitely when a stream stalls.
        val frame = series.frames.lastOrNull { candidate ->
            nowNs - candidate.startAtNs >= 250_000_000L || required.all { (id, arrivals) ->
                (candidate.targets != null && id !in candidate.targets && id != "all" && id != "start" && !id.startsWith("metadata:")) ||
                    candidate.startAtNs in arrivals
            }
        }
        display(snapshot(series, frame), nowNs)
    }

    private fun display(frame: CallbackTimelineFrame, nowNs: Long) {
        displayed = frame
        val maximum = frame.rows.flatMap { it.latenciesMs }.maxOfOrNull { kotlin.math.abs(it) } ?: return
        val padded = (maximum * 1.15).coerceAtLeast(50.0)
        val unit = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(padded)))
        val target = (listOf(1, 2, 5, 10).first { it * unit >= padded } * unit).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
        when {
            target > axisMs -> { axisMs = target; smallerAxisSinceNs = null }
            target <= axisMs / 2 && autoHoldUntilNs == null -> {
                val since = smallerAxisSinceNs ?: nowNs.also { smallerAxisSinceNs = it }
                if (nowNs - since >= 3_000_000_000L) { axisMs = target; smallerAxisSinceNs = null }
            }
            else -> smallerAxisSinceNs = null
        }
    }

    private fun snapshot(series: ResultCallbackSeries, frame: ResultCallbackFrame?, trigger: Pair<String, Long>? = null) =
        CallbackTimelineFrame(frame?.number, frame?.startAtNs, series.tracks.map { track ->
            val points = track.points.filter { (frame != null && it.startAtNs == frame.startAtNs) ||
                (trigger?.first == track.id && trigger.second == it.atNs) }
            val values = points.mapNotNull { it.latencyMs }
            val state = when {
                values.isNotEmpty() -> null
                track.unavailable != null -> track.unavailable
                points.isNotEmpty() -> "No start time"
                frame == null -> "Awaiting data"
                track.id == "all" || track.id == "start" || track.id.startsWith("metadata:") -> "Awaiting data"
                frame.targets != null && track.id !in frame.targets -> "Not requested"
                !track.repeating && frame.targets == null -> "Awaiting capture"
                else -> "Awaiting data"
            }
            CallbackTimelineRow(track.id, track.label, values, state)
        })
}
