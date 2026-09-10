package dev.halcamera.diagnosis

/**
 * The only place thresholds live (docs/PRODUCT-v0.2.md chapter 6). All numbers are proposals until measured on device.
 * Changing a number here requires changing the document first.
 */
object ThresholdTable {
    const val VERSION = "0.2-draft"

    /** Global noise floor for relative latency judgement: below this absolute delta no WARN is raised. */
    const val NOISE_FLOOR_MS = 10.0

    sealed class Absolute {
        /** No absolute rule; only hard failures (timeouts/errors) apply. */
        object None : Absolute()
        /** Fixed bounds. Exceeding warnAt gives WARN, exceeding failAt (if any) gives FAIL, both with [basis]. */
        data class Bounds(val warnAt: Double, val failAt: Double?, val source: String, val basis: ThresholdBasis) : Absolute()
        /**
         * CDD reference (5.2). The engine decides WARN/FAIL from applicability and condition equivalence.
         * On devices where the CDD does not apply, [heuristicWarnAt]/[heuristicFailAt] are the product bounds:
         * 1.5x and 2x the CDD value (Galaxy S25+, non-MPC, sits at 550-620 ms preview start in every run, so the
         * bare CDD value produced a permanent WARN that meant nothing to the user).
         */
        data class Cdd(val bound: Double, val source: String, val heuristicWarnAt: Double, val heuristicFailAt: Double) : Absolute()
        /** Cadence metric (H.1): compare against the sample's own expectedMs times [tolerance]. */
        data class Cadence(val tolerance: Double, val source: String) : Absolute()
        /** Count metric: 0 PASS, [warnAt]..[failAt) WARN, >= failAt FAIL, all heuristic. */
        data class Count(val warnAt: Int, val failAt: Int?, val source: String, val failBasis: ThresholdBasis = ThresholdBasis.HEURISTIC) : Absolute()
        /** 3A convergence: <= passAt PASS, otherwise WARN (never FAIL in v0.2). */
        data class ConvergeWarnOnly(val passAt: Double, val source: String) : Absolute()
    }

    data class Relative(val warnPct: Double, val failPct: Double?, val noiseFloorMs: Double = NOISE_FLOOR_MS)

    data class Rule(val id: String, val absolute: Absolute, val relative: Relative?, val weight: Double, val group: String)

    private const val CDD_LAUNCH = "cdd_2.2.7.2_H-1-6"
    private const val CDD_JPEG = "cdd_2.2.7.2_H-1-5"
    private const val WATCHDOG = "watchdog_v0.2"
    private const val PHYSICS = "physics"
    private const val STABILITY = "product_stability_v0.2"
    private const val RECORDING = "product_recording_v0.2"

    private val latency = Relative(30.0, 100.0)
    private val slow = Relative(50.0, 200.0)
    /** Open/close are tens of milliseconds and jitter by that much run to run (S25+: 16 ms then 44 ms). A 10 ms floor turns
     *  jitter into a +170 % relative FAIL, so these two use a 50 ms floor. */
    private val tiny = Relative(30.0, 100.0, noiseFloorMs = 50.0)
    private val tinySlow = Relative(50.0, 200.0, noiseFloorMs = 50.0)

    val rules: Map<String, Rule> = listOf(
        // 6.1 Launch. Weight 25 spread over 1.1, 1.2, 1.3, 1.6, 1.8.
        Rule("1.1", Absolute.None, tiny, 5.0, "launch"),
        Rule("1.2", Absolute.None, latency, 5.0, "launch"),
        Rule("1.3", Absolute.None, latency, 5.0, "launch"),
        Rule("1.5", Absolute.None, null, 0.0, "launch"),
        Rule("1.6", Absolute.Cdd(500.0, CDD_LAUNCH, 750.0, 1000.0), latency, 5.0, "launch"),
        Rule("1.7", Absolute.None, tinySlow, 0.0, "launch"),
        Rule("1.8", Absolute.None, latency, 5.0, "launch"),
        // 6.2 Still. Weight 25 over 2.2, 2.3.
        Rule("2.1", Absolute.None, latency, 0.0, "still"),
        Rule("2.2", Absolute.Cdd(1000.0, CDD_JPEG, 1500.0, 2000.0), latency, 12.5, "still"),
        Rule("2.3", Absolute.None, latency, 12.5, "still"),
        Rule("2.4", Absolute.None, Relative(50.0, null), 0.0, "still"),
        Rule("2.5", Absolute.None, latency, 0.0, "still"),
        Rule("2.6", Absolute.None, Relative(50.0, null), 0.0, "still"),
        Rule("2.7", Absolute.Count(1, 3, STABILITY), null, 0.0, "still"),
        // 6.3 Recording. Not run in v0.2 Auto Check; weight 0.
        Rule("3.1", Absolute.None, latency, 0.0, "recording"),
        Rule("3.2", Absolute.Count(1, 3, STABILITY), null, 0.0, "recording"),
        Rule("3.3", Absolute.None, null, 0.0, "recording"),
        Rule("3.4", Absolute.Bounds(95.0, 85.0, RECORDING, ThresholdBasis.HEURISTIC), null, 0.0, "recording"),
        Rule("3.5", Absolute.Bounds(90.0, 75.0, RECORDING, ThresholdBasis.HEURISTIC), null, 0.0, "recording"),
        Rule("3.6", Absolute.None, slow, 0.0, "recording"),
        Rule("3.7", Absolute.None, Relative(50.0, 150.0), 0.0, "recording"),
        // 6.4 Observation. Stability weight 30 over H.1, H.2, H.3, H.4, H.5, H.9; 3A weight 20 over H.6, H.7, H.8.
        Rule("H.1", Absolute.Cadence(1.2, PHYSICS), Relative(20.0, 50.0), 5.0, "stability"),
        Rule("H.2", Absolute.None, latency, 5.0, "stability"),
        Rule("H.3", Absolute.None, latency, 5.0, "stability"),
        Rule("H.4", Absolute.None, latency, 5.0, "stability"),
        Rule("H.5", Absolute.Count(1, 3, STABILITY), null, 5.0, "stability"),
        Rule("H.6", Absolute.ConvergeWarnOnly(1500.0, STABILITY), Relative(50.0, null), 20.0 / 3, "3a"),
        Rule("H.7", Absolute.ConvergeWarnOnly(1500.0, STABILITY), Relative(50.0, null), 20.0 / 3, "3a"),
        Rule("H.8", Absolute.ConvergeWarnOnly(1500.0, STABILITY), Relative(50.0, null), 20.0 / 3, "3a"),
        Rule("H.9", Absolute.Count(1, 1, STABILITY, ThresholdBasis.HARD), null, 5.0, "stability")
    ).associateBy { it.id }

    /** Metrics whose higher value is better (percentages of requested fps). */
    val higherIsBetter = setOf("3.4", "3.5")

    val totalWeight: Double = rules.values.sumOf { it.weight }

    const val WATCHDOG_SOURCE = WATCHDOG
}
