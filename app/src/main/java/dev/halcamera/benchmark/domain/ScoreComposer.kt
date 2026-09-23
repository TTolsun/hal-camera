package dev.halcamera.benchmark.domain

import kotlin.math.roundToInt

/** A provisional within-endpoint scale, not a cross-device ranking. See docs/SCORING.md. */
data class ScoreCurve(val metricId: String, val center: Double, val scale: Double)

data class ScoreCalibration(
    val manufacturer: String,
    val model: String,
    val endpointKey: String,
    val contractId: String,
    val normalRunIds: List<String>,
    val curves: List<ScoreCurve>
)

data class EndpointScore(
    val total: Int,
    val categories: Map<Category, Double>,
    val metrics: Map<String, Double>
)

object ScoreComposer {
    const val VERSION = "score-v1-draft"
    const val MIN_NORMAL_RUNS = 10

    // Fixed scoring floors, independent of later changes to the regression rule table.
    // 2.7 is not measured by this profile; 3A, RECORD, RESOURCE and SWITCH have zero weight.
    //
    // RECORD carries no floor and is absent from [categories] on purpose: that is what a weight of zero means
    // here, the same way 3A has had it since the draft. The recording metrics enter the score only once the
    // sensitivity work of issue #123 has a calibration for them (docs/PLAN-Recording-v0.1.md 3.3), so a v2 run
    // and a v1 run are scored on the same sixteen metrics and their totals stay on one axis.
    val floors = linkedMapOf(
        "1.1" to 10.0, "1.2" to 5.0, "1.3" to 10.0, "1.8" to 10.0,
        "1.6" to 10.0, "1.7" to 5.0,
        "H.1" to 2.0, "H.2" to 2.0, "H.3" to 5.0, "H.4" to 5.0, "H.10" to 1.0,
        "2.2" to 10.0, "2.3" to 10.0, "2.5" to 10.0,
        "H.5" to 2.0, "H.9" to 1.0
    ).toMap()
    val categories = listOf(Category.LAUNCH, Category.PREVIEW, Category.CAPTURE, Category.STABILITY)

    /** Caller must curate normal scenes: eligibility alone cannot classify lighting or contention. */
    fun calibrate(normalRuns: List<BenchmarkRun>): ScoreCalibration {
        require(normalRuns.size >= MIN_NORMAL_RUNS) { "At least ten normal runs are required" }
        require(normalRuns.all { it.runId.isNotBlank() }) { "Missing run id" }
        require(normalRuns.map { it.runId }.distinct().size == normalRuns.size) { "Duplicate run ids" }
        val first = normalRuns.first()
        require(normalRuns.all { trainingEligible(it) && sameEndpoint(first, it) }) {
            "Calibration requires eligible release runs of one device, endpoint and contract"
        }
        val curves = floors.map { (id, floor) ->
            val values = normalRuns.map { run ->
                require(run.metrics.count { it.id == id } == 1) { "Missing or duplicate metric $id" }
                requireNotNull(value(run.metric(id), id)) { "Unmeasured metric $id" }
            }.sorted()
            val center = median(values)
            val q1 = values[(values.size - 1) / 4]
            val q3 = values[(values.size - 1) * 3 / 4]
            ScoreCurve(id, center, maxOf(q3 - q1, center * 0.15, floor))
        }
        return ScoreCalibration(first.device.manufacturer, first.device.model, first.endpoint.key,
            first.contract.comparisonContractId, normalRuns.map { it.runId }.sorted(), curves)
    }

    /** Production score: ineligible or unsupported runs never receive even a partial endpoint score. */
    fun compose(run: BenchmarkRun, calibration: ScoreCalibration): EndpointScore? =
        if (trainingEligible(run)) evaluate(run, calibration) else null

    /** Offline sensitivity analysis only. The result must not be written as an eligible run score. */
    fun sensitivity(run: BenchmarkRun, calibration: ScoreCalibration): EndpointScore? {
        val validity = RunValidity.fromJsonMap(run.validity.toJsonMap())
        if (!validity.measurementValid || validity.unknownFlags.isNotEmpty() || run.aborted != null) return null
        return evaluate(run, calibration)
    }

