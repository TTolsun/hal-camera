package dev.halcamera.diagnosis

import java.util.Locale

/**
 * Picks a diagnosis rule id from metric states (docs/PRODUCT-v0.2.md chapter 8).
 * The same rule id is rendered differently by consumer and expert UIs; the rule never names the HAL as a cause.
 */
class DiagnosisRules {

    data class Context(
        val cadenceChanged: Boolean = false,
        val threeAStable: Boolean = true,
        val coverage: Double = 1.0,
        val insufficient: Boolean = false
    )

    companion object {
        /** 8.2 priority, highest first. */
        val PRIORITY = listOf(
            "hard_failure", "below_spec", "pipeline_stall", "sensor_stall", "callback_delay",
            "cdd_reference_exceeded", "slower_than_baseline", "three_a_unstable", "cadence_change",
            "three_a_searching", "insufficient_evidence", "normal"
        )
        val CONSUMER_TEXT = mapOf(
            "normal" to "현재 프레임 흐름은 정상입니다.",
            "slower_than_baseline" to "규격 범위 안이지만 평소보다 느립니다.",
            "below_spec" to "평소와 같지만 성능 기준을 만족하지 않습니다.",
            "cdd_reference_exceeded" to "권장 성능 기준보다 느립니다.",
            "sensor_stall" to "프레임이 예상보다 늦게 도착합니다.",
            "callback_delay" to "프레임 응답이 지연됩니다. 촬영 파이프라인 후반부의 지연 가능성이 있습니다.",
            "pipeline_stall" to "프레임 흐름 전체가 정체됩니다.",
            "cadence_change" to "어두운 환경에서 프레임 속도가 낮아졌습니다. 문제가 아닙니다.",
            "three_a_unstable" to "초점 또는 노출 맞추기가 평소보다 오래 걸립니다.",
            "three_a_searching" to "초점을 맞추는 중입니다.",
            "hard_failure" to "카메라를 열거나 촬영하지 못했습니다.",
            "insufficient_evidence" to "검사할 데이터가 부족합니다. 밝은 곳에서 다시 검사해 주세요."
        )
    }

    fun diagnose(states: List<MetricState>, context: Context = Context()): Diagnosis {
        val byId = states.associateBy { it.id }
        fun bad(id: String) = byId[id]?.final.let { it == State.WARN || it == State.FAIL }
        val matched = LinkedHashMap<String, MutableList<String>>()
        fun hit(rule: String, evidence: String) { matched.getOrPut(rule) { mutableListOf() } += evidence }

        states.filter { it.isHardFailure }.forEach { hit("hard_failure", "${it.id} hard failure (${it.absoluteSource ?: "error"})") }
        states.filter { it.final == State.FAIL && it.thresholdBasis == ThresholdBasis.ABSOLUTE_VALIDATED }
            .forEach { hit("below_spec", "${it.id} ${fmt(it.value)} ≥ ${fmt(it.absoluteBound)} (${it.absoluteSource})") }
        states.filter { (it.final == State.WARN || it.final == State.FAIL) && it.thresholdBasis == ThresholdBasis.ABSOLUTE_REFERENCE }
            .forEach { hit("cdd_reference_exceeded", "${it.id} ${fmt(it.value)} ≥ ${fmt(it.absoluteBound)} (${it.absoluteSource}, environment_not_equivalent)") }

        val stall = bad("H.5")
        val partial = bad("H.3") || bad("H.4")
        when {
            stall && partial -> hit("pipeline_stall", "H.5 stall ${fmt(byId["H.5"]?.value)} · H.3 partial ${fmt(byId["H.3"]?.value)} ms")
            stall -> hit("sensor_stall", "H.5 stall count ${fmt(byId["H.5"]?.value)} · interval > own duration")
            partial -> hit("callback_delay", "H.3 partial ${fmt(byId["H.3"]?.value)} ms (${pct(byId["H.3"]?.deltaPct)}) · cadence 정상")
        }
        states.filter { (it.final == State.WARN || it.final == State.FAIL) && it.thresholdBasis == ThresholdBasis.RELATIVE && it.id !in setOf("H.5", "H.3", "H.4", "H.6", "H.7", "H.8") }
            .forEach { hit("slower_than_baseline", "${it.id} ${fmt(it.value)} vs baseline ${fmt(it.baselineValue)} (${pct(it.deltaPct)})") }
        listOf("H.6", "H.7", "H.8").filter { bad(it) }.forEach { hit("three_a_unstable", "$it ${fmt(byId[it]?.value)} ms") }
        if (context.cadenceChanged) hit("cadence_change", "AE variable FPS · not a stall")
        if (!context.threeAStable) hit("three_a_searching", "3A not yet stable at window end")
        if (context.insufficient) hit("insufficient_evidence", "coverage ${pct(context.coverage * 100)}")

        val ordered = PRIORITY.filter { it in matched }
        val head = ordered.firstOrNull() ?: "normal"
        val evidence = mutableListOf<String>()
        ordered.forEach { r -> matched[r]!!.forEach { evidence += "$r: $it" } }
        val cause = when (head) {
            "sensor_stall", "pipeline_stall" -> CauseLayer.SENSOR_FRONT
            "callback_delay" -> CauseLayer.FRAMEWORK_CALLBACK
            else -> CauseLayer.UNATTRIBUTED
        }
        return Diagnosis(head, cause, evidence, ordered.drop(1))
    }

    private fun fmt(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
    private fun pct(v: Double?) = v?.let { String.format(Locale.US, "%+.0f%%", it) } ?: "—"
}
