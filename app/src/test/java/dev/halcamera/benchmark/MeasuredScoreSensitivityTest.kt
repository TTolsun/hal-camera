package dev.halcamera.benchmark

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Recorded release outputs, not expectations computed from the current scoring formula. */
@RunWith(Parameterized::class)
class MeasuredScoreSensitivityTest(private val sample: Sample) {
    data class Sample(val fields: Map<String, String>, val metrics: List<BenchmarkMetric>) {
        override fun toString() = "${fields.getValue("condition")}/${fields.getValue("run_id")}"
    }

    @Test fun measuredScoresSurviveCompositionAndReportRoundTrip() {
        val f = sample.fields
        val expectedScore = f.getValue("score").nullable()?.toInt()
        val expectedRule = f.getValue("rule").nullable()
        // Only score inputs are replayed. Unused fixture fields (timestamps, optics and statistics)
        // are placeholders; this does not replay camera events or the CLI file transport.
        val input = BenchmarkRunFixture.run(
            runId = f.getValue("run_id"),
            app = AppInfo("0.6.0", 9, false),
            metrics = sample.metrics.map { it.copy(score = null) },
            exposureLoad = f.getValue("exposure_load_p50").nullable()?.toDouble(),
            flags = f.getValue("flags").takeUnless { it == "-" }
                ?.split(',')?.map { requireNotNull(ValidityFlags.byCode(it)) } ?: emptyList()
        ).copy(
            aborted = f.getValue("aborted").nullable(),
            contract = MeasurementContract("camera2-standard-v1", "metrics-0.3", "nearest_rank", "elapsedRealtimeNanos"),
            env = RunEnv(0, 0, 0, f.getValue("battery_start").toInt(),
                f.getValue("battery_end").toInt(), false, false, 0)
        )
        val scored = ScoreComposer.apply(input, S25PlusScoreDraft.calibration)
        val encoded = BenchmarkReportCodec.toJsonMap(scored)
        assertEquals(4, encoded["schema_version"])
        val restored = BenchmarkReportCodec.fromJsonMap(encoded)
        assertEquals(scored, restored)
        for (run in listOf(scored, restored)) {
            assertEquals(expectedScore, run.endpointScore)
            assertEquals(expectedRule, run.scoringRuleVersion)
            assertEquals(f.getValue("measurement_valid").toBooleanStrict(), run.validity.measurementValid)
            assertEquals(f.getValue("comparison_eligible").toBooleanStrict(), run.validity.comparisonEligible)
            assertEquals(f.getValue("scoring_eligible").toBooleanStrict(), run.validity.scoringEligible)
            sample.metrics.forEach { expected ->
                val actual = run.metrics.single { it.id == expected.id }
                if (expected.score == null) assertNull("${expected.id} must remain unscored", actual.score)
                else assertEquals(expected.id, expected.score, requireNotNull(actual.score), 1e-7)
            }
            val line = ResultPresenter.scoreLine(run)
            if (expectedScore == null) assertNull(line)
            else assertTrue(requireNotNull(line).startsWith("Camera Endpoint Score $expectedScore / 1000\n"))
        }
    }

    companion object {
        private fun String.nullable(): String? = takeUnless { it == "null" }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun samples(): Collection<Array<Any>> {
            val lines = requireNotNull(MeasuredScoreSensitivityTest::class.java
                .getResourceAsStream("/score-sensitivity-20260914.tsv"))
                .bufferedReader(Charsets.UTF_8).use { it.readLines() }
            val header = lines.first().split('\t')
            require(header.size == 33 && header.distinct().size == header.size)
            val samples = lines.drop(1).map { line ->
                val cells = line.split('\t')
                require(cells.size == header.size)
                val fields = header.take(13).zip(cells.take(13)).toMap()
                require(fields.getValue("source_sha256").matches(Regex("[0-9a-f]{64}")))
                // IDs, categories and units come from the recording, never from floors iteration order.
                val metrics = header.drop(13).zip(cells.drop(13)).map { (key, value) ->
                    val identity = key.split(':')
                    val parts = value.split(';')
                    require(identity.size == 3 && parts.size == 4)
                    BenchmarkRunFixture.metric(identity[0], parts[0].nullable()?.toDouble()).copy(
                        category = Category.valueOf(identity[1].uppercase(java.util.Locale.ROOT)),
                        unit = identity[2], sampleCount = parts[1].toInt(),
                        timeout = parts[2].toBooleanStrict(), score = parts[3].nullable()?.toDouble()
                    )
                }
                require(metrics.map { it.id }.distinct().size == 20)
                Sample(fields, metrics)
            }
            require(samples.size == 17)
            require(samples.map { it.fields.getValue("run_id") }.distinct().size == 17)
            require(samples.map { it.fields.getValue("source_sha256") }.distinct().size == 17)
            require(samples.count { it.fields.getValue("score") == "null" } == 1)
            return samples.map { arrayOf<Any>(it) }
        }
    }
}
