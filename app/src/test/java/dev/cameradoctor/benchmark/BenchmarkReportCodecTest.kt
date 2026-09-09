package dev.cameradoctor.benchmark

import dev.cameradoctor.check.CameraEndpoint
import dev.cameradoctor.check.LensRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BenchmarkReportCodecTest {
    private val profile = BenchmarkProfile.CAMERA2_STANDARD_V1
    private val device = DeviceInfo("samsung", "SM-S936N", "BP4A.251205.006", "inc", "samsung/fp", null, 36, "2025-12-01", "hal-1")
    private val app = AppInfo("0.3.0", 3)
    private val subject = SubjectLabel("SW42_release_20260909", "a8f29c1", null, "SAT 변경 적용")

    private fun run(): BenchmarkRun {
        val cycles = (0 until 10).map { i -> LaunchCycle(i, i == 0, 140.0 + i, 40.0, 90.0, 120.0, 400.0, 80.0, timestampsNs = mapOf("open_call" to 1_000L * i)) }
        val stills = (0 until 10).map { i -> StillSample(i, i == 0, i * 400_000_000L, i * 400_000_000L + 160_000_000L, i * 400_000_000L + 150_000_000L) }
        val metrics = BenchmarkEvaluator(profile).evaluate(BenchmarkEvaluator.Input(cycles, stills, null, 0))
        val validity = RunValidityEvaluator.evaluate(ValidityInputs(
            aborted = null, hardFailure = false, preflightSupported = true, preflightMismatch = false,
            launchSamples = 9, stillSamples = 9, observedFrames = 298, expectedLaunchSamples = 9, expectedStillSamples = 9,
            cadenceFixed = true, thermalStart = 0, thermalMax = 1, thermalEnd = 1, powerSaveMode = false, charging = true,
            batteryStart = 82, profileDraft = profile.isDraft, subjectLabeled = true))
        val identity = BuildIdentity.compare(BuildIdentity(device, app, subject), BuildIdentity(device.copy(fingerprint = "samsung/other"), app, SubjectLabel("SW41", "9c01d2e")))
        return BenchmarkRun(
            runId = "20260909-101422",
            exportedAtUtc = "2026-09-09T01:14:22.000Z",
            aborted = null,
            profile = profile,
            contract = MeasurementContract.forProfile(profile),
            compatibility = Compatibility("device_setup", true, emptyList(), true),
            effectiveConditions = mapOf("af_mode" to "CONTINUOUS_PICTURE", "fps_range" to "[30,30]"),
            endpoint = CameraEndpoint("0", null, LensRole.MAIN, 1, true, true, null, 24.0, 1, 3, 0.6f, 10f),
            device = device, app = app, subject = subject,
            env = RunEnv(0, 1, 1, 82, 80, true, false, 0),
            validity = validity,
            baselineRef = RunRef("20260909-095100", true, identity),
            referenceRef = null,
            metrics = metrics,
            raw = mapOf("launch_cycles" to cycles.map { it.toJsonMap() }, "stills" to stills.map { it.toJsonMap() },
                "observation" to mapOf("frames" to 298, "exposure_load_p50" to 1.2e6))
        )
    }

    @Test fun roundTripPreservesTheRun() {
        val r = run()
        val back = BenchmarkReportCodec.fromJsonMap(BenchmarkReportCodec.toJsonMap(r))
        assertEquals(r, back)
        assertEquals(r.contract.comparisonContractId, back.contract.comparisonContractId)
        assertTrue(back.validity.comparisonEligible)
        assertFalse(back.validity.scoringEligible)
    }

    @Test fun topLevelKeysFollowSchema3() {
        val r = run()
        val m = BenchmarkReportCodec.toJsonMap(r)
        val required = listOf(
            "schema_version", "kind", "run_id", "exported_at_utc", "aborted", "profile", "compatibility", "conditions",
            "metric_definition_version", "stats_method", "clock", "comparison_contract_id", "regression_rule_version",
            "scoring_rule_version", "device", "app", "subject", "endpoint", "env", "validity", "baseline_ref", "reference_ref",
            "metrics", "summary", "raw", "events"
        )
        for (k in required) assertTrue(k, m.containsKey(k))
        assertEquals(3, m["schema_version"])
        assertEquals("benchmark", m["kind"])
        assertEquals("camera2-standard-v1-draft|metrics-0.3|nearest_rank|elapsedRealtimeNanos", m["comparison_contract_id"])
        assertEquals("regression-rule-v1", m["regression_rule_version"])
        @Suppress("UNCHECKED_CAST") val summary = m["summary"] as Map<String, Any?>
        assertEquals(r.metrics.size, summary["unknown"])
        assertEquals(null, summary["endpoint_score"])
        @Suppress("UNCHECKED_CAST") val env = m["env"] as Map<String, Any?>
        assertTrue(env.containsKey("thermal_max"))
        assertTrue(env.containsKey("power_save_mode"))
        @Suppress("UNCHECKED_CAST") val subjectMap = m["subject"] as Map<String, Any?>
        assertEquals("a8f29c1", subjectMap["commit"])
    }

    @Test fun metricJsonUsesNForSampleCountAndKeepsSamplesNullForWindowMetrics() {
        val ms = run().metrics.associateBy { it.id }
        val open = ms["1.1"]!!.toJsonMap()
        assertEquals(9, open["n"])
        assertFalse(open.containsKey("sample_count"))
        assertEquals(9, (open["samples"] as List<*>).size)
        val h1 = ms["H.1"]!!.toJsonMap()
        assertEquals(null, h1["samples"])
        assertEquals("not_run", h1["unknown_reason"])
        assertEquals(ms["1.1"], BenchmarkMetric.fromJsonMap(open))
    }

    @Test fun missingRequiredFieldsFailWithTheKeyName() {
        val m = BenchmarkReportCodec.toJsonMap(run())
        try { BenchmarkReportCodec.fromJsonMap(m - "run_id"); fail("run_id required") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("run_id")) }
        val metric = run().metrics.first().toJsonMap() - "id"
        try { BenchmarkMetric.fromJsonMap(metric); fail("metric id required") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("id")) }
        try { MeasurementContract.fromJsonMap(mapOf("clock" to "x")); fail("profile_id required") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("profile_id")) }
        // Optional contract fields fall back to the writer constants.
        val c = MeasurementContract.fromJsonMap(mapOf("profile_id" to "p"))
        assertEquals(MeasurementContract.METRIC_DEFINITION_VERSION, c.metricDefinitionVersion)
    }

    @Test fun otherSchemaVersionIsRejected() {
        val m = BenchmarkReportCodec.toJsonMap(run()) + ("schema_version" to 2)
        try {
            BenchmarkReportCodec.fromJsonMap(m)
            fail("schema 2 must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
