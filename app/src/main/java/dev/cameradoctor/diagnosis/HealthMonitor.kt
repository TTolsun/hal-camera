package dev.cameradoctor.diagnosis

import dev.cameradoctor.telemetry.Event
import dev.cameradoctor.telemetry.stateName
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
    val values: Map<String, Any?>
)

class HealthMonitor(
    private val recentNs: Long = 1_500_000_000L,
    private val holdNs: Long = 3_000_000_000L,
    private val minBaseline: Int = 15
) {
    private var lastWarningNs = 0L
    private var held: Assessment? = null

    fun assess(events: List<Event>, session: String, now: Long): Assessment {
        val results = events.filter { it.session == session && it.kind == "capture_result" }
        val baseline = results.filter { it.atNs < now - recentNs }
        val recent = results.filter { it.atNs >= now - recentNs }
        if (baseline.size < minBaseline || recent.isEmpty()) {
            return Assessment(HealthLevel.NO_DATA, "HEALTH · baseline 수집 중 (${results.size}/$minBaseline)", emptyList(), null, emptyMap())
        }
        val baseIntervals = baseline.mapNotNull { num(it, "intervalMs") }
        val tRef = median(baseIntervals) ?: return Assessment(HealthLevel.NO_DATA, "HEALTH · 간격 정보 없음", emptyList(), null, emptyMap())
        val threshold = 1.5 * tRef
        val recentIntervals = recent.mapNotNull { num(it, "intervalMs") }
        val recentMaxInterval = recentIntervals.maxOrNull()
        // Variable FPS: under AE control the HAL may legitimately lengthen SENSOR_FRAME_DURATION (e.g. 33 -> 66 ms in low light).
        // An interval that matches the frame's own reported duration is a cadence change, not a stall. Only intervals that
        // exceed 1.5x of both the session baseline and the frame's own duration count as anomalies.
        fun durationMs(r: Event) = num(r, "frameDurationNs")?.div(1e6)
        fun stalled(r: Event): Boolean {
            val i = num(r, "intervalMs") ?: return false
            val own = durationMs(r)
            return i > threshold && (own == null || i > 1.5 * own)
        }
        val baseDuration = median(baseline.mapNotNull(::durationMs))
        val recentDuration = median(recent.mapNotNull(::durationMs))
        val cadenceChanged = baseDuration != null && recentDuration != null && kotlin.math.abs(recentDuration - baseDuration) > 0.2 * baseDuration
        val intervalAnomaly = recent.any(::stalled)
        val stalls = results.count(::stalled)
        val fpsRange = events.lastOrNull { it.session == session && it.kind == "request_observed" }?.values?.get("fpsRange")?.toString()

        val starts = events.filter { it.session == session && it.kind == "capture_started" && it.frame != null }.associate { it.frame!! to it.atNs }
        fun gapMs(r: Event): Double? = starts[r.frame]?.let { (r.atNs - it) / 1e6 }
        val baseGap = median(baseline.mapNotNull(::gapMs))
        val recentGapMax = recent.mapNotNull(::gapMs).maxOrNull()
        val gapAnomaly = baseGap != null && recentGapMax != null && recentGapMax > baseGap * 1.5 && recentGapMax - baseGap > 10.0

        val last = recent.last()
        val ae = (last.values["ae"] as? Number)?.toInt(); val af = (last.values["af"] as? Number)?.toInt(); val awb = (last.values["awb"] as? Number)?.toInt()
        val aeStable = ae == 2 || ae == 3; val afStable = af == null || af == 0 || af == 2 || af == 4; val awbStable = awb == 2 || awb == 3
        val threeA = "AE ${stateName("AE", ae)} · AF ${stateName("AF", af)} · AWB ${stateName("AWB", awb)}"
        val threeAStable = aeStable && afStable && awbStable

        val evidence = mutableListOf<String>()
        evidence += "센서 간격: 최근 최대 ${fmt(recentMaxInterval)} ms · 기준 p50 ${fmt(tRef)} ms · 임계 ${fmt(threshold)} ms"
        evidence += "요청 FPS 범위 ${fpsRange ?: "—"} · 프레임 duration 기준 ${fmt(baseDuration)} → 최근 ${fmt(recentDuration)} ms" +
            if (cadenceChanged) " (가변 FPS cadence 변경, stall 아님)" else ""
        evidence += "10초 내 stall ${stalls}회 (간격 > 1.5 × 기준 및 > 1.5 × 자체 duration. 실제 drop 개수 아님)"
        evidence += "START→PARTIAL: 최근 최대 ${fmt(recentGapMax)} ms · 기준 p50 ${fmt(baseGap)} ms"
        evidence += "3A: $threeA"
        evidence += if (threeAStable) "3A 안정. 노출 변화로 인한 정상 cadence 변경 가능성 낮음" else "3A 수렴 중. 간격 변화가 노출 조정 때문일 수 있음"
        evidence += "원인 층: unattributed (앱은 콜백만 관측. HAL/스케줄링 판정은 Perfetto 필요)"

        val values = mapOf(
            "tRefMs" to tRef, "thresholdMs" to threshold, "recentMaxIntervalMs" to recentMaxInterval, "stallCount10s" to stalls,
            "baselineGapMs" to baseGap, "recentMaxGapMs" to recentGapMax, "intervalAnomaly" to intervalAnomaly, "gapAnomaly" to gapAnomaly,
            "threeAStable" to threeAStable, "ae" to ae, "af" to af, "awb" to awb, "baselineFrames" to baseline.size,
            "baselineFrameDurationMs" to baseDuration, "recentFrameDurationMs" to recentDuration, "cadenceChanged" to cadenceChanged, "fpsRange" to fpsRange
        )
        val fresh = when {
            intervalAnomaly -> Assessment(HealthLevel.WARNING,
                "⚠ FRAME INTERVAL ${fmt(recentMaxInterval)} ms · 기준 ${fmt(tRef)} ms (+${pct(recentMaxInterval!!, tRef)}%)", evidence, tRef, values)
            gapAnomaly -> Assessment(HealthLevel.WARNING,
                "⚠ PARTIAL CALLBACK +${fmt(recentGapMax)} ms · 기준 +${fmt(baseGap)} ms (+${pct(recentGapMax!!, baseGap!!)}%)", evidence, tRef, values)
            cadenceChanged -> Assessment(HealthLevel.WATCH, "HEALTH · FPS cadence 변경 ${fmt(baseDuration)} → ${fmt(recentDuration)} ms (AE 가변 FPS)", evidence, tRef, values)
            !threeAStable -> Assessment(HealthLevel.WATCH, "HEALTH · 3A 수렴 중 · interval ${fmt(recentIntervals.lastOrNull())} ms", evidence, tRef, values)
            else -> Assessment(HealthLevel.OK, "HEALTH · OK · interval ${fmt(recentIntervals.lastOrNull())} ms · partial +${fmt(recent.lastOrNull()?.let(::gapMs))} ms", evidence, tRef, values)
        }
        // Hold a warning briefly so a spike stays readable and can be captured; new warnings replace the held one.
        if (fresh.level == HealthLevel.WARNING) { lastWarningNs = now; held = fresh; return fresh }
        val h = held
        if (h != null && now - lastWarningNs < holdNs) return h.copy(evidence = fresh.evidence, values = fresh.values + ("held" to true))
        held = null
        return fresh
    }

    private fun num(e: Event, key: String) = (e.values[key] as? Number)?.toDouble()
    private fun median(xs: List<Double>): Double? = if (xs.isEmpty()) null else xs.sorted().let { s -> s[s.size / 2] }
    private fun fmt(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
    private fun pct(v: Double, base: Double) = ((v / base - 1.0) * 100).toInt()
}
