package dev.cameradoctor.diagnosis

import kotlin.math.roundToInt

/**
 * Composite health and optional score (docs/PRODUCT-v0.2.md chapter 7).
 * ISSUE only from hard or validated-absolute FAIL. Relative and heuristic FAIL stop at WARNING.
 * No score without a baseline; no score below 70 % coverage; score hidden unless [scoreEnabled].
 */
class HealthComposer(
    private val table: Map<String, ThresholdTable.Rule> = ThresholdTable.rules,
    private val scoreEnabled: Boolean = false,
    private val minCoverage: Double = 0.7,
    private val normalMinKnownWeight: Double = 0.5
) {
    fun compose(states: List<MetricState>, hasBaseline: Boolean): Health {
        val counts = State.values().associateWith { s -> states.count { it.final == s } }
        val issue = states.any { it.final == State.FAIL && (it.thresholdBasis == ThresholdBasis.HARD || it.thresholdBasis == ThresholdBasis.ABSOLUTE_VALIDATED) }
        val warning = states.any { it.final == State.WARN || it.final == State.FAIL }

        val total = table.values.sumOf { it.weight }
        fun w(id: String) = table[id]?.weight ?: 0.0
        val known = states.filter { it.final != State.UNKNOWN }
        val knownWeight = known.sumOf { w(it.id) }
        val coverage = if (total > 0) knownWeight / total else 0.0
        val passWeight = states.filter { it.final == State.PASS }.sumOf { w(it.id) }

        val level = when {
            issue -> HealthLevelV2.ISSUE
            warning -> HealthLevelV2.WARNING
            total > 0 && passWeight / total >= normalMinKnownWeight -> HealthLevelV2.NORMAL
            else -> HealthLevelV2.INSUFFICIENT
        }

        val score: Int? = when {
            !hasBaseline -> null
            coverage < minCoverage -> null
            knownWeight <= 0.0 -> null
            else -> {
                val points = known.sumOf { w(it.id) * when (it.final) { State.PASS -> 1.0; State.WARN -> 0.5; else -> 0.0 } }
                val raw = (100.0 * points / knownWeight).roundToInt()
                if (states.any { it.isHardFailure }) minOf(raw, 59) else raw
            }
        }
        return Health(level, coverage, score, scoreEnabled && score != null, counts)
    }
}
