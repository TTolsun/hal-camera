package dev.cameradoctor.telemetry

import java.util.ArrayDeque

/** All atNs values belong to elapsedRealtimeNanos, never wall time or sensor time. */
data class Event(
    val atNs: Long,
    val session: String,
    val kind: String,
    val frame: Long? = null,
    val sensorNs: Long? = null,
    val values: Map<String, Any?> = emptyMap()
)

data class Incident(
    val id: String,
    val triggerNs: Long,
    val requestedStartNs: Long,
    val requestedEndNs: Long,
    val finishedNs: Long,
    val finishReason: String,
    val events: List<Event>,
    val capacityEvictions: Long,
    val incidentTruncated: Boolean
)

class FlightRecorder(
    private val clock: () -> Long,
    private val retentionNs: Long = 30_000_000_000L,
    private val maxEvents: Int = 18_000,
    private val preNs: Long = 10_000_000_000L,
    private val postNs: Long = 5_000_000_000L
) {
    private val ring = ArrayDeque<Event>()
    private var capacityEvictions = 0L
    private var pending: Pending? = null
    private data class Pending(val id: String, val trigger: Long, val events: MutableList<Event>, var truncated: Boolean = false)
    init { require(retentionNs >= preNs && maxEvents > 0 && preNs >= 0 && postNs >= 0) }

    @Synchronized fun record(session: String, kind: String, frame: Long? = null,
        sensorNs: Long? = null, values: Map<String, Any?> = emptyMap()): Event {
        val now = clock()
        trim(now)
        val event = Event(now, session, kind, frame, sensorNs, values.toMap())
        ring.addLast(event)
        while (ring.size > maxEvents) { ring.removeFirst(); capacityEvictions++ }
        pending?.let {
            if (now <= it.trigger + postNs) {
                if (it.events.size < maxEvents * 2) it.events.add(event) else it.truncated = true
            }
        }
        return event
    }
    @Synchronized fun snapshot(windowNs: Long = retentionNs): List<Event> {
        val now = clock()
        trim(now)
        return ring.filter { it.atNs >= now - windowNs }
    }
    @Synchronized fun trigger(id: String): Boolean {
        if (pending != null) return false
        val now = clock()
        trim(now)
        pending = Pending(id, now, ring.filter { it.atNs >= now - preNs }.toMutableList())
        record("app", "incident_trigger", values = mapOf("incidentId" to id))
        return true
    }
    @Synchronized fun remainingNs(): Long? = pending?.let { (it.trigger + postNs - clock()).coerceAtLeast(0) }
    @Synchronized fun finish(forceReason: String? = null): Incident? {
        val p = pending ?: return null
        val now = clock()
        if (forceReason == null && now < p.trigger + postNs) return null
        pending = null
        return Incident(p.id, p.trigger, p.trigger - preNs, p.trigger + postNs, now,
            forceReason ?: "completed", p.events.toList(), capacityEvictions, p.truncated)
    }
    private fun trim(now: Long) {
        while (ring.isNotEmpty() && ring.first.atNs < now - retentionNs) ring.removeFirst()
    }
}

/** Gaps are observed result gaps, not a count of HAL drops or rendered preview drops. */
data class FrameStats(val intervalMs: Double?, val fps: Double?, val resultGap: Long?)
class FrameTracker {
    private var lastSensor: Long? = null
    private var lastFrame: Long? = null
    fun add(frame: Long, sensorNs: Long?): FrameStats {
        val interval = sensorNs?.let { timestamp -> lastSensor?.let { timestamp - it } }?.takeIf { it > 0 }
        val gap = lastFrame?.let { (frame - it - 1).coerceAtLeast(0) }
        if (sensorNs != null && (lastSensor == null || sensorNs > lastSensor!!)) lastSensor = sensorNs
        if (lastFrame == null || frame > lastFrame!!) lastFrame = frame
        return FrameStats(interval?.div(1e6), interval?.let { 1e9 / it }, gap)
    }
}
