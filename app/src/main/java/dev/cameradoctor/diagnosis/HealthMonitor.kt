package dev.cameradoctor.diagnosis

import dev.cameradoctor.telemetry.Event
import java.util.Locale

enum class HealthLevel { NO_DATA, OK, WATCH, WARNING }

/**
 * What the app can say about the last few seconds from its own callbacks.
 * Every statement is relative to a baseline from the same session, never a fixed number,
 * and no cause is attributed: the app only sees framework callbacks, not the HAL.
 */
data class Assessment(
    val level: HealthLevel,
    val headline: String,
    val evidence: List<String>,
    val tRefMs: Double?,
    val values: Map<String, Any?>,
    val states: List<MetricState> = emptyList(),
    val diagnosis: Diagnosis? = null
)

/**
 * Live health strip. Runs the same MetricExtractor / ThresholdEngine / DiagnosisRules as Auto Check over a short
 * window, with two live-specific choices documented in PRODUCT-v0.2.md 12.1: the recent window is aggregated by MAX
 * (spike detection) against the session baseline p95, and 3A convergence metrics (H.6 to H.8) are not judged because
 * the window start is not a convergence start. When the AE cadence changes, the H.1 baseline is scaled by the
 * duration ratio so a legitimate 30 to 15 fps change is not reported as slower.
 */
