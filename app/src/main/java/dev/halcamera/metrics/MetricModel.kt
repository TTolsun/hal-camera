package dev.halcamera.metrics

import java.util.Locale

/**
 * What survives of the Doctor-era model after M3. The verdict vocabulary is gone with the code that produced it:
 * State, ThresholdBasis, CddApplicability, ConditionEquivalence, HealthLevelV2, CauseLayer, MetricState, Diagnosis
 * and Health all described a judgement the app no longer makes, and the BenchMarker judges a delta against a
 * baseline instead (docs/PLAN-BenchMarker-v0.3.md 2.2).
 *
 * The three declarations left are measurement, not judgement, and the benchmark package reads all of them.
 */
enum class UnknownReason {
    NOT_MEASURABLE, NOT_RUN, INSUFFICIENT_SAMPLES, NO_BASELINE,
    UNSUPPORTED, CADENCE_CHANGED, CONDITION_MISMATCH
}

val Enum<*>.jsonName: String get() = name.lowercase(Locale.US)

/**
 * One measured metric before any comparison. Produced by MetricExtractor (H.x) or BenchmarkRunner (1.x / 2.x).
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
