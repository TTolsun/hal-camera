package dev.cameradoctor.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BenchmarkProfileTest {
    private val p = BenchmarkProfile.CAMERA2_STANDARD_V1

    @Test fun standardProfileIsDraftUntilM2ConfirmsIt() {
        assertTrue(p.isDraft)
        assertEquals("camera2-standard-v1-draft", p.id)
        assertEquals(LaunchMode.WARM_REOPEN, p.launchMode)
        assertEquals("1920x1080", p.previewSize)
        assertEquals("1920x1080", p.yuvSize)
        assertEquals("[30,30]", p.fpsRange)
    }

    @Test fun expectedSampleCountsFollowMetricsDocument() {
        // 10 launches minus warm-up, 10 stills minus warm-up, 9 intervals minus the warm-up interval (METRICS.md 2.5).
        assertEquals(9, p.expectedLaunchSamples)
        assertEquals(9, p.expectedStillSamples)
        assertEquals(8, p.expectedShotToShotSamples)
        val noExclusion = p.copy(excludeFirst = false)
        assertEquals(10, noExclusion.expectedLaunchSamples)
        assertEquals(9, noExclusion.expectedShotToShotSamples)
    }

    @Test fun jsonRoundTrip() {
        val back = BenchmarkProfile.fromJsonMap(p.toJsonMap())
        assertEquals(p, back)
        assertEquals("warm_reopen", p.toJsonMap()["launch_mode"])
    }

    @Test fun malformedProfileMapFailsWithTheKeyNameNotAClassCast() {
        val missing = p.toJsonMap() - "yuv_size"
        try { BenchmarkProfile.fromJsonMap(missing); fail("missing key must be rejected") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("yuv_size")) }
        val wrongType = p.toJsonMap() + ("zsl" to "off")
        try { BenchmarkProfile.fromJsonMap(wrongType); fail("wrong type must be rejected") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("zsl")) }
        val badMode = p.toJsonMap() + ("launch_mode" to "cold")
        try { BenchmarkProfile.fromJsonMap(badMode); fail("unknown launch mode must be rejected") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("launch_mode")) }
        // org.json hands numbers back as Int or Long; both must be accepted.
        val longs = p.toJsonMap() + ("launch_iterations" to 10L) + ("warmup_ms" to 3000)
        assertEquals(p, BenchmarkProfile.fromJsonMap(longs))
    }

    @Test fun conditionsKeyIsStableAndNamesTheLaunchMode() {
        assertEquals(p.conditionsKey, p.copy().conditionsKey)
        assertTrue(p.conditionsKey.contains("launch=warm_reopen"))
        assertTrue(p.conditionsKey.contains("preview=1920x1080"))
        assertNotEquals(p.conditionsKey, p.copy(yuvSize = "1280x720").conditionsKey)
    }

    @Test fun contractIdCombinesProfileAndMetricDefinition() {
        val c = MeasurementContract.forProfile(p)
        assertEquals("camera2-standard-v1-draft|metrics-0.3|nearest_rank|elapsedRealtimeNanos", c.comparisonContractId)
        // Same profile, different metric computation: not comparable.
        val other = c.copy(metricDefinitionVersion = "metrics-0.4")
        assertNotEquals(c.comparisonContractId, other.comparisonContractId)
        assertEquals(c, MeasurementContract.fromJsonMap(c.toJsonMap()))
    }
}