    fun apply(run: BenchmarkRun, calibration: ScoreCalibration): BenchmarkRun {
        val score = compose(run, calibration)
        return run.copy(
            scoringRuleVersion = score?.let { VERSION },
            endpointScore = score?.total,
            metrics = run.metrics.map { it.copy(score = score?.metrics?.get(it.id)) }
        )
    }

    private fun evaluate(run: BenchmarkRun, calibration: ScoreCalibration): EndpointScore? {
        if (!validCalibration(calibration) || run.app.debuggable != false ||
            !run.compatibility.supported || run.contract != MeasurementContract.forProfile(run.profile) ||
            run.device.manufacturer != calibration.manufacturer || run.device.model != calibration.model ||
            run.endpoint.key != calibration.endpointKey ||
            run.contract.comparisonContractId != calibration.contractId ||
            !scorable(run.profile)) return null
        if (run.metrics.map { it.id }.distinct().size != run.metrics.size) return null
        val scores = linkedMapOf<String, Double>()
        for (curve in calibration.curves) {
            val current = value(run.metric(curve.metricId), curve.metricId) ?: return null
            // All included metrics are lower-is-better. A normal median maps to 800;
            // one robust scale of degradation costs 200 points. This is a draft convention.
            scores[curve.metricId] = (800.0 - 200.0 * ((current - curve.center) / curve.scale)).coerceIn(0.0, 1000.0)
        }
        val grouped = categories.associateWith { category ->
            scores.filterKeys { BenchmarkMetricCatalog.info(it)?.category == category }.values.average()
        }
        return EndpointScore(grouped.values.average().roundToInt().coerceIn(0, 1000), grouped, scores)
    }

    /**
     * A confirmed profile of this app. Both canonical profiles are scorable on the same sixteen metrics, but a
     * calibration still only ever scores runs of the profile it was learned on: the contract id carries the
     * profile id, so a v1 calibration never scores a v2 run and a device needs a fresh calibration after the
     * switch, exactly as it needs a fresh baseline.
     */
    private fun scorable(profile: BenchmarkProfile): Boolean =
        !profile.isDraft && BenchmarkProfile.canonical(profile.id) == profile

    private fun validCalibration(c: ScoreCalibration): Boolean =
        c.manufacturer.isNotBlank() && c.model.isNotBlank() && c.endpointKey.isNotBlank() &&
            c.contractId in BenchmarkProfile.CANONICAL.values.map { MeasurementContract.forProfile(it).comparisonContractId } &&
            c.normalRunIds.size >= MIN_NORMAL_RUNS && c.normalRunIds.all { it.isNotBlank() } &&
            c.normalRunIds.distinct().size == c.normalRunIds.size &&
            c.curves.size == floors.size && c.curves.map { it.metricId }.toSet() == floors.keys &&
            c.curves.all { it.center.isFinite() && it.center >= 0 && it.scale.isFinite() && it.scale >= floors.getValue(it.metricId) }

    private fun value(metric: BenchmarkMetric?, id: String): Double? {
        val info = BenchmarkMetricCatalog.info(id) ?: return null
        if (metric == null || metric.timeout || metric.category != info.category || metric.unit != info.unit || metric.sampleCount <= 0) return null
        return metric.value?.takeIf { it.isFinite() && it >= 0 }
    }

    private fun trainingEligible(run: BenchmarkRun): Boolean =
        RunValidity.fromJsonMap(run.validity.toJsonMap()).scoringEligible &&
            run.aborted == null && run.compatibility.supported && run.app.debuggable == false &&
            scorable(run.profile) &&
            run.contract == MeasurementContract.forProfile(run.profile) &&
            run.env.charging == false && run.env.powerSaveMode == false &&
            run.env.batteryStart?.let { it >= ValidityFlags.BATTERY_LOW_PCT } == true &&
            listOf(run.env.thermalStart, run.env.thermalMax, run.env.thermalEnd).all {
                it != null && it in 0 until ValidityFlags.THERMAL_MODERATE
            }

    private fun sameEndpoint(a: BenchmarkRun, b: BenchmarkRun): Boolean =
        a.device == b.device && a.app == b.app && a.endpoint.key == b.endpoint.key && a.contract == b.contract

    private fun median(sorted: List<Double>): Double =
        if (sorted.size % 2 == 0) sorted[sorted.size / 2 - 1] / 2 + sorted[sorted.size / 2] / 2 else sorted[sorted.size / 2]
}
