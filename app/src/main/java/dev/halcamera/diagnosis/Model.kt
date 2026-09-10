package dev.halcamera.diagnosis

import java.util.Locale

/**
 * Code contract from docs/PRODUCT-v0.2.md section 13.2.
 * Three orthogonal axes: State says how bad the result is, ThresholdBasis says why it was judged so,
 * ConditionEquivalence says how close the measurement conditions are to the external reference.
 * Every enum serializes to snake_case via [jsonName].
 */
enum class State { PASS, WARN, FAIL, UNKNOWN }

enum class ThresholdBasis { HARD, ABSOLUTE_VALIDATED, ABSOLUTE_REFERENCE, RELATIVE, HEURISTIC }

enum class ConditionEquivalence { EQUIVALENT, SIMILAR, NON_EQUIVALENT }

enum class CddApplicability { APPLICABLE, NOT_APPLICABLE }

enum class UnknownReason {
    NOT_MEASURABLE, NOT_RUN, INSUFFICIENT_SAMPLES, NO_BASELINE,
    UNSUPPORTED, CADENCE_CHANGED, CONDITION_MISMATCH
}

enum class HealthLevelV2 { NORMAL, WARNING, ISSUE, INSUFFICIENT }

enum class CauseLayer { UNATTRIBUTED, APP, FRAMEWORK_CALLBACK, SENSOR_FRONT }

val Enum<*>.jsonName: String get() = name.lowercase(Locale.US)

/** Severity order used by the worst-of rule (5.4): FAIL > WARN > PASS > UNKNOWN. */
val State.rank: Int get() = when (this) { State.FAIL -> 3; State.WARN -> 2; State.PASS -> 1; State.UNKNOWN -> 0 }

fun worstOf(a: State, b: State): State = if (a.rank >= b.rank) a else b

/**
 * One measured metric before judgement. Produced by MetricExtractor (H.x) or AutoCheckRunner (1.x / 2.x).
 * [expectedMs] carries the physics bound for cadence metrics (own SENSOR_FRAME_DURATION or 1e9/fps for fixed fps).
 */
data class MetricSample(
    val id: String,
    val value: Double?,
    val p95: Double? = null,
    val n: Int = 0,
    val hardFailure: Boolean = false,
    val unknownReason: UnknownReason? = null,
    val expectedMs: Double? = null,
    val fixedCadence: Boolean = false,
    val timeout: Boolean = false
)

data class BaselineValue(val value: Double, val p95: Double? = null)

/** Device and run context needed for CDD gating (5.2). */
data class DeviceContext(
    val mediaPerformanceClass: Int = 0,
    val primaryCamera: Boolean = false,
    val conditionEquivalence: ConditionEquivalence = ConditionEquivalence.SIMILAR
) {
    /** U = Android 14 = API 34. Values below U used other bounds and are not applicable. */
    val cddApplicability: CddApplicability
        get() = if (mediaPerformanceClass >= 34 && primaryCamera) CddApplicability.APPLICABLE else CddApplicability.NOT_APPLICABLE
}

data class MetricState(
    val id: String,
    val value: Double?,
    val p95: Double? = null,
    val n: Int,

    val absolute: State,
    val absoluteBound: Double?,
    val absoluteSource: String?,

    val relative: State,
    val baselineValue: Double?,
    val deltaPct: Double?,

    val final: State,

    val thresholdBasis: ThresholdBasis?,
    val cddApplicability: CddApplicability?,
    val conditionEquivalence: ConditionEquivalence?,

    val unknownReason: UnknownReason?
) {
    val isHardFailure: Boolean
        get() = final == State.FAIL && thresholdBasis == ThresholdBasis.HARD

    companion object {
        /** Inverse of [toJsonMap] for reading a stored run JSON. Unknown enum names fall back to UNKNOWN / null. */
        fun fromJsonMap(m: Map<String, Any?>): MetricState {
            fun d(k: String) = (m[k] as? Number)?.toDouble()
            fun s(k: String) = (m[k] as? String)?.takeIf { it.isNotEmpty() && it != "null" }
            fun <E : Enum<E>> e(k: String, values: Array<E>): E? = s(k)?.let { v -> values.firstOrNull { it.jsonName == v } }
            return MetricState(
                id = m["id"] as String, value = d("value"), p95 = d("p95"), n = (m["n"] as? Number)?.toInt() ?: 0,
                absolute = e("absolute", State.values()) ?: State.UNKNOWN, absoluteBound = d("absolute_bound"), absoluteSource = s("absolute_source"),
                relative = e("relative", State.values()) ?: State.UNKNOWN, baselineValue = d("baseline_value"), deltaPct = d("delta_pct"),
                final = e("final", State.values()) ?: State.UNKNOWN, thresholdBasis = e("threshold_basis", ThresholdBasis.values()),
                cddApplicability = e("cdd_applicability", CddApplicability.values()), conditionEquivalence = e("condition_equivalence", ConditionEquivalence.values()),
                unknownReason = e("unknown_reason", UnknownReason.values())
            )
        }
    }

    fun toJsonMap(): Map<String, Any?> = mapOf(
        "id" to id, "value" to value, "p95" to p95, "n" to n,
        "absolute" to absolute.jsonName, "absolute_bound" to absoluteBound, "absolute_source" to absoluteSource,
        "relative" to relative.jsonName, "baseline_value" to baselineValue, "delta_pct" to deltaPct,
        "final" to final.jsonName,
        "threshold_basis" to thresholdBasis?.jsonName,
        "cdd_applicability" to cddApplicability?.jsonName,
        "condition_equivalence" to conditionEquivalence?.jsonName,
        "unknown_reason" to unknownReason?.jsonName
    )
}

data class Diagnosis(
    val rule: String,
    val causeLayer: CauseLayer,
    val evidence: List<String>,
    val secondaryRules: List<String> = emptyList()
) {
    fun toJsonMap(): Map<String, Any?> = mapOf(
        "rule" to rule, "cause_layer" to causeLayer.jsonName, "evidence" to evidence, "secondary_rules" to secondaryRules
    )
}

data class Health(
    val level: HealthLevelV2,
    val coverage: Double,
    val score: Int?,
    val scoreVisible: Boolean,
    val counts: Map<State, Int>
) {
    fun toJsonMap(): Map<String, Any?> = mapOf(
        "level" to level.jsonName, "coverage" to coverage, "score" to score, "score_visible" to scoreVisible,
        "pass" to (counts[State.PASS] ?: 0), "warn" to (counts[State.WARN] ?: 0),
        "fail" to (counts[State.FAIL] ?: 0), "unknown" to (counts[State.UNKNOWN] ?: 0)
    )
}
