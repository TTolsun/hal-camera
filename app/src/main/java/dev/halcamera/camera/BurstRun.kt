package dev.halcamera.camera

/**
 * A LIVE burst (#178): [count] stills taken one after another through the engine's ordinary photo path, so each
 * shot is saved, named and described exactly like a single photo. A shot starts once the previous one has been
 * written and the engine is free again, and no sooner than [intervalMs] after the previous shot started; with 0 it
 * goes as fast as the camera and storage allow. Each shot's request id ("burst-<id>-03") lands in its capture
 * metadata, which is what ties the saved files of one burst together.
 *
 * A failed shot does not end the burst, but two in a row do: by then the camera or storage is gone and every later
 * shot would fail the same way. [cancel] stops before the next shot; a shot already submitted still reports.
 * [finished] is called exactly once, with every shot that was attempted and the number never taken.
 *
 * Pure logic: the caller supplies the clock, the scheduler and the shutter, all on one thread.
 */
class BurstRun<T>(
    val id: String,
    val count: Int,
    val intervalMs: Long,
    private val clock: () -> Long,
    private val schedule: (delayMs: Long, block: () -> Unit) -> Unit,
    /** True when the engine can take a still right now. */
    private val ready: () -> Boolean,
    private val shoot: (requestId: String, done: (Result<T>) -> Unit) -> Unit,
    private val progress: (BurstRun<T>) -> Unit,
    private val finished: (BurstSummary<T>) -> Unit,
) {
    init {
        require(count in 1..MAX_COUNT) { "Burst count must be 1..$MAX_COUNT" }
        require(intervalMs >= 0) { "Burst interval must not be negative" }
    }

    private val shots = mutableListOf<BurstShot<T>>()
    private var lastStartMs: Long? = null
    private var shooting = false
    private var stopReason: String? = null
    private var done = false

    val attempted: Int get() = shots.size + if (shooting) 1 else 0
    val saved: Int get() = shots.count { it.result.isSuccess }
    val failed: Int get() = shots.count { it.result.isFailure }
    val running: Boolean get() = !done

    fun start() { next() }

    /** Takes no further shot. A shot in flight still reports before [finished] is called. */
    fun cancel(reason: String) {
        if (done || stopReason != null) return
        stopReason = reason
        if (!shooting) finish()
    }

    fun requestId(index: Int) = "burst-$id-${(index + 1).toString().padStart(2, '0')}"

    private fun next() {
        if (done) return
        if (stopReason != null || shots.size >= count) { finish(); return }
        val wait = lastStartMs?.let { it + intervalMs - clock() } ?: 0L
        if (wait > 0) { schedule(wait) { next() }; return }
        awaitReady(clock())
    }

    /** The engine clears its busy flag a moment after the save callback, so a shot waits for it rather than failing. */
    private fun awaitReady(sinceMs: Long) {
        if (done) return
        if (stopReason != null) { finish(); return }
        if (ready()) { fire(); return }
        if (clock() - sinceMs >= READY_TIMEOUT_MS) { stopReason = "Camera stayed busy"; finish(); return }
        schedule(READY_POLL_MS) { awaitReady(sinceMs) }
    }

    private fun fire() {
        val index = shots.size
        val requestId = requestId(index)
        val startMs = clock()
        lastStartMs = startMs
        shooting = true
        progress(this)
        var answered = false
        shoot(requestId) { result ->
            if (answered) return@shoot
            answered = true
            shooting = false
            shots += BurstShot(index, requestId, startMs, result)
            progress(this)
            if (shots.size >= 2 && shots.takeLast(2).all { it.result.isFailure } && stopReason == null)
                stopReason = "Two shots in a row failed"
            next()
        }
    }

    private fun finish() {
        if (done) return
        done = true
        finished(BurstSummary(id, count, intervalMs, shots.toList(), stopReason))
    }

    companion object {
        /** Each still can hold tens of MB until it is written; LIVE never queues more than this. */
        const val MAX_COUNT = 20
        const val READY_POLL_MS = 20L
        const val READY_TIMEOUT_MS = 5_000L
    }
}

data class BurstShot<T>(val index: Int, val requestId: String, val startedMs: Long, val result: Result<T>)

data class BurstSummary<T>(
    val id: String,
    val requested: Int,
    val intervalMs: Long,
    val shots: List<BurstShot<T>>,
    /** Why the burst ended early; null when every requested shot was attempted. */
    val stopReason: String?,
) {
    val saved: Int get() = shots.count { it.result.isSuccess }
    val failedShots: List<BurstShot<T>> get() = shots.filter { it.result.isFailure }
    val notTaken: Int get() = requested - shots.size
    /** Time between consecutive shot starts: what the camera and storage actually allowed. */
    val startGapsMs: List<Long> get() = shots.zipWithNext { a, b -> b.startedMs - a.startedMs }

    /** "Burst 8/10 saved · #4 failed: Capture timed out · 1 not taken (Stopped)". */
    fun describe(): String = buildString {
        append("Burst $saved/$requested saved")
        failedShots.forEach { append(" · #${it.index + 1} failed: ${it.result.exceptionOrNull()?.message ?: "unknown"}") }
        if (notTaken > 0) append(" · $notTaken not taken")
        stopReason?.let { append(" ($it)") }
    }
}
