package dev.halcamera.benchmark.domain

import dev.halcamera.metrics.UnknownReason

/**
 * Condition differences between two otherwise eligible runs (docs/PLAN-BenchMarker-v0.3.md 7.5).
 * Eligibility (5.3) is a property of one run; these are properties of the pair.
 *
 * [blocksAll] mismatches make every metric UNKNOWN(condition_mismatch); [metricIds] limits a mismatch to the
 * metrics it actually distorts. CHARGING_DIFFERS keeps every state and is shown as a banner only.
 */
enum class ConditionMismatch(val blocksAll: Boolean, val metricIds: Set<String> = emptySet()) {
    THERMAL_MAX_DIFFERS(true),
    POWER_SAVE_DIFFERS(true),
    CHARGING_DIFFERS(false),
    EXPOSURE_DIFFERS(false, setOf("H.6", "H.7", "H.8"));

    fun blocks(metricId: String): Boolean = blocksAll || metricId in metricIds
}

/**
 * One metric of the comparison table (7.3). [deltaPct] is filled whenever both values exist, even when the state is
 * UNKNOWN.
 *
 * Against a baseline set, [baselineValue] is the worst value of the set: the edge a regression has to cross, and
 * the value the delta and the result screen's tick stand for. [baselineBest] is the other edge, the one an
 * improvement has to cross. Against a single run the two are the same number.
 */
data class MetricComparison(
    val metricId: String,
    val baselineValue: Double?,
    val currentValue: Double?,
    val deltaPct: Double?,
    val state: RegressionState,
    val unknownReason: UnknownReason?,
    val baselineBest: Double? = baselineValue
)

/**
 * Result of comparing one run against a baseline set or a single reference run. Pure data, recomputed at display
 * time with the current rule version (7.2) rather than read back from the stored run.
 */
data class RunComparison(
    val baseRunIds: List<String>,
    val currentRunId: String,
    val sameContract: Boolean,
    val sameEndpoint: Boolean,
    val identity: BuildIdentityComparison?,
    val conditionMismatches: List<ConditionMismatch>,
    val metrics: List<MetricComparison>,
    val ruleVersion: String = RegressionRules.VERSION
) {
    val regressedCount: Int get() = metrics.count { it.state == RegressionState.REGRESSED }
    val improvedCount: Int get() = metrics.count { it.state == RegressionState.IMPROVED }
    val stableCount: Int get() = metrics.count { it.state == RegressionState.STABLE }
    val unknownCount: Int get() = metrics.count { it.state == RegressionState.UNKNOWN }

    /** The run-wide REGRESSION DETECTED banner (7.2). */
    val hasRegression: Boolean get() = regressedCount >= 1

    /**
     * How many metrics the rules could actually judge. Zero means the two runs were not comparable at all, which
     * is a different statement from "nothing regressed" and has to be shown as one (PR #21 review).
     */
    val judgedCount: Int get() = metrics.count { it.state != RegressionState.UNKNOWN }

    fun metric(id: String): MetricComparison? = metrics.firstOrNull { it.metricId == id }

    companion object {
        /** No baseline: the table still lists the current values, with no state. */
        fun noBaseline(current: BenchmarkRun): RunComparison = RunComparison(
            baseRunIds = emptyList(), currentRunId = current.runId, sameContract = false, sameEndpoint = false,
            identity = null, conditionMismatches = emptyList(),
            metrics = current.metrics.map {
                MetricComparison(it.id, null, it.value, null, RegressionState.UNKNOWN, UnknownReason.NO_BASELINE)
            }
        )
    }
}

/**
 * Applies the regression rule table (7.2) and the pairwise condition rules (7.5) to a run and its baseline set.
 *
 * The whole comparison is one pure function so that every boundary value of the table is testable on the JVM and
 * so that the stored run never has to be trusted: the run JSON keeps measured values, and baseline / reference
 * columns are derived here each time they are shown.
 *
 * Against a set, each metric is judged against the range of the set's values: REGRESSED only when the run is worse
 * than the worst member by the rule's margin, IMPROVED only when it is better than the best member by it (#165).
 * Normal runs of one camera spread wider than a rule's margin (launch alternates between two modes, record stop
 * varies by a third), and a single run as the baseline turned that spread into verdicts. A set of one reduces to
 * the plain pairwise comparison.
 */
object RegressionDetector {

    /** Ratio of exposure_load_p50 beyond which 3A metrics are not comparable (7.5, inherited from v0.2 chapter 6). */
    const val EXPOSURE_MISMATCH_RATIO = 4.0

