package dev.halcamera.benchmark

import org.junit.Assert.*
import org.junit.Test

class ProfileComparisonTest {
    private val confirmed = ProfileComparison.Options(sameDeviceConfirmed = true, independentRunsConfirmed = true)
    private fun group(side: String, value: Double, n: Int = 5): List<ProfileEntry> = (1..n).map {
        val run = BenchmarkRunFixture.run(runId = "$side-$it", metrics = listOf(BenchmarkRunFixture.metric("H.1", value))).let { r ->
            r.copy(effectiveConditions = r.effectiveConditions + mapOf("preview_size" to "1920x1080", "yuv_size" to "1920x1080", "still_size" to "1920x1080"))
        }
        ProfileEntry("import:$side-$it", "$side-$it", "synthetic test", run)
    }
    private fun row(result: ProfileComparison.Result) = result.metrics.single { it.id == "H.1" }
    private fun compare(a: List<ProfileEntry> = group("a", 100.0), b: List<ProfileEntry> = group("b", 200.0),
                        options: ProfileComparison.Options = confirmed) = ProfileComparison.compare(a, b, options)

    @Test fun `build changes across sets are allowed and frames are not independent runs`() {
        val b = group("b", 200.0).map { it.copy(run = it.run.copy(device = it.run.device.copy(fingerprint = "new-system"),
            subject = SubjectLabel("SW-after", "new-commit"))) }
        val result = compare(b = b)
        assertTrue(result.problems.toString(), result.problems.isEmpty())
        val metric = row(result)
        assertEquals(5, metric.before!!.n)
        assertEquals(100.0, metric.delta!!, 0.0)
        assertTrue(metric.adjustedP!! < 0.05)
        assertEquals(RegressionState.REGRESSED, metric.practical)
        assertTrue(result.render().contains("a-1"))
        assertTrue(result.render().contains(RepeatStatistics.VERSION))
    }

    @Test fun `statistical significance and practical thresholds are separate`() {
        val metric = row(compare(b = group("b", 100.00001)))
        assertTrue(metric.adjustedP!! < 0.05)
        assertEquals(RegressionState.STABLE, metric.practical)
    }

    @Test fun `insufficient runs are inconclusive even if each run contains many frames`() {
        val metric = row(compare(a = group("a", 100.0, 4)))
        assertNull(metric.p)
        assertTrue(metric.blocked.any { it.contains("부족") })
    }

    @Test fun `unknown identity requires explicit confirmation and model alone never proves identity`() {
        val blank = group("a", 100.0).map { it.copy(run = it.run.copy(raw = it.run.raw + ("device_instance_id" to "  "))) }
        assertNull(blank.first().instanceId)
        assertTrue(compare(options = confirmed.copy(sameDeviceConfirmed = false)).problems.any { it.contains("물리 기기") })
        fun identified(entries: List<ProfileEntry>, id: String) = entries.map { it.copy(run = it.run.copy(raw = it.run.raw + ("device_instance_id" to id))) }
        assertTrue(compare(identified(group("a", 100.0), "id"), identified(group("b", 200.0), "id"),
            confirmed.copy(sameDeviceConfirmed = false)).problems.isEmpty())
        assertNull(row(compare(identified(group("a", 100.0), "id1"), identified(group("b", 200.0), "id2"),
            confirmed.copy(sameDeviceConfirmed = false))).p)
    }

    @Test fun `other devices require explicit mode and retain raw descriptive data`() {
        val b = group("b", 200.0).map { it.copy(run = it.run.copy(device = it.run.device.copy(model = "Exynos fixture"))) }
        val blocked = compare(b = b)
        assertNull(row(blocked).p)
        assertEquals(200.0, row(blocked).after!!.mean, 0.0)
        assertNotNull(row(compare(b = b, options = confirmed.copy(differentDevices = true))).p)
        assertTrue(compare(b = b, options = confirmed.copy(differentDevices = true)).render().contains("광학계"))
    }

