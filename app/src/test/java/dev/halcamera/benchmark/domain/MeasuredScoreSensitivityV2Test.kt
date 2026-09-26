package dev.halcamera.benchmark.domain

import org.junit.Assert.*
import org.junit.Test

/**
 * The 2026-09-26 S25+ `camera2-standard-v2` measurements for issue #123 (docs/validation/score-sensitivity-20260926.md).
 *
 * The installed app has no v2 calibration, so the run JSONs carry no score. The expected scores in the TSV are what
 * [ScoreComposer.calibrate] and [ScoreComposer.sensitivity] produced from these runs when the record was made; this
 * test pins that output and the verdicts reported on the issue, so a change to the formula or floors shows up here.
 */
class MeasuredScoreSensitivityV2Test {
    private data class Row(val fields: Map<String, String>, val run: BenchmarkRun, val metricScores: Map<String, Double>) {
        val condition get() = fields.getValue("condition")
        val score get() = fields.getValue("score").toInt()
    }

    @Test fun recordedRunsAreTheFortyMeasuredAndDistinct() {
        assertEquals(40, rows.size)
        assertEquals(40, rows.map { it.run.runId }.distinct().size)
        assertEquals(40, rows.map { it.fields.getValue("source_sha256") }.distinct().size)
        assertEquals(
            mapOf("normal" to 10, "control" to 6, "lowlight2" to 6, "thermal_light" to 6, "thermal_moderate" to 6, "contention" to 6),
            rows.groupingBy { it.condition }.eachCount()
        )
    }

    @Test fun storedValidityMatchesTheFlagTable() {
        for (row in rows) {
            val v = row.run.validity
            assertEquals(row.run.runId, row.fields.getValue("measurement_valid").toBooleanStrict(), v.measurementValid)
            assertEquals(row.run.runId, row.fields.getValue("comparison_eligible").toBooleanStrict(), v.comparisonEligible)
            assertEquals(row.run.runId, row.fields.getValue("scoring_eligible").toBooleanStrict(), v.scoringEligible)
        }
    }

    @Test fun calibrationFromTheTenNormalRunsReproducesEveryRecordedScore() {
        assertEquals(rows.filter { it.condition == "normal" }.map { it.run.runId }.sorted(), calibration.normalRunIds)
        for (row in rows) {
            val score = requireNotNull(ScoreComposer.sensitivity(row.run, calibration)) { row.run.runId }
            assertEquals(row.run.runId, row.score, score.total)
            assertEquals(row.metricScores.keys, score.metrics.keys)
            row.metricScores.forEach { (id, expected) -> assertEquals("${row.run.runId} $id", expected, score.metrics.getValue(id), 1e-9) }
        }
    }

    @Test fun onlyModerateThermalRunsAreKeptOutOfTheProductionScore() {
        for (row in rows) {
            val production = ScoreComposer.compose(row.run, calibration)
            if (row.condition == "thermal_moderate") assertNull(row.run.runId, production)
            else assertEquals(row.run.runId, row.score, requireNotNull(production).total)
        }
    }

    @Test fun noStressConditionMeetsTheAgreedDegradationRule() {
        // Rule agreed on #123: degraded when at least four of six stress runs score below the lowest normal run.
        val normalMin = scores("normal").min()
        assertEquals(787, normalMin)
        val below = listOf("control", "lowlight2", "thermal_light", "thermal_moderate", "contention")
            .associateWith { c -> scores(c).count { it < normalMin } }
        assertEquals(mapOf("control" to 0, "lowlight2" to 2, "thermal_light" to 0, "thermal_moderate" to 0, "contention" to 0), below)
        assertTrue(below.values.none { it >= 4 })
    }

    @Test fun thermalScoresAreNotMonotonicAndTheDayTwoControlMatchesDayOne() {
        val median = listOf("normal", "control", "lowlight2", "thermal_light", "thermal_moderate", "contention")
            .associateWith { median(scores(it)) }
        assertEquals(
            mapOf("normal" to 800.5, "control" to 799.0, "lowlight2" to 797.5, "thermal_light" to 841.5,
                "thermal_moderate" to 824.0, "contention" to 837.5),
            median
        )
        assertFalse(median.getValue("normal") > median.getValue("thermal_light") &&
            median.getValue("thermal_light") > median.getValue("thermal_moderate"))
    }

    private fun scores(condition: String) = rows.filter { it.condition == condition }.map { it.score }

    private fun median(values: List<Int>): Double {
        val s = values.sorted()
        return if (s.size % 2 == 0) (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0 else s[s.size / 2].toDouble()
    }

    private companion object {
        val rows: List<Row> by lazy { load() }
        val calibration: ScoreCalibration by lazy { ScoreComposer.calibrate(rows.filter { it.condition == "normal" }.map { it.run }) }

        fun load(): List<Row> {
            val lines = requireNotNull(MeasuredScoreSensitivityV2Test::class.java
                .getResourceAsStream("/score-sensitivity-20260926.tsv"))
                .bufferedReader(Charsets.UTF_8).use { it.readLines() }
            val header = lines.first().split('\t')
            require(header.size == 30 && header.distinct().size == header.size)
            return lines.drop(1).map { line ->
                val cells = line.split('\t')
                require(cells.size == header.size)
                val fields = header.take(14).zip(cells.take(14)).toMap()
                require(fields.getValue("source_sha256").matches(Regex("[0-9a-f]{64}")))
                val scores = linkedMapOf<String, Double>()
                // IDs, categories and units come from the recording, never from the floors' iteration order.
                val metrics = header.drop(14).zip(cells.drop(14)).map { (key, value) ->
                    val identity = key.split(':')
                    val parts = value.split(';')
                    require(identity.size == 3 && parts.size == 4)
                    scores[identity[0]] = parts[3].toDouble()
                    BenchmarkRunFixture.metric(identity[0], parts[0].toDouble()).copy(
                        category = Category.valueOf(identity[1].uppercase(java.util.Locale.ROOT)),
                        unit = identity[2], sampleCount = parts[1].toInt(), timeout = parts[2].toBooleanStrict()
                    )
                }
                val int = { key: String -> fields.getValue(key).toInt() }
                // Only score inputs are replayed; device, endpoint and timestamps are fixture placeholders shared by
                // every row, which is what the real runs share too (one S25+, camera 0, one installed build).
                val run = BenchmarkRunFixture.run(
                    runId = fields.getValue("run_id"),
                    metrics = metrics,
                    profile = BenchmarkProfile.CAMERA2_STANDARD_V2,
                    exposureLoad = fields.getValue("exposure_load_p50").toDouble(),
                    flags = fields.getValue("flags").takeUnless { it == "-" }
                        ?.split(',')?.map { requireNotNull(ValidityFlags.byCode(it)) } ?: emptyList(),
                    app = AppInfo("0.13.2", 150, false)
                ).copy(
                    env = RunEnv(int("thermal_start"), int("thermal_max"), int("thermal_end"),
                        int("battery_start"), int("battery_end"), false, false, 0)
                )
                Row(fields, run, scores)
            }
        }
    }
}
