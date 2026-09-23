package dev.halcamera.benchmark.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BenchmarkProfileTest {
    private val p = BenchmarkProfile.CAMERA2_STANDARD_V1

    @Test fun standardProfileIsConfirmedAfterTheM2DeviceCheck() {
        // Confirmed on the Galaxy S25+ (M2): three rear-main runs with zero stalls in the observation window,
        // so the 1080p YUV condition stands and the id carries no "-draft" suffix any more (plan 3.5).
        assertFalse(p.isDraft)
        assertEquals("camera2-standard-v1", p.id)
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

    @Test fun theRecordingProfileKeepsEveryStandardConditionAndAddsTheRecordingOnes() {
        val v2 = BenchmarkProfile.CAMERA2_STANDARD_V2
        assertFalse(v2.isDraft)
        assertEquals("camera2-standard-v2", v2.id)
        assertTrue(v2.records)
        assertFalse(p.records)
        // Everything v1 fixes stays fixed: a v2 run measures the same launch, preview and capture conditions.
        assertEquals(p, v2.copy(id = p.id, recordSize = null, recordCodec = null, recordBitrate = null,
            recordFps = null, recordDurationMs = null, recordIterations = null, recordAudio = null))
        assertEquals("1920x1080", v2.recordSize)
        assertEquals(30, v2.recordFps)
        assertEquals(9_000L, v2.recordDurationMs)
        assertEquals(5, v2.recordIterations)
        assertEquals(false, v2.recordAudio)
        // Five cycles minus the warm-up cycle. Below the ten repetitions of METRICS.md 0.2 by decision (3.2).
        assertEquals(4, v2.expectedRecordSamples)
        assertEquals(0, p.expectedRecordSamples)
        assertEquals(v2, BenchmarkProfile.canonical("camera2-standard-v2"))
        assertEquals(p, BenchmarkProfile.canonical("camera2-standard-v1"))
    }

    @Test fun recordingConditionsSurviveTheJsonRoundTripAndAreAllOrNothing() {
        val v2 = BenchmarkProfile.CAMERA2_STANDARD_V2
        assertEquals(v2, BenchmarkProfile.fromJsonMap(v2.toJsonMap()))
        // A v1 file carries no record keys at all and must keep reading as a non-recording profile.
        assertEquals(p, BenchmarkProfile.fromJsonMap(p.toJsonMap().filterKeys { !it.startsWith("record_") }))
        val half = v2.toJsonMap() - "record_fps"
        try { BenchmarkProfile.fromJsonMap(half); fail("a partial record block must be rejected") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("record_fps")) }
    }

    @Test fun theRecordSegmentOfTheConditionsKeyAppearsOnlyForRecordingProfiles() {
        val v2 = BenchmarkProfile.CAMERA2_STANDARD_V2
        // v1 baselines are stored under this key, so it must not gain a record segment.
        assertFalse(p.conditionsKey.contains("record="))
        assertTrue(v2.conditionsKey.contains("record=h264@1920x1080/30fps/9000ms x5"))
        assertNotEquals(p.conditionsKey, v2.conditionsKey)
        assertNotEquals(v2.conditionsKey, v2.copy(recordDurationMs = 6_000).conditionsKey)
    }

    @Test fun contractIdCombinesProfileAndMetricDefinition() {
        val c = MeasurementContract.forProfile(p)
        assertEquals("camera2-standard-v1|metrics-0.3|nearest_rank|elapsedRealtimeNanos", c.comparisonContractId)
        // Same profile, different metric computation: not comparable.
        val other = c.copy(metricDefinitionVersion = "metrics-0.4")
        assertNotEquals(c.comparisonContractId, other.comparisonContractId)
        assertEquals(c, MeasurementContract.fromJsonMap(c.toJsonMap()))
    }
}