    @Test fun `mixed builds and overlapping content or measurement IDs cannot inflate N`() {
        val a = group("a", 100.0)
        assertNull(row(compare(a, a)).p)
        val collision = group("b", 200.0).toMutableList().apply { this[0] = this[0].copy(run = this[0].run.copy(runId = a[0].run.runId)) }
        assertNull(row(compare(a, collision)).p)
        val mixed = a.toMutableList().apply { this[0] = this[0].copy(run = this[0].run.copy(subject = SubjectLabel("other"))) }
        assertTrue(compare(a = mixed).problems.any { it.contains("빌드") })
    }

    @Test fun `aborted timeout missing values and unknown flags have traceable exclusions`() {
        val a = group("a", 100.0, 9).toMutableList()
        a[0] = a[0].copy(run = a[0].run.copy(aborted = "cancelled"))
        a[1] = a[1].copy(run = a[1].run.copy(metrics = listOf(BenchmarkRunFixture.metric("H.1", 100.0, timeout = true))))
        a[2] = a[2].copy(run = a[2].run.copy(metrics = listOf(BenchmarkRunFixture.metric("H.1", null))))
        a[3] = a[3].copy(run = a[3].run.copy(validity = RunValidity(true, true, true, listOf("FUTURE_FLAG"))))
        val metric = row(compare(a = a))
        assertEquals(5, metric.before!!.n)
        assertEquals(4, metric.exclusions.size)
        assertTrue(metric.exclusions.any { it.contains("a-1") && it.contains("중단") })
        assertTrue(metric.exclusions.any { it.contains("FUTURE_FLAG") })
        assertNotNull(metric.p)
    }

    @Test fun `forged stored flags cannot hide bad environment or unsupported contracts`() {
        val run = group("a", 100.0).first().run
        assertNotNull(ProfileComparison.exclusion(run.copy(env = run.env.copy(powerSaveMode = true))))
        assertNotNull(ProfileComparison.exclusion(run.copy(env = run.env.copy(thermalEnd = 3))))
        assertNotNull(ProfileComparison.exclusion(run.copy(effectiveConditions = run.effectiveConditions + ("fps_range" to "[15,30]"))))
        assertNotNull(ProfileComparison.exclusion(run.copy(contract = run.contract.copy(metricDefinitionVersion = "future"))))
        assertNotNull(ProfileComparison.exclusion(run.copy(validity = run.validity.copy(ruleVersion = "future"))))
    }

    @Test fun `conditions environment and endpoint mismatch block inference`() {
        val b = group("b", 200.0)
        for (changed in listOf(
            b.map { it.copy(run = it.run.copy(effectiveConditions = emptyMap())) },
            b.map { it.copy(run = it.run.copy(env = it.run.env.copy(charging = true))) },
            b.map { it.copy(run = it.run.copy(env = it.run.env.copy(powerSaveMode = null))) },
            b.map { it.copy(run = it.run.copy(env = it.run.env.copy(rotation = 1))) },
            b.map { it.copy(run = it.run.copy(endpoint = BenchmarkRunFixture.endpoint("1"))) }
        )) assertNull(row(compare(b = changed)).p)
        assertNull(row(compare(options = confirmed.copy(independentRunsConfirmed = false))).p)
    }

    @Test fun `multiple tested metrics share a corrected significance family`() {
        fun two(entries: List<ProfileEntry>) = entries.map { it.copy(run = it.run.copy(metrics = it.run.metrics +
            BenchmarkRunFixture.metric("H.2", it.run.metrics.first().value))) }
        val result = compare(two(group("a", 100.0)), two(group("b", 200.0)))
        assertEquals(row(result).p!! * 2, row(result).adjustedP!!, 1e-12)
    }

    @Test fun `3A exposure mismatch blocks only 3A metrics`() {
        fun withThreeA(entries: List<ProfileEntry>, exposure: Double?) = entries.map { it.copy(run = it.run.copy(
            metrics = it.run.metrics + BenchmarkRunFixture.metric("H.6", 500.0),
            raw = mapOf("observation" to mapOf("exposure_load_p50" to exposure)))) }
        val a = withThreeA(group("a", 100.0), 100.0)
        for (exposure in listOf(null, 501.0)) {
            val result = compare(a, withThreeA(group("b", 200.0), exposure))
            assertNotNull(row(result).p)
            assertNull(result.metrics.single { it.id == "H.6" }.p)
        }
    }
}
