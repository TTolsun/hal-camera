package dev.cameradoctor.check

import dev.cameradoctor.diagnosis.BaselineValue
import dev.cameradoctor.diagnosis.ConditionEquivalence
import dev.cameradoctor.diagnosis.DeviceContext
import dev.cameradoctor.diagnosis.Diagnosis
import dev.cameradoctor.diagnosis.DiagnosisRules
import dev.cameradoctor.diagnosis.Health
import dev.cameradoctor.diagnosis.HealthComposer
import dev.cameradoctor.diagnosis.HealthLevelV2
import dev.cameradoctor.diagnosis.MetricExtractor
import dev.cameradoctor.diagnosis.MetricSample
import dev.cameradoctor.diagnosis.MetricState
import dev.cameradoctor.diagnosis.State
import dev.cameradoctor.diagnosis.ThresholdEngine
import dev.cameradoctor.diagnosis.UnknownReason
import dev.cameradoctor.telemetry.Event

/**
 * Turns one endpoint's AutoCheck result plus its telemetry events into metric states, diagnosis and health.
 * Pure Kotlin so the whole chapter 4 to 7 pipeline is testable without a device.
 */
class CheckEvaluator(
    private val engine: ThresholdEngine = ThresholdEngine(),
    private val rules: DiagnosisRules = DiagnosisRules(),
    private val composer: HealthComposer = HealthComposer(),
    private val extractor: MetricExtractor = MetricExtractor()
) {
    data class EndpointEvaluation(
        val endpoint: CameraEndpoint,
        val states: List<MetricState>,
        val diagnosis: Diagnosis,
        val health: Health,
        val observation: MetricExtractor.Observation?,
        val baselineUsed: Boolean,
        val baselineCandidate: Map<String, BaselineValue>?
    )

    companion object {
        /** Metrics defined in the table but not run by the v0.2 Auto Check (4.3). */
        val NOT_RUN = listOf("2.1", "2.4", "2.6", "2.7", "3.1", "3.2", "3.3", "3.4", "3.5", "3.6", "3.7")
    }

    fun evaluate(
        result: AutoCheckRunner.EndpointResult,
        events: List<Event>,
        baseline: Map<String, BaselineValue>?,
        mediaPerformanceClass: Int,
        fixedFpsExpectedMs: Double? = null
    ): EndpointEvaluation {
        val obs = if (result.observeStartNs != null && result.observeEndNs != null)
            extractor.observe(events, result.session, result.observeStartNs, result.observeEndNs, baseline?.get("H.1")?.value,
                fixedFpsExpectedMs = fixedFpsExpectedMs) else null
        val samples = mutableListOf<MetricSample>()
        samples += result.launchSamples()
        samples += obs?.samples ?: listOf("H.1", "H.2", "H.3", "H.4", "H.5", "H.6", "H.7", "H.8").map {
            MetricSample(it, null, unknownReason = UnknownReason.NOT_RUN)
        }
        samples += if (result.observeStartNs != null && result.observeEndNs != null)
            extractor.failureSample(events, result.session, result.observeStartNs, result.observeEndNs)
        else MetricSample("H.9", null, unknownReason = UnknownReason.NOT_RUN)
        samples += NOT_RUN.map { MetricSample(it, null, unknownReason = UnknownReason.NOT_RUN) }
        samples += MetricSample("1.5", null, unknownReason = UnknownReason.NOT_MEASURABLE)

        val device = DeviceContext(mediaPerformanceClass, result.endpoint.primary, ConditionEquivalence.SIMILAR)
        val states = engine.evaluate(samples, baseline, device)
        val diagnosis = rules.diagnose(states, DiagnosisRules.Context(
            cadenceChanged = false, threeAStable = obs?.threeAStable ?: true,
            insufficient = states.count { it.final != State.UNKNOWN } == 0))
        val health = composer.compose(states, hasBaseline = baseline != null)

        // 5.3: a run qualifies as baseline when nothing hard failed and every H metric had enough samples.
        val hNotInsufficient = states.filter { it.id.startsWith("H.") }.none { it.unknownReason == UnknownReason.INSUFFICIENT_SAMPLES }
        val candidate = if (result.hardFailure == null && obs != null && hNotInsufficient)
            states.filter { it.value != null }.associate { it.id to BaselineValue(it.value!!, it.p95) } else null
        return EndpointEvaluation(result.endpoint, states, diagnosis, health, obs, baseline != null, candidate)
    }

    /** Device-level composite is the worst endpoint (7.1). */
    fun overall(evaluations: List<EndpointEvaluation>): HealthLevelV2 {
        val order = listOf(HealthLevelV2.ISSUE, HealthLevelV2.WARNING, HealthLevelV2.INSUFFICIENT, HealthLevelV2.NORMAL)
        return order.firstOrNull { level -> evaluations.any { it.health.level == level } } ?: HealthLevelV2.INSUFFICIENT
    }
}
