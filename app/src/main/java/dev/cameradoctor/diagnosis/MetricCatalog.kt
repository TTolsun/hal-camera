package dev.cameradoctor.diagnosis

import java.util.Locale

/**
 * Human names for metric ids and states. UI layers must go through this: a metric id such as "H.1" is never shown
 * alone (docs/PRODUCT-v0.2.md 11.5). Expert views append the id in parentheses; consumer views omit it.
 */
object MetricCatalog {
    data class Info(val id: String, val name: String, val consumer: String, val unit: String)

    private val infos = listOf(
        Info("1.1", "카메라 열기", "카메라 켜기", "ms"),
        Info("1.2", "세션 구성", "카메라 준비", "ms"),
        Info("1.3", "첫 프레임 시작 콜백", "첫 화면 응답", "ms"),
        Info("1.5", "액티비티 생성→열기", "앱 준비", "ms"),
        Info("1.6", "프리뷰 시작 총 지연", "화면이 켜질 때까지", "ms"),
        Info("1.7", "카메라 닫기", "카메라 끄기", "ms"),
        Info("1.8", "첫 YUV 프레임 도착", "첫 프레임 도착", "ms"),
        Info("2.1", "셔터 지연", "셔터 반응", "ms"),
        Info("2.2", "촬영→이미지 수신", "사진 저장 속도", "ms"),
        Info("2.3", "촬영→결과 메타데이터", "촬영 결과 응답", "ms"),
        Info("2.4", "3A 사전 수렴", "촬영 전 초점·노출", "ms"),
        Info("2.5", "연속 촬영 간격", "연속 촬영", "ms"),
        Info("2.6", "프리뷰 복귀", "촬영 후 화면 복귀", "ms"),
        Info("2.7", "촬영 중 프리뷰 stall", "촬영 중 끊김", "회"),
        Info("3.1", "녹화 시작→첫 콜백", "녹화 시작", "ms"),
        Info("3.2", "녹화 중 간격 이상", "녹화 중 끊김", "회"),
        Info("3.3", "인코더 프레임 손실", "녹화 프레임 손실", "개"),
        Info("3.4", "녹화 안정 FPS", "녹화 프레임 속도", "fps"),
        Info("3.5", "장시간 녹화 FPS 저하", "장시간 녹화", "fps"),
        Info("3.6", "녹화 정지 지연", "녹화 정지", "ms"),
        Info("3.7", "녹화 간격 jitter", "녹화 흔들림", "ms"),
        Info("H.1", "프레임 간격 p50", "프레임 속도", "ms"),
        Info("H.2", "프레임 간격 p95", "프레임 속도 편차", "ms"),
        Info("H.3", "partial 결과 지연", "프레임 응답", "ms"),
        Info("H.4", "버퍼 도착 지연", "프레임 전달", "ms"),
        Info("H.5", "frame stall 횟수", "화면 끊김", "회"),
        Info("H.6", "AE 수렴 시간", "노출 맞추기", "ms"),
        Info("H.7", "AF 수렴 시간", "초점 맞추기", "ms"),
        Info("H.8", "AWB 수렴 시간", "색 맞추기", "ms"),
        Info("H.9", "콜백 실패 횟수", "촬영 오류", "회")
    ).associateBy { it.id }

    fun info(id: String): Info = infos[id] ?: Info(id, id, id, "")

    fun stateText(s: State): String = when (s) {
        State.PASS -> "정상"; State.WARN -> "주의"; State.FAIL -> "이상"; State.UNKNOWN -> "미판정"
    }

    fun stateMark(s: State): String = when (s) {
        State.PASS -> "✓"; State.WARN -> "△"; State.FAIL -> "✗"; State.UNKNOWN -> "○"
    }

    fun basisText(b: ThresholdBasis?): String = when (b) {
        null -> ""
        ThresholdBasis.HARD -> "오류·timeout"
        ThresholdBasis.ABSOLUTE_VALIDATED -> "규격 기준"
        ThresholdBasis.ABSOLUTE_REFERENCE -> "권장 기준 참조"
        ThresholdBasis.RELATIVE -> "평소 대비"
        ThresholdBasis.HEURISTIC -> "경험 기준"
    }

