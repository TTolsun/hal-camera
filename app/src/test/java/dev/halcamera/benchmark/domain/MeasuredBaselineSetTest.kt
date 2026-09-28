package dev.halcamera.benchmark.domain

import dev.halcamera.benchmark.domain.BenchmarkRunFixture.metric
import dev.halcamera.benchmark.domain.BenchmarkRunFixture.run
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Measured S25+ runs judged against every baseline set of normal runs (#165).
 *
 * Each TSV holds, per run, the condition, the source file's SHA-256, the pairwise conditions of 7.5 and the value of
 * every compared metric; 1.9 and 1.10 are read off 1.1 as [LaunchTail] does. A 3A value ending in `!timeout` timed
 * out. What these tests pin is how the design behaves on real runs, so a later change to the rules or to the set
 * comparison shows up as a named difference (docs/validation/baseline-set-20260928.md).
 */
internal object MeasuredRuns {

    data class Row(val condition: String, val run: BenchmarkRun)

    data class Tally(val pairs: Int, val degraded: Int, val noVerdict: Int, val metrics: Map<String, Int>)

    fun load(resource: String): List<Row> {
        val lines = javaClass.classLoader!!.getResource(resource)!!.readText().lines().filter { it.isNotBlank() }
        val metricIds = lines.first().split('\t').drop(8)
        return lines.drop(1).map { line ->
            val c = line.split('\t')
            val metrics = metricIds.mapIndexed { i, id ->
                val cell = c[8 + i]
                metric(id, cell.removeSuffix("!timeout").toDoubleOrNull(), timeout = cell.endsWith("!timeout"))
            }
            Row(c[0], run(
                runId = c[1], metrics = metrics,
                thermalMax = c[3].toInt(), charging = c[4].toBoolean(), powerSaveMode = c[5].toBoolean(),
                exposureLoad = c[6].toDoubleOrNull(),
                flags = c[7].takeIf { it != "-" }?.split(',')?.map { ValidityFlags.byCode(it)!! }.orEmpty()
            ))
        }
    }

    /** Every set of [size] normal runs against every run outside it, by condition. */
    fun tally(rows: List<Row>, size: Int): Map<String, Tally> {
        val sets = combinations(rows.filter { it.condition == "normal" }.map { it.run }, size)
        return rows.groupBy { it.condition }.mapValues { (_, group) ->
            var pairs = 0; var degraded = 0; var noVerdict = 0
            val metrics = sortedMapOf<String, Int>()
            for (set in sets) for (row in group) {
                if (row.run in set) continue
                val c = RegressionDetector.compare(set, row.run)
                pairs++
                if (c.judgedCount == 0) noVerdict++
                if (c.hasRegression) degraded++
                c.metrics.filter { it.state == RegressionState.REGRESSED }.forEach { metrics.merge(it.metricId, 1, Int::plus) }
            }
            Tally(pairs, degraded, noVerdict, metrics)
        }
    }

    private fun <T> combinations(xs: List<T>, k: Int): List<List<T>> =
        if (k == 0) listOf(emptyList()) else xs.indices.flatMap { i -> combinations(xs.drop(i + 1), k - 1).map { listOf(xs[i]) + it } }
}

/**
 * The #123 runs (2026-09-26, 0.13.2, camera 0), used to choose between candidate designs. No threshold was tuned to
 * them: every rule keeps its v2 value, and 1.9 and 1.10 use the rule of 1.1.
 */
class MeasuredBaselineSetTest {

    private val rows by lazy { MeasuredRuns.load("baseline-set-20260926.tsv") }
    private val tally by lazy { MeasuredRuns.tally(rows, 3) }

    @Test fun theRunsAreTheFortyOfIssue123() {
        assertEquals(
            mapOf("normal" to 10, "control-day2" to 6, "lowlight2" to 6, "thermal1" to 6, "thermal2" to 6, "contention" to 6),
            rows.groupingBy { it.condition }.eachCount()
        )
    }

    @Test fun normalRunsAreNoLongerDegradedByTheLaunchModeOrRecordStop() {
        // Against a single normal run: 30 of 90 normal pairs and 17 of 60 day-2 pairs were degraded (#165).
        // Every remaining normal case is one run whose record start (3.1) lies beyond the other nine.
        assertEquals(MeasuredRuns.Tally(840, 84, 0, mapOf("3.1" to 84)), tally.getValue("normal"))
        assertEquals(MeasuredRuns.Tally(720, 0, 0, emptyMap()), tally.getValue("control-day2"))
    }

    @Test fun contentionAndThermalAreCaughtByTheEndsOfOpen() {
        // A camera held by another app: every first open is far beyond the normal range.
        assertEquals(720, tally.getValue("contention").degraded)
        assertEquals(720, tally.getValue("contention").metrics["1.9"])
        // Thermal LIGHT: three of six runs spike an open to 260–296 ms.
        assertEquals(MeasuredRuns.Tally(720, 360, 0, mapOf("1.10" to 360)), tally.getValue("thermal1"))
        // Thermal MODERATE runs are not comparison-eligible, so no set gives them a verdict.
        assertEquals(720, tally.getValue("thermal2").noVerdict)
        assertEquals(0, tally.getValue("thermal2").degraded)
    }

    @Test fun lowLightStaysAKnownGap() {
        // Low light moves record start and record stop a little, no more than normal runs do among themselves.
        assertEquals(MeasuredRuns.Tally(720, 114, 0, mapOf("3.1" to 84, "3.6" to 30)), tally.getValue("lowlight2"))
    }
}

/**
 * The confirming measurement (2026-09-28/29, PR build versionCode 590, camera 0, one dim indoor scene, unplugged).
 * Taken after the design was chosen; its two findings made 3A informational and raised the recommended set size to
 * five. Normal runs were measured at 01:07–01:30 and the control runs at 23:21–23:33 the same day.
 */
class MeasuredBaselineSet20260928Test {

    private val rows by lazy { MeasuredRuns.load("baseline-set-20260928.tsv") }

    @Test fun theRunsAreTheConfirmingMeasurement() {
        assertEquals(
            mapOf("normal" to 10, "contention" to 6, "control" to 6, "lowlight" to 6, "thermal1" to 3),
            rows.groupingBy { it.condition }.eachCount()
        )
    }

    @Test fun setsOfFiveKeepNormalRunsCleanAndCatchContention() {
        val t = MeasuredRuns.tally(rows, 5)
        // Record start (3.1) alternates between ~186 ms and ~217 ms; a set without the slow value flags it.
        assertEquals(MeasuredRuns.Tally(1260, 42, 0, mapOf("3.1" to 42)), t.getValue("normal"))
        assertEquals(MeasuredRuns.Tally(1512, 22, 0, mapOf("1.10" to 1, "3.1" to 21)), t.getValue("control"))
        assertEquals(1512, t.getValue("contention").degraded)
        assertEquals(1512, t.getValue("contention").metrics["1.9"])
        assertEquals(MeasuredRuns.Tally(1512, 23, 0, mapOf("2.5" to 2, "3.1" to 21)), t.getValue("lowlight"))
    }

    @Test fun setsOfThreeFlagMoreNormalRuns() {
        val t = MeasuredRuns.tally(rows, 3)
        assertEquals(MeasuredRuns.Tally(840, 70, 0, mapOf("3.1" to 70)), t.getValue("normal"))
        assertEquals(MeasuredRuns.Tally(720, 45, 0, mapOf("1.10" to 10, "3.1" to 35)), t.getValue("control"))
    }

    @Test fun threeAGivesNoVerdictAndTheHeatedRunsWereNotComparable() {
        val t = MeasuredRuns.tally(rows, 5)
        // AE took 353–1413 ms across the ten normal runs; judged, it flagged a third of normal pairs.
        t.values.forEach { tally -> listOf("H.6", "H.7", "H.8").forEach { assertEquals(null, tally.metrics[it]) } }
        // Two of the three heated runs aborted; the third showed no open spike. Thermal is left unanswered.
        assertEquals(MeasuredRuns.Tally(756, 0, 504, emptyMap()), t.getValue("thermal1"))
    }
}
