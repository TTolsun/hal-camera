package dev.halcamera.ui

import dev.halcamera.metrics.MetricExtractor
import dev.halcamera.telemetry.Event
import java.util.Locale

/**
 * The live numbers of the LIVE screen (docs/PLAN-BenchMarker-v0.3.md 8.1). Every field is an observation; none of
 * them is a verdict.
 *
 * This replaces the Doctor-era HealthMonitor, which ran the threshold engine and the diagnosis rules over the last
 * few seconds and put OK / WATCH / WARNING on the screen. That judgement was the thing M3 removes: a fixed-interval
 * opinion formed from a two-second window is not a measurement anyone can act on, and the BenchMarker answers the
 * same question properly by comparing a whole run against a baseline the developer chose.
 *
 * [intervalRefMs] survives because [StripView] needs a reference line to draw against, and the reference is the
 * session's own median interval rather than a target frame rate: a 30 to 15 fps change by AE is a different cadence,
 * not a slower one.
 */
data class LiveReading(
    val baselineFrames: Int,
    val intervalRefMs: Double?,
    val baselinePartialMs: Double?,
    val baselineBufferMs: Double?,
    val intervalMs: Double?,
    val maxIntervalMs: Double?,
    val frameDurationMs: Double?,
    val partialMs: Double?,
    val bufferMs: Double?,
    val stalls: Int,
    val ae: Int?, val af: Int?, val awb: Int?
) {
    val hasReference: Boolean get() = intervalRefMs != null
}

/**
 * Reads [LiveReading] off the flight recorder. Cheap enough to call on the main thread once per tick, because the
 * work is one pass of [MetricExtractor] over the events already held in the ring buffer.
 */
class LiveReadout(
    private val recentNs: Long = 1_500_000_000L,
    private val minBaseline: Int = 15
) {
    private val extractor = MetricExtractor(minSamples = 1)

    fun read(events: List<Event>, session: String, now: Long): LiveReading {
        val all = extractor.frames(events, session, Long.MIN_VALUE, now)
        val last = all.lastOrNull()
        // The reference needs a settled population, so it is drawn from the frames older than the recent window.
        val older = all.filter { it.resultAtNs < now - recentNs }
        val recent = all.filter { it.resultAtNs >= now - recentNs }
        if (older.size < minBaseline) {
            return LiveReading(
                all.size, null, null, null, last?.intervalMs, null, last?.ownDurationMs,
                last?.partialMs, last?.bufferMs, 0, last?.ae, last?.af, last?.awb
            )
        }
        val base = extractor.observe(older)
        val ref = base.intervalP50
        return LiveReading(
            baselineFrames = base.n,
            intervalRefMs = ref,
            baselinePartialMs = base.partialP50,
            baselineBufferMs = MetricExtractor.percentile(older.mapNotNull { it.bufferMs }, 0.5),
            intervalMs = last?.intervalMs,
            maxIntervalMs = recent.mapNotNull { it.intervalMs }.maxOrNull(),
            frameDurationMs = last?.ownDurationMs,
            partialMs = last?.partialMs,
            bufferMs = last?.bufferMs,
            stalls = all.count { it.stalled(ref) },
            ae = last?.ae, af = last?.af, awb = last?.awb
        )
    }

    companion object {
        /**
         * The panel's raw table. One row per number, aligned, and a dash wherever a value cannot be read yet.
         *
         * The labels are English because the block is drawn in a monospace face, where a Hangul glyph occupies
         * two cells while [String.padEnd] counts it as one: Korean labels put every value in a different column.
         * The 3A states get their own indented rows rather than one long line, so no row has to wrap.
         */
        fun panelText(r: LiveReading): String {
            fun ms(v: Double?) = v?.let { String.format(Locale.US, "%.1f ms", it) } ?: "—"
            fun row(label: String, value: String) = label.padEnd(LABEL_WIDTH) + value
            return listOf(
                row("interval", ms(r.intervalMs)),
                row("interval ref p50", ms(r.intervalRefMs)),
                row("interval max", ms(r.maxIntervalMs)),
                row("frame duration", ms(r.frameDurationMs)),
                row("partial", ms(r.partialMs)),
                row("partial ref p50", ms(r.baselinePartialMs)),
                row("buffer", ms(r.bufferMs)),
                row("stalls (10s)", r.stalls.toString()),
                row("ref frames", r.baselineFrames.toString()),
                row("  AE", dev.halcamera.telemetry.stateName("AE", r.ae)),
                row("  AF", dev.halcamera.telemetry.stateName("AF", r.af)),
                row("  AWB", dev.halcamera.telemetry.stateName("AWB", r.awb))
            ).joinToString("\n")
        }

        /** The one-line strip caption under the sparkline. */
        fun stripText(frame: Long?, partialMs: Double?, bufferMs: Double?): String {
            fun short(v: Double?) = v?.let { String.format(Locale.US, "%+.1f", it) } ?: "—"
            return "#${frame ?: "—"}  START +0  PARTIAL ${short(partialMs)}  BUFFER ${short(bufferMs)} ms"
        }

        internal const val LABEL_WIDTH = 18
    }
}