    /** Two thermal_max values this far apart make the pair incomparable (7.5). */
    const val THERMAL_MAX_STEP_DIFF = 2

    fun compare(
        base: BenchmarkRun?,
        current: BenchmarkRun,
        rules: Map<String, RegressionRule> = RegressionRules.rules
    ): RunComparison = compare(listOfNotNull(base), current, rules)

    fun compare(
        bases: List<BenchmarkRun>,
        current: BenchmarkRun,
        rules: Map<String, RegressionRule> = RegressionRules.rules
    ): RunComparison {
        if (bases.isEmpty()) return RunComparison.noBaseline(current)

        val sameContract = bases.all { it.contract.comparisonContractId == current.contract.comparisonContractId }
        val sameEndpoint = bases.all { it.endpoint.key == current.endpoint.key }
        // A condition that differs from any member differs from the set: judging against the members that happen
        // to match would quietly narrow the range the verdict claims to use.
        val mismatches = bases.flatMap { conditionMismatches(it, current) }.distinct().sorted()
        // A run that may not be compared at all (5.3) cannot produce a trustworthy state, in either position.
        val eligible = current.validity.comparisonEligible && bases.all { it.validity.comparisonEligible }
        val comparable = sameContract && sameEndpoint && eligible

        val ids = (current.metrics.map { it.id } + bases.flatMap { b -> b.metrics.map { it.id } }).distinct()
        val metrics = ids.map { id ->
            compareMetric(id, bases.map { it.metric(id) }, current.metric(id), rules[id], comparable, mismatches)
        }
        return RunComparison(
            baseRunIds = bases.map { it.runId }, currentRunId = current.runId,
            sameContract = sameContract, sameEndpoint = sameEndpoint,
            identity = bases.map { BuildIdentity.compare(BuildIdentity.of(it), BuildIdentity.of(current)) }.reduce(::both),
            conditionMismatches = mismatches, metrics = metrics
        )
    }

    /** "same" against a set means the same as every member; an axis unknown for any member is unknown. */
    private fun both(a: BuildIdentityComparison, b: BuildIdentityComparison): BuildIdentityComparison {
        fun and(x: Boolean?, y: Boolean?): Boolean? = if (x == null || y == null) null else x && y
        return BuildIdentityComparison(
            sameSystemFingerprint = a.sameSystemFingerprint && b.sameSystemFingerprint,
            sameVendorFingerprint = and(a.sameVendorFingerprint, b.sameVendorFingerprint),
            sameCameraInfoVersion = and(a.sameCameraInfoVersion, b.sameCameraInfoVersion),
            sameAppVersion = a.sameAppVersion && b.sameAppVersion,
            sameAppBuild = and(a.sameAppBuild, b.sameAppBuild),
            sameSubjectLabel = and(a.sameSubjectLabel, b.sameSubjectLabel),
            sameSubjectCommit = and(a.sameSubjectCommit, b.sameSubjectCommit)
        )
    }

    /** 7.5. Null on either side means the condition is unknown, and an unknown condition is not a mismatch. */
    fun conditionMismatches(base: BenchmarkRun, current: BenchmarkRun): List<ConditionMismatch> {
        val out = ArrayList<ConditionMismatch>()
        val bt = base.env.thermalMax
        val ct = current.env.thermalMax
        if (bt != null && ct != null && kotlin.math.abs(bt - ct) >= THERMAL_MAX_STEP_DIFF) out += ConditionMismatch.THERMAL_MAX_DIFFERS
        if (differs(base.env.powerSaveMode, current.env.powerSaveMode)) out += ConditionMismatch.POWER_SAVE_DIFFERS
        if (differs(base.env.charging, current.env.charging)) out += ConditionMismatch.CHARGING_DIFFERS
        if (exposureDiffers(exposureLoad(base), exposureLoad(current))) out += ConditionMismatch.EXPOSURE_DIFFERS
        return out
    }

    /** raw.observation.exposure_load_p50 (chapter 6); null when the run was written without it. */
    fun exposureLoad(run: BenchmarkRun): Double? = JsonMaps.d(JsonMaps.map(run.raw["observation"])?.get("exposure_load_p50"))

    private fun exposureDiffers(a: Double?, b: Double?): Boolean {
        if (a == null || b == null || a <= 0.0 || b <= 0.0) return false
        val ratio = a / b
        return ratio > EXPOSURE_MISMATCH_RATIO || ratio < 1.0 / EXPOSURE_MISMATCH_RATIO
    }