    fun unknownText(r: UnknownReason?): String = when (r) {
        null -> ""
        UnknownReason.NOT_MEASURABLE -> "측정 불가"
        UnknownReason.NOT_RUN -> "이번 검사에 없음"
        UnknownReason.INSUFFICIENT_SAMPLES -> "표본 부족"
        UnknownReason.NO_BASELINE -> "기준 없음, 다음 검사부터 비교"
        UnknownReason.UNSUPPORTED -> "기기 미지원"
        UnknownReason.CADENCE_CHANGED -> "프레임 속도 변경으로 비교 불가"
        UnknownReason.CONDITION_MISMATCH -> "환경이 달라 비교 불가"
    }

    private val convergenceIds = setOf("H.6", "H.7", "H.8", "2.4")

    fun value(s: MetricState): String {
        val i = info(s.id)
        val v = s.value ?: return "—"
        return if (i.unit == "회" || i.unit == "개") "${v.toInt()}${i.unit}" else String.format(Locale.US, "%.1f %s", v, i.unit)
    }

    private fun consumerValue(s: MetricState): String {
        // This describes the five-second boundary, not whether convergence eventually completed.
        // Expert views retain the exact observed duration, including values beyond that boundary.
        return if (s.id in convergenceIds && (s.value ?: 0.0) > 5000.0 &&
            (s.final == State.WARN || s.final == State.FAIL)) "5초 내 미완료" else value(s)
    }

    /** Why a WARN/FAIL was raised, in consumer words, chosen by the basis that produced the state (5.4). */
    private fun consumerWhy(s: MetricState): String = when (s.thresholdBasis) {
        ThresholdBasis.RELATIVE -> s.deltaPct?.let { String.format(Locale.US, "  평소보다 %+.0f%%", it) } ?: ""
        ThresholdBasis.ABSOLUTE_REFERENCE -> s.absoluteBound?.let { String.format(Locale.US, "  권장 기준 %.0f %s 초과", it, info(s.id).unit) } ?: "  권장 기준 초과"
        ThresholdBasis.ABSOLUTE_VALIDATED -> s.absoluteBound?.let { String.format(Locale.US, "  성능 기준 %.0f %s 초과", it, info(s.id).unit) } ?: "  성능 기준 초과"
        ThresholdBasis.HEURISTIC -> if (s.id in convergenceIds) "" else s.absoluteBound?.let { String.format(Locale.US, "  기준 %.0f %s 초과", it, info(s.id).unit) } ?: ""
        ThresholdBasis.HARD -> "  실패"
        null -> ""
    }

    /** Expert line: name (id)  value  state [basis or unknown reason]  vs baseline. */
    fun expertLine(s: MetricState): String {
        val i = info(s.id)
        val tail = when {
            s.final == State.UNKNOWN -> unknownText(s.unknownReason)
            s.thresholdBasis != null -> basisText(s.thresholdBasis)
            else -> ""
        }
        val delta = s.deltaPct?.let { String.format(Locale.US, " · 평소 대비 %+.0f%%", it) } ?: ""
        val bound = s.absoluteBound?.takeIf { s.thresholdBasis != null && s.thresholdBasis != ThresholdBasis.RELATIVE }
            ?.let { String.format(Locale.US, " · 기준 %.0f", it) } ?: ""
        return "${stateMark(s.final)} ${i.name} (${s.id})  ${value(s)}  ${stateText(s.final)}" + (if (tail.isEmpty()) "" else " · $tail") + delta + bound
    }

    /** Consumer line: plain name, value, state. No id, no basis vocabulary. */
    fun consumerLine(s: MetricState): String {
        val i = info(s.id)
        val why = when {
            s.final == State.UNKNOWN -> "  (${unknownText(s.unknownReason)})"
            s.final == State.WARN || s.final == State.FAIL -> consumerWhy(s)
            else -> ""
        }
        return "${stateMark(s.final)} ${i.consumer}  ${consumerValue(s)}$why"
    }
}