class HealthMonitor(
    private val recentNs: Long = 1_500_000_000L,
    private val holdNs: Long = 3_000_000_000L,
    private val minBaseline: Int = 15,
    private val engine: ThresholdEngine = ThresholdEngine(),
    private val rules: DiagnosisRules = DiagnosisRules(),
    private val device: DeviceContext = DeviceContext()
) {
    private val extractor = MetricExtractor(minSamples = 1)
    private val liveIds = listOf("H.1", "H.2", "H.3", "H.4", "H.5", "H.9")
    private val composer = HealthComposer(table = ThresholdTable.rules.filterKeys { it in liveIds })
    private var lastWarningNs = 0L
    private var held: Assessment? = null

    fun assess(events: List<Event>, session: String, now: Long): Assessment {
        val all = extractor.frames(events, session, Long.MIN_VALUE, now)
        val baselineFrames = all.filter { it.resultAtNs < now - recentNs }
        val recentFrames = all.filter { it.resultAtNs >= now - recentNs }
        if (baselineFrames.size < minBaseline || recentFrames.isEmpty()) {
            return Assessment(HealthLevel.NO_DATA, "HEALTH · baseline 수집 중 (${all.size}/$minBaseline)", emptyList(), null, emptyMap())
        }
        val base = extractor.observe(baselineFrames)
        val tRef = base.intervalP50 ?: return Assessment(HealthLevel.NO_DATA, "HEALTH · 간격 정보 없음", emptyList(), null, emptyMap())
        val recent = extractor.observe(recentFrames, baselineIntervalMs = tRef, aggregation = MetricExtractor.Aggregation.MAX)

        val baseDuration = base.durationP50
        val recentDuration = recent.durationP50
        val cadenceChanged = baseDuration != null && recentDuration != null && kotlin.math.abs(recentDuration - baseDuration) > 0.2 * baseDuration
        val cadenceScale = if (cadenceChanged) recentDuration!! / baseDuration!! else 1.0

        fun p95(xs: List<Double?>) = MetricExtractor.percentile(xs.filterNotNull(), 0.95)
        val baseline = mapOf(
            "H.1" to BaselineValue((p95(base.frames.map { it.intervalMs }) ?: tRef) * cadenceScale),
            "H.2" to BaselineValue((p95(base.frames.map { it.intervalMs }) ?: tRef) * cadenceScale),
            "H.3" to BaselineValue(p95(base.frames.map { it.partialMs }) ?: 0.0),
            "H.4" to BaselineValue(p95(base.frames.map { it.bufferMs }) ?: 0.0)
        ).filterValues { it.value > 0.0 }
        val samples = recent.samples.filter { it.id in liveIds } + extractor.failureSample(events, session, now - recentNs, now)
        val states = engine.evaluate(samples, baseline, device)
        val diagnosis = rules.diagnose(states, DiagnosisRules.Context(cadenceChanged = cadenceChanged, threeAStable = recent.threeAStable))
        val health = composer.compose(states, hasBaseline = true)

        val byId = states.associateBy { it.id }
        val stalls = all.count { it.stalled(tRef) }
        val focus = recent.worstStall ?: recent.worstPartial ?: recent.last!!
        val last = recent.last!!
        val threeA = "AE ${dev.cameradoctor.telemetry.stateName("AE", last.ae)} · AF ${dev.cameradoctor.telemetry.stateName("AF", last.af)} · AWB ${dev.cameradoctor.telemetry.stateName("AWB", last.awb)}"
        val baseGap = base.partialP50
        val recentGapMax = recent.worstPartial?.partialMs
        val recentMaxInterval = recent.frames.mapNotNull { it.intervalMs }.maxOrNull()
        val caseText = DiagnosisRules.CONSUMER_TEXT[diagnosis.rule] ?: diagnosis.rule

        val evidence = mutableListOf<String>()
        evidence += "판정: ${diagnosis.rule} — $caseText"
        evidence += "관측: interval ${fmt(focus.intervalMs)} · duration ${fmt(focus.ownDurationMs)} · partial +${fmt(focus.partialMs)} ms (frame #${focus.frame ?: "—"})"
        evidence += "기준 p50: interval ${fmt(tRef)} · duration ${fmt(baseDuration)} · partial +${fmt(baseGap)} ms (n=${base.n})"
        states.forEach { s ->
            evidence += "${s.id} ${s.final.jsonName}" +
                (s.thresholdBasis?.let { " · basis ${it.jsonName}" } ?: "") +
                (s.absoluteBound?.let { " · abs bound ${fmt(it)} (${s.absoluteSource})" } ?: "") +
                (s.deltaPct?.let { " · vs baseline ${pct(it)}" } ?: "") +
                (s.unknownReason?.let { " · ${it.jsonName}" } ?: "")
        }
        evidence += "10초 내 stall ${stalls}회 · 최근 최대 interval ${fmt(recentMaxInterval)} ms · 최근 최대 partial +${fmt(recentGapMax)} ms (실제 drop 개수 아님)"
        evidence += "3A: $threeA" + if (recent.threeAStable) " · 안정" else " · 수렴 중 (노출 조정이 간격을 바꿀 수 있음)"
        evidence += "원인 층: ${diagnosis.causeLayer.jsonName}. 앱은 콜백 도착만 관측하며 HAL 내부와 CPU 스케줄링은 Perfetto가 있어야 판정 가능"
        diagnosis.evidence.forEach { evidence += "근거: $it" }

        val values = mapOf(
            "case" to diagnosis.rule, "focusFrame" to focus.frame, "intervalMs" to focus.intervalMs,
            "frameDurationMs" to focus.ownDurationMs, "partialGapMs" to focus.partialMs,
            "tRefMs" to tRef, "thresholdMs" to 1.5 * tRef, "recentMaxIntervalMs" to recentMaxInterval, "stallCount10s" to stalls,
            "baselineGapMs" to baseGap, "recentMaxGapMs" to recentGapMax,
            "intervalAnomaly" to (recent.stallCount > 0), "gapAnomaly" to (byId["H.3"]?.final.let { it == State.WARN || it == State.FAIL }),
            "threeAStable" to recent.threeAStable, "ae" to last.ae, "af" to last.af, "awb" to last.awb, "baselineFrames" to base.n,
            "baselineFrameDurationMs" to baseDuration, "recentFrameDurationMs" to recentDuration, "cadenceChanged" to cadenceChanged,
            "healthLevel" to health.level.jsonName, "coverage" to health.coverage,
            "metricStates" to states.map { it.toJsonMap() }, "diagnosis" to diagnosis.toJsonMap()
        )

        val level = when (health.level) {
            HealthLevelV2.ISSUE, HealthLevelV2.WARNING -> HealthLevel.WARNING
            else -> if (diagnosis.rule == "normal") HealthLevel.OK else HealthLevel.WATCH
        }
        val headline = when (diagnosis.rule) {
            "pipeline_stall" -> "⚠ PIPELINE STALL · interval ${fmt(focus.intervalMs)} > duration ${fmt(focus.ownDurationMs)} · partial +${fmt(focus.partialMs)} ms (기준 +${fmt(baseGap)})"
            "sensor_stall" -> "⚠ SENSOR STALL · interval ${fmt(focus.intervalMs)} ms > duration ${fmt(focus.ownDurationMs)} ms (+${pctOf(focus.intervalMs, tRef)}% vs 기준) · 앞단"
            "callback_delay" -> "⚠ PARTIAL DELAY · +${fmt(recentGapMax)} ms vs 기준 +${fmt(baseGap)} ms (${pct(byId["H.3"]?.deltaPct)}) · cadence 정상 · 뒷단"
            "cadence_change" -> "HEALTH · CADENCE ${fmt(baseDuration)} → ${fmt(recentDuration)} ms (AE 가변 FPS) · stall 아님"
            "three_a_searching" -> "HEALTH · 3A 수렴 중 · interval ${fmt(last.intervalMs)} ms"
            "normal" -> "HEALTH · OK · interval ${fmt(last.intervalMs)} · duration ${fmt(recentDuration)} · partial +${fmt(last.partialMs)} ms"
            else -> "⚠ ${diagnosis.rule.uppercase(Locale.US).replace('_', ' ')} · ${diagnosis.evidence.firstOrNull() ?: ""}"
        }
        val fresh = Assessment(level, headline, evidence, tRef, values, states, diagnosis)
        // Hold a warning briefly so a spike stays readable and can be captured; new warnings replace the held one.
        if (fresh.level == HealthLevel.WARNING) { lastWarningNs = now; held = fresh; return fresh }
        val h = held
        if (h != null && now - lastWarningNs < holdNs) return h.copy(evidence = fresh.evidence, values = fresh.values + ("held" to true))
        held = null
        return fresh
    }

    private fun fmt(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
    private fun pct(v: Double?) = v?.let { String.format(Locale.US, "%+.0f%%", it) } ?: "—"
    private fun pctOf(v: Double?, base: Double) = v?.let { ((it / base - 1.0) * 100).toInt() } ?: 0
}
