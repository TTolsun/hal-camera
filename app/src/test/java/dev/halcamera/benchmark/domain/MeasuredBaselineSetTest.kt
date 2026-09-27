package dev.halcamera.benchmark.domain

import dev.halcamera.benchmark.domain.BenchmarkRunFixture.metric
import dev.halcamera.benchmark.domain.BenchmarkRunFixture.run
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The #123 S25+ runs (2026-09-26, 0.13.2, camera 0) judged against every baseline set of three normal runs (#165).
 *
 * These 40 runs were used to choose between candidate designs, not to tune a threshold: every rule keeps its v2
 * value, and 1.9 and 1.10 use the rule of 1.1. What this test pins is how the chosen design behaves on them, so a
 * later change to the rules or to the set comparison shows up as a named difference. Whether the design holds is
 * decided by a new measurement (docs/validation/baseline-set-20260928.md).
 *
 * `baseline-set-20260926.tsv` holds, per run, the condition, the source file's SHA-256, the pairwise conditions of
 * 7.5 and the value of every compared metric; 1.9 and 1.10 were read off 1.1 as [LaunchTail] does. A 3A value
 * ending in `!timeout` timed out. Thermal MODERATE runs are not comparable and are counted as no verdict.
 */
class MeasuredBaselineSetTest {

    private data class Row(val condition: String, val run: BenchmarkRun)

    private val rows: List<Row> by lazy {
        val lines = javaClass.classLoader!!.getResource("baseline-set-20260926.tsv")!!.readText().lines().filter { it.isNotBlank() }
        val header = lines.first().split('\t')
        val metricIds = header.drop(8)
        lines.drop(1).map { line ->
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

    private data class Tally(val pairs: Int, val degraded: Int, val noVerdict: Int, val metrics: Map<String, Int>)

    /** Every set of three normal runs against every run outside it. */
    private fun tally(): Map<String, Tally> {
        val normals = rows.filter { it.condition == "normal" }.map { it.run }
        val sets = combinations(normals, 3)
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

    @Test fun theRunsAreTheFortyOfIssue123() {
        assertEquals(
            mapOf("normal" to 10, "control-day2" to 6, "lowlight2" to 6, "thermal1" to 6, "thermal2" to 6, "contention" to 6),
            rows.groupingBy { it.condition }.eachCount()
        )
    }

    @Test fun normalRunsAreNoLongerDegradedByTheLaunchModeOrRecordStop() {
        val t = tally()
        // Against a single normal run: 30 of 90 normal pairs and 17 of 60 day-2 pairs were degraded (#165).
        // Every remaining normal case is one run whose record start (3.1) lies beyond the other nine.
        assertEquals(Tally(840, 84, 0, mapOf("3.1" to 84)), t.getValue("normal"))
        assertEquals(Tally(720, 0, 0, emptyMap()), t.getValue("control-day2"))
    }

    @Test fun contentionAndThermalAreCaughtByTheEndsOfOpen() {
        val t = tally()
        // A camera held by another app: every first open is far beyond the normal range.
        assertEquals(720, t.getValue("contention").degraded)
        assertEquals(720, t.getValue("contention").metrics["1.9"])
        // Thermal LIGHT: three of six runs spike an open to 260–296 ms.
        assertEquals(Tally(720, 360, 0, mapOf("1.10" to 360)), t.getValue("thermal1"))
        // Thermal MODERATE runs are not comparison-eligible, so no set gives them a verdict.
        assertEquals(720, t.getValue("thermal2").noVerdict)
        assertEquals(0, t.getValue("thermal2").degraded)
    }

    @Test fun lowLightStaysAKnownGap() {
        // Low light moves record start and record stop a little, no more than normal runs do among themselves.
        assertEquals(Tally(720, 114, 0, mapOf("3.1" to 84, "3.6" to 30)), tally().getValue("lowlight2"))
    }
}
