package dev.cameradoctor.diagnosis

import dev.cameradoctor.diagnosis.ThresholdTable.Absolute

/**
 * Turns measured samples into MetricStates using ThresholdTable, a device baseline and the device context.
 * Pure Kotlin. Rules: docs/PRODUCT-v0.2.md chapter 5 and 6.
 */
class ThresholdEngine(private val table: Map<String, ThresholdTable.Rule> = ThresholdTable.rules) {

    fun evaluate(samples: List<MetricSample>, baseline: Map<String, BaselineValue>?, device: DeviceContext): List<MetricState> =
        samples.map { evaluate(it, baseline?.get(it.id), device) }

    fun evaluate(sample: MetricSample, baseline: BaselineValue?, device: DeviceContext): MetricState {
        val rule = table[sample.id] ?: return unknown(sample, UnknownReason.NOT_MEASURABLE, null, device)
        val cdd = rule.absolute as? Absolute.Cdd
        val applicability = cdd?.let { device.cddApplicability }
        val equivalence = cdd?.let { device.conditionEquivalence }

        // 5.6 hard failures ignore every threshold.
        if (sample.hardFailure) {
            return MetricState(sample.id, sample.value, sample.p95, sample.n,
                absolute = State.FAIL, absoluteBound = null, absoluteSource = ThresholdTable.WATCHDOG_SOURCE,
                relative = State.UNKNOWN, baselineValue = baseline?.value, deltaPct = null,
                final = State.FAIL, thresholdBasis = ThresholdBasis.HARD,
                cddApplicability = applicability, conditionEquivalence = equivalence, unknownReason = null)
        }
        sample.unknownReason?.let { return unknown(sample, it, rule, device) }
        val value = sample.value ?: return unknown(sample, UnknownReason.NOT_MEASURABLE, rule, device)

        val abs = judgeAbsolute(rule, sample, value, device)
        val rel = judgeRelative(rule, sample, value, baseline)

        var final = worstOf(abs.state, rel.state)
        var basis: ThresholdBasis? = when {
            final == State.UNKNOWN || final == State.PASS -> null
            abs.state == final -> abs.basis
            else -> rel.basis
        }
        var reason: UnknownReason? = null
        if (final == State.UNKNOWN) {
            reason = when {
                abs.unknownReason != null && rel.state == State.UNKNOWN && rule.relative == null -> abs.unknownReason
                rule.relative != null && baseline == null -> UnknownReason.NO_BASELINE
                abs.unknownReason != null -> abs.unknownReason
                else -> UnknownReason.NOT_MEASURABLE
            }
            basis = null
        }
        return MetricState(sample.id, value, sample.p95, sample.n,
            absolute = abs.state, absoluteBound = abs.bound, absoluteSource = abs.source,
            relative = rel.state, baselineValue = baseline?.value, deltaPct = rel.deltaPct,
            final = final, thresholdBasis = basis,
            cddApplicability = applicability, conditionEquivalence = equivalence, unknownReason = reason)
    }

    private data class Abs(val state: State, val bound: Double?, val source: String?, val basis: ThresholdBasis?, val unknownReason: UnknownReason? = null)
    private data class Rel(val state: State, val deltaPct: Double?, val basis: ThresholdBasis?)