    private fun differs(a: Boolean?, b: Boolean?): Boolean = a != null && b != null && a != b

    private fun compareMetric(
        id: String,
        bases: List<BenchmarkMetric?>,
        current: BenchmarkMetric?,
        rule: RegressionRule?,
        comparable: Boolean,
        mismatches: List<ConditionMismatch>
    ): MetricComparison {
        // Members without a value are left out of the range. A metric one member did not measure (a profile
        // change, an unsupported stage) cannot widen it, and leaving it out only makes the range narrower.
        val values = bases.mapNotNull { it?.value }
        val higherIsBetter = rule?.direction == Direction.HIGHER_IS_BETTER
        val worst = if (higherIsBetter) values.minOrNull() else values.maxOrNull()
        val best = if (higherIsBetter) values.maxOrNull() else values.minOrNull()
        val c = current?.value
        val delta = deltaPct(worst, c)

        // A 3A metric that timed out stores the observation window length as its value (plan chapter 13,
        // 2026-09-10). Comparing that number would report a regression against a window, not a convergence.
        val timedOut = bases.any { it?.timeout == true } || current?.timeout == true
        val reason = when {
            worst == null || c == null -> missingReason(bases.firstOrNull { it?.value == null }, current)
            timedOut -> UnknownReason.NOT_MEASURABLE
            !comparable -> UnknownReason.CONDITION_MISMATCH
            mismatches.any { it.blocks(id) } -> UnknownReason.CONDITION_MISMATCH
            rule == null -> UnknownReason.NOT_MEASURABLE
            else -> null
        }
        if (reason != null) return MetricComparison(id, worst, c, delta, RegressionState.UNKNOWN, reason, best)

        val againstWorst = state(rule!!, worst!!, c!!)
        val stateInRange = when {
            againstWorst == RegressionState.REGRESSED -> RegressionState.REGRESSED
            state(rule, best!!, c) == RegressionState.IMPROVED -> RegressionState.IMPROVED
            else -> RegressionState.STABLE
        }
        return MetricComparison(id, worst, c, delta, stateInRange, null, best)
    }

    /**
     * Why a metric has no value. Only the side that is actually missing can explain it: every metric is written
     * with `NO_BASELINE` as its stored default before any comparison ran (BenchmarkEvaluator), so a present
     * value's reason says nothing, and reading it would report "no baseline" for a run that has one.
     */
    private fun missingReason(base: BenchmarkMetric?, current: BenchmarkMetric?): UnknownReason {
        fun reasonOf(m: BenchmarkMetric?): UnknownReason? =
            m?.takeIf { it.value == null }?.unknownReason?.takeIf { it != UnknownReason.NO_BASELINE }
        return reasonOf(current) ?: reasonOf(base) ?: UnknownReason.NOT_RUN
    }

    /** (current − baseline) / baseline × 100. Null when a value is missing or the baseline is zero. */
    fun deltaPct(baseline: Double?, current: Double?): Double? {
        if (baseline == null || current == null || baseline == 0.0) return null
        return (current - baseline) / baseline * 100.0
    }

    /**
     * 7.2. LATENCY needs both the relative and the absolute change; COUNT uses the absolute change only.
     * A zero baseline has no percentage, so a LATENCY metric falls back to the absolute rule rather than
     * reporting STABLE for a change it cannot express as a ratio.
     */
    fun state(rule: RegressionRule, baseline: Double, current: Double): RegressionState {
        val worse = if (rule.direction == Direction.LOWER_IS_BETTER) current - baseline else baseline - current
        val better = -worse
        if (rule.kind == RuleKind.COUNT || baseline == 0.0) {
            return when {
                worse >= rule.noiseFloor -> RegressionState.REGRESSED
                better >= rule.noiseFloor -> RegressionState.IMPROVED
                else -> RegressionState.STABLE
            }
        }
        val pct = rule.deltaPct ?: return RegressionState.STABLE
        val deltaPct = (current - baseline) / baseline * 100.0
        val worsePct = if (rule.direction == Direction.LOWER_IS_BETTER) deltaPct else -deltaPct
        return when {
            worsePct >= pct && worse >= rule.noiseFloor -> RegressionState.REGRESSED
            worsePct <= -pct && better >= rule.noiseFloor -> RegressionState.IMPROVED
            else -> RegressionState.STABLE
        }
    }
}
