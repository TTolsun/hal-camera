package dev.cameradoctor.benchmark

enum class RuleKind { LATENCY, COUNT }

enum class Direction { LOWER_IS_BETTER, HIGHER_IS_BETTER }

/**
 * One metric's regression rule (docs/PLAN-BenchMarker-v0.3.md 7.2).
 * LATENCY: REGRESSED when delta_pct >= deltaPct and the absolute change >= noiseFloor (ms).
 * COUNT: REGRESSED when the absolute change >= noiseFloor (count); deltaPct is null.
 */
data class RegressionRule(
    val metricId: String,
    val kind: RuleKind,
    val direction: Direction,
    val deltaPct: Double?,
    val noiseFloor: Double
) {
    init {
        require((kind == RuleKind.COUNT) == (deltaPct == null)) { "$metricId: deltaPct must be null exactly for COUNT rules" }
        require(noiseFloor >= 0.0) { "$metricId: noiseFloor must not be negative" }
    }
}

/**
 * The only place regression thresholds live. Values are the initial proposal; any change to any value bumps
 * [VERSION] (7.2). The detector that applies these rules arrives in M4; M1 only fixes the table.
 */
object RegressionRules {
    const val VERSION = "regression-rule-v1"

    private fun latency(id: String, pct: Double, floorMs: Double) =
        RegressionRule(id, RuleKind.LATENCY, Direction.LOWER_IS_BETTER, pct, floorMs)

    private fun count(id: String, floor: Int) =
        RegressionRule(id, RuleKind.COUNT, Direction.LOWER_IS_BETTER, null, floor.toDouble())

    val rules: Map<String, RegressionRule> = listOf(
        latency("1.1", 15.0, 10.0),
        latency("1.3", 15.0, 10.0),
        latency("1.8", 15.0, 10.0),
        latency("1.6", 15.0, 10.0),
        latency("1.2", 15.0, 5.0),
        latency("1.7", 15.0, 5.0),
        latency("2.2", 15.0, 10.0),
        latency("2.3", 15.0, 10.0),
        latency("2.5", 15.0, 10.0),
        latency("H.1", 10.0, 2.0),
        latency("H.2", 10.0, 2.0),
        latency("H.3", 15.0, 5.0),
        latency("H.4", 15.0, 5.0),
        latency("H.10", 20.0, 1.0),
        latency("H.6", 30.0, 200.0),
        latency("H.7", 30.0, 200.0),
        latency("H.8", 30.0, 200.0),
        count("H.5", 2),
        count("2.7", 2),
        count("H.9", 1)
    ).associateBy { it.metricId }

    fun rule(metricId: String): RegressionRule? = rules[metricId]
}