    private fun judgeAbsolute(rule: ThresholdTable.Rule, sample: MetricSample, value: Double, device: DeviceContext): Abs =
        when (val a = rule.absolute) {
            Absolute.None -> Abs(State.UNKNOWN, null, null, null, UnknownReason.NOT_MEASURABLE)
            is Absolute.Bounds -> {
                val higher = rule.id in ThresholdTable.higherIsBetter
                val fail = a.failAt != null && (if (higher) value < a.failAt else value >= a.failAt)
                val warn = if (higher) value < a.warnAt else value >= a.warnAt
                when {
                    fail -> Abs(State.FAIL, a.failAt, a.source, a.basis)
                    warn -> Abs(State.WARN, a.warnAt, a.source, a.basis)
                    else -> Abs(State.PASS, a.warnAt, a.source, null)
                }
            }
            is Absolute.Cdd -> judgeCdd(a, value, device)
            is Absolute.Cadence -> {
                val expected = sample.expectedMs
                if (expected == null) Abs(State.UNKNOWN, null, a.source, null, UnknownReason.CADENCE_CHANGED)
                else if (value > expected * a.tolerance) Abs(State.WARN, expected * a.tolerance, a.source, ThresholdBasis.HEURISTIC)
                else Abs(State.PASS, expected * a.tolerance, a.source, null)
            }
            is Absolute.Count -> {
                val count = value.toInt()
                when {
                    a.failAt != null && count >= a.failAt -> Abs(State.FAIL, a.failAt.toDouble(), a.source, a.failBasis)
                    count >= a.warnAt -> Abs(State.WARN, a.warnAt.toDouble(), a.source, ThresholdBasis.HEURISTIC)
                    else -> Abs(State.PASS, a.warnAt.toDouble(), a.source, null)
                }
            }
            is Absolute.ConvergeWarnOnly ->
                if (sample.timeout || value > a.passAt) Abs(State.WARN, a.passAt, a.source, ThresholdBasis.HEURISTIC)
                else Abs(State.PASS, a.passAt, a.source, null)
        }

    /** 5.2: CDD bound is a validated FAIL only under equivalent conditions; a WARN reference otherwise; heuristic when not applicable. */
    private fun judgeCdd(a: Absolute.Cdd, value: Double, device: DeviceContext): Abs {
        val over = value >= a.bound
        return if (device.cddApplicability == CddApplicability.APPLICABLE) {
            when {
                !over -> Abs(State.PASS, a.bound, a.source, null)
                device.conditionEquivalence == ConditionEquivalence.EQUIVALENT -> Abs(State.FAIL, a.bound, a.source, ThresholdBasis.ABSOLUTE_VALIDATED)
                else -> Abs(State.WARN, a.bound, a.source, ThresholdBasis.ABSOLUTE_REFERENCE)
            }
        } else {
            when {
                value >= a.heuristicFailAt -> Abs(State.FAIL, a.heuristicFailAt, a.source, ThresholdBasis.HEURISTIC)
                over -> Abs(State.WARN, a.bound, a.source, ThresholdBasis.HEURISTIC)
                else -> Abs(State.PASS, a.bound, a.source, null)
            }
        }
    }

    private fun judgeRelative(rule: ThresholdTable.Rule, sample: MetricSample, value: Double, baseline: BaselineValue?): Rel {
        val r = rule.relative ?: return Rel(State.UNKNOWN, null, null)
        if (baseline == null || baseline.value <= 0.0) return Rel(State.UNKNOWN, null, null)
        val delta = value - baseline.value
        val pct = delta / baseline.value * 100.0
        if (delta < r.noiseFloorMs) return Rel(State.PASS, pct, null)
        return when {
            r.failPct != null && pct >= r.failPct -> Rel(State.FAIL, pct, ThresholdBasis.RELATIVE)
            pct >= r.warnPct -> Rel(State.WARN, pct, ThresholdBasis.RELATIVE)
            else -> Rel(State.PASS, pct, null)
        }
    }

    private fun unknown(sample: MetricSample, reason: UnknownReason, rule: ThresholdTable.Rule?, device: DeviceContext): MetricState {
        val cdd = rule?.absolute as? Absolute.Cdd
        return MetricState(sample.id, sample.value, sample.p95, sample.n,
            absolute = State.UNKNOWN, absoluteBound = null, absoluteSource = null,
            relative = State.UNKNOWN, baselineValue = null, deltaPct = null,
            final = State.UNKNOWN, thresholdBasis = null,
            cddApplicability = cdd?.let { device.cddApplicability }, conditionEquivalence = cdd?.let { device.conditionEquivalence },
            unknownReason = reason)
    }
}
