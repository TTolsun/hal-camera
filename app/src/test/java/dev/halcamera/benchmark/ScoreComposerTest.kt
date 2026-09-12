package dev.halcamera.benchmark

import dev.halcamera.benchmark.BenchmarkRunFixture.metric
import dev.halcamera.benchmark.BenchmarkRunFixture.run
import org.junit.Assert.*
import org.junit.Test

class ScoreComposerTest {
    private fun normal(id: Int = 0) = run(
        runId = "normal-$id", app = AppInfo("0.5.0", 6, false),
        metrics = ScoreComposer.floors.keys.map { metric(it, if (it == "H.5" || it == "H.9") 0.0 else 100.0) }
    )
    private fun dataset() = (0..9).map(::normal)
    private fun calibration() = ScoreComposer.calibrate(dataset())

    @Test fun tenDistinctNormalReleaseRunsAreRequired() {
        assertThrows(IllegalArgumentException::class.java) { ScoreComposer.calibrate(dataset().take(9)) }
        assertThrows(IllegalArgumentException::class.java) { ScoreComposer.calibrate(List(10) { normal() }) }
        for (bad in listOf(
            normal(9).copy(app = AppInfo("old", 1, null)),
            normal(9).copy(app = AppInfo("debug", 1, true)),
            normal(9).copy(app = AppInfo("other-release", 9, false)),
            normal(9).copy(validity = RunValidity.from(listOf(ValidityFlags.CHARGING))),
            normal(9).copy(env = normal().env.copy(charging = true)),
            normal(9).copy(endpoint = BenchmarkRunFixture.endpoint("3")),
            normal(9).copy(device = normal().device.copy(fingerprint = "other"))
        )) assertThrows(IllegalArgumentException::class.java) { ScoreComposer.calibrate(dataset().take(9) + bad) }
    }

    @Test fun constantNormalDistributionHasFiniteScoresAndFixedFloors() {
        val c = calibration()
        assertEquals(1.0, c.curves.first { it.metricId == "H.9" }.scale, 0.0)
        assertEquals(800, ScoreComposer.compose(normal(), c)!!.total)
        assertEquals(4, ScoreComposer.compose(normal(), c)!!.categories.size)
    }

    @Test fun degradationIsMonotoneAndScoresAreBounded() {
        val c = calibration()
        val slow = normal().copy(metrics = normal().metrics.map { it.copy(value = 10000.0) })
        val fast = normal().copy(metrics = normal().metrics.map { it.copy(value = 0.0) })
        assertEquals(0, ScoreComposer.compose(slow, c)!!.total)
        assertTrue(ScoreComposer.compose(fast, c)!!.total in 800..1000)
        val oneFailure = normal().copy(metrics = normal().metrics.map { if (it.id == "H.9") it.copy(value = 1.0) else it })
        // Stability averages two metrics, and each of the four categories weighs one quarter.
        assertEquals(775, ScoreComposer.compose(oneFailure, c)!!.total)
    }

    @Test fun threeAAndUnimplementedCaptureStallDoNotAffectScore() {
        val input = normal().copy(metrics = normal().metrics + listOf(metric("H.6", 999999.0, timeout = true), metric("2.7", null)))
        val scored = ScoreComposer.apply(input, calibration())
        assertEquals(800, scored.endpointScore)
        assertEquals(ScoreComposer.VERSION, scored.scoringRuleVersion)
        assertNull(scored.metric("H.6")!!.score)
        assertNull(scored.metric("2.7")!!.score)
    }

    @Test fun missingNonFiniteAndDuplicateMetricsNeverReceivePartialScore() {
        val c = calibration()
        for (bad in listOf<Double?>(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            val input = normal().copy(metrics = normal().metrics.map { if (it.id == "H.1") it.copy(value = bad) else it })
            assertNull(ScoreComposer.compose(input, c))
        }
        assertNull(ScoreComposer.compose(normal().copy(metrics = normal().metrics.drop(1)), c))
        assertNull(ScoreComposer.compose(normal().copy(metrics = normal().metrics + normal().metrics.first()), c))
    }

    @Test fun eligibilityScopeAndUnknownEnvironmentAreEnforced() {
        val input = normal()
        val c = calibration()
        for (bad in listOf(
            input.copy(device = input.device.copy(model = "other")),
            input.copy(endpoint = BenchmarkRunFixture.endpoint("3")),
            input.copy(contract = input.contract.copy(metricDefinitionVersion = "other")),
            input.copy(env = input.env.copy(thermalMax = null)),
            input.copy(env = input.env.copy(batteryStart = 19)),
            input.copy(env = input.env.copy(charging = null)),
            input.copy(validity = input.validity.copy(flags = listOf("FUTURE_BLOCKER")))
        )) assertNull(ScoreComposer.compose(bad, c))
    }

    @Test fun sensitivityDoesNotPublishAnIneligibleScore() {
        val hot = normal().copy(env = normal().env.copy(thermalMax = 2), validity = RunValidity.from(listOf(ValidityFlags.THERMAL_HIGH)))
        assertNotNull(ScoreComposer.sensitivity(hot, calibration()))
        val result = ScoreComposer.apply(hot.copy(endpointScore = 800, scoringRuleVersion = "old"), calibration())
        assertNull(result.endpointScore)
        assertNull(result.scoringRuleVersion)
        assertTrue(result.metrics.all { it.score == null })
        assertNull(ScoreComposer.sensitivity(hot.copy(aborted = "error"), calibration()))
    }

    @Test fun malformedCalibrationIsRejected() {
        val c = calibration()
        assertNull(ScoreComposer.compose(normal(), c.copy(normalRunIds = listOf("one"))))
        assertNull(ScoreComposer.compose(normal(), c.copy(curves = c.curves.drop(1))))
        assertNull(ScoreComposer.compose(normal(), c.copy(curves = c.curves.map { it.copy(scale = 0.0) })))
    }

    @Test fun storedScoresRoundTripAndTheScreenIdentifiesTheDraft() {
        val scored = ScoreComposer.apply(normal(), S25PlusScoreDraft.calibration)
        val decoded = BenchmarkReportCodec.fromJsonMap(BenchmarkReportCodec.toJsonMap(scored))
        assertEquals(scored.endpointScore, decoded.endpointScore)
        assertEquals(scored.metrics.map { it.score }, decoded.metrics.map { it.score })
        assertTrue(ResultPresenter.scoreLine(decoded)!!.contains("내부 초안"))
        assertTrue(ResultPresenter.scoreLine(decoded)!!.contains("기기 간 순위 아님"))
        assertNull(ResultPresenter.scoreLine(decoded.copy(app = AppInfo("debug", 1, true))))
        assertNull(ResultPresenter.scoreLine(normal()))
    }
}
