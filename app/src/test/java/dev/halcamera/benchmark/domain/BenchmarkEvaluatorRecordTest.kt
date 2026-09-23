package dev.halcamera.benchmark.domain

import dev.halcamera.metrics.RecordCadence
import dev.halcamera.metrics.UnknownReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the five RECORD metrics are aggregated over the cycles of a run (docs/PLAN-Recording-v0.1.md 7 and 8).
 * The cadence numbers are handed in ready-made; RecordMetricsTest is where they are computed.
 */
class BenchmarkEvaluatorRecordTest {

    private val profile = BenchmarkProfile.CAMERA2_STANDARD_V2
    private val evaluator = BenchmarkEvaluator(profile)

    private fun cadence(
        anomalies: Int? = 0, compared: Int = 240, fps: Double? = 30.0, jitter: Double? = 0.4,
        reason: UnknownReason? = null
    ) = RecordCadence(
        frames = 270, intervals = List(269) { 33.3 }, comparedIntervals = compared, excludedIntervals = 0,
        anomalyCount = anomalies, anomalyUnknownReason = reason,
        windowFpsP50 = fps, windowFpsMin = fps, windows = if (fps == null) 0 else 5,
        jitterStdDevMs = jitter, jitterP95Ms = jitter
    )

    private fun sample(
        i: Int, warmup: Boolean = false, failed: Boolean = false, start: Double? = 55.0,
        stop: Double? = 300.0, c: RecordCadence? = cadence()
    ) = RecordSample(i, warmup, failed, start, stop, c)

    /** Five cycles, the first a warm-up, as the profile drives them. */
    private fun fiveGood(vararg overrides: Pair<Int, RecordSample>): List<RecordSample> {
        val byIndex = overrides.toMap()
        return (0 until 5).map { byIndex[it] ?: sample(it, warmup = it == 0) }
    }

    private fun evaluate(records: List<RecordSample>, unsupported: Boolean = false) = evaluator.evaluate(
        BenchmarkEvaluator.Input(
            cycles = emptyList(), stills = emptyList(), observation = null, callbackFailures = 0,
            observed = false, records = records, recordUnsupported = unsupported
        )
    ).associateBy { it.id }

    @Test
    fun `the record metrics are reported with the catalog category and unit`() {
        val ms = evaluate(fiveGood())
        assertEquals(BenchmarkMetrics.RECORD.toSet(), ms.keys.filter { it.startsWith("3.") }.toSet())
        for (id in BenchmarkMetrics.RECORD) assertEquals(Category.RECORD, ms.getValue(id).category)
        assertEquals("ms", ms.getValue("3.1").unit)
        assertEquals("fps", ms.getValue("3.4").unit)
        assertEquals("count", ms.getValue("3.2").unit)
    }

    @Test
    fun `the warm-up cycle is excluded and kept beside the samples`() {
        val ms = evaluate(fiveGood(0 to sample(0, warmup = true, start = 999.0)))
        val m = ms.getValue("3.1")
        assertEquals(55.0, m.value!!, 0.001)
        assertEquals(profile.expectedRecordSamples, m.sampleCount)
        assertEquals(listOf(999.0), m.excludedWarmup)
    }

    @Test
    fun `3 point 2 sums the anomalies over the cycles and counts the comparisons`() {
        val ms = evaluate(fiveGood(
            2 to sample(2, c = cadence(anomalies = 1)),
            3 to sample(3, c = cadence(anomalies = 2))
        ))
        val m = ms.getValue("3.2")
        assertEquals(3.0, m.value!!, 0.0)
        assertEquals(4 * 240, m.sampleCount)
    }

    @Test
    fun `a cycle whose cadence was not fixed contributes no zero to 3 point 2`() {
        val notFixed = cadence(anomalies = null, compared = 0, reason = UnknownReason.CADENCE_CHANGED)
        val ms = evaluate(fiveGood(
            1 to sample(1, c = notFixed), 2 to sample(2, c = notFixed),
            3 to sample(3, c = notFixed), 4 to sample(4, c = cadence(anomalies = 1))
        ))
        val m = ms.getValue("3.2")
        // Only the one cycle that ran at the profile's frame duration is counted, and its comparisons alone.
        assertEquals(1.0, m.value!!, 0.0)
        assertEquals(240, m.sampleCount)
    }

    @Test
    fun `no cycle at the fixed cadence leaves 3 point 2 unknown rather than zero`() {
        val notFixed = cadence(anomalies = null, compared = 0, reason = UnknownReason.CADENCE_CHANGED)
        val ms = evaluate((0 until 5).map { sample(it, warmup = it == 0, c = notFixed) })
        val m = ms.getValue("3.2")
        assertNull(m.value)
        assertEquals(UnknownReason.CADENCE_CHANGED, m.unknownReason)
    }

    @Test
    fun `too few usable cycles leave the values unknown but keep the statistics`() {
        // Three failures leave one usable cycle, below the minimum of three.
        val ms = evaluate(fiveGood(
            1 to sample(1, failed = true), 2 to sample(2, failed = true), 3 to sample(3, failed = true)
        ))
        for (id in BenchmarkMetrics.RECORD) {
            val m = ms.getValue(id)
            assertNull("$id has no value", m.value)
            assertEquals("$id says why", UnknownReason.INSUFFICIENT_SAMPLES, m.unknownReason)
        }
        assertEquals(300.0, ms.getValue("3.6").p50!!, 0.001)
    }

    @Test
    fun `exactly the minimum of usable cycles still produces values`() {
        val ms = evaluate(fiveGood(1 to sample(1, failed = true)))
        assertEquals(ValidityFlags.MIN_RECORD_CYCLES, 3)
        assertEquals(55.0, ms.getValue("3.1").value!!, 0.001)
        assertEquals(3, ms.getValue("3.1").sampleCount)
    }

    @Test
    fun `a cycle that produced no frames leaves the cadence metrics without its sample`() {
        val ms = evaluate(fiveGood(1 to sample(1, c = null)))
        // 3.1 and 3.6 come from the runner's marks, so that cycle still answers them.
        assertEquals(4, ms.getValue("3.1").sampleCount)
        assertEquals(3, ms.getValue("3.4").sampleCount)
        assertEquals(3, ms.getValue("3.7").sampleCount)
    }

    @Test
    fun `a refused recording combination reports unsupported, not missing`() {
        val ms = evaluate(emptyList(), unsupported = true)
        for (id in BenchmarkMetrics.RECORD) {
            assertEquals(UnknownReason.UNSUPPORTED, ms.getValue(id).unknownReason)
            assertNull(ms.getValue(id).value)
        }
    }

    @Test
    fun `a recording profile whose stage never ran reports not run`() {
        val ms = evaluate(emptyList())
        for (id in BenchmarkMetrics.RECORD) assertEquals(UnknownReason.NOT_RUN, ms.getValue(id).unknownReason)
    }

    @Test
    fun `a profile without a record stage reports no 3 point x entries at all`() {
        val v1 = BenchmarkEvaluator(BenchmarkProfile.CAMERA2_STANDARD_V1).evaluate(
            BenchmarkEvaluator.Input(emptyList(), emptyList(), null, 0, observed = false)
        )
        assertTrue(v1.none { it.id.startsWith("3.") })
        assertEquals(BenchmarkMetrics.ALL - BenchmarkMetrics.RECORD.toSet(), v1.map { it.id })
    }

    @Test
    fun `a run that recorded nothing is still a valid measurement, only not scored`() {
        val flags = RunValidityEvaluator.flags(inputs(recordSamples = 0))
        assertTrue(flags.contains(ValidityFlags.RECORD_NOT_MEASURED))
        val validity = RunValidity.from(flags)
        assertTrue("the launch, preview and capture samples are intact", validity.measurementValid)
        assertTrue("and still comparable in house", validity.comparisonEligible)
        assertEquals("but a score would cover fewer metrics than another device's", false, validity.scoringEligible)
    }

    @Test
    fun `a profile without a record stage never raises the flag`() {
        assertTrue(RunValidityEvaluator.flags(inputs(recordSamples = 0, expectedRecords = 0))
            .none { it == ValidityFlags.RECORD_NOT_MEASURED })
    }

    private fun inputs(recordSamples: Int, expectedRecords: Int = profile.expectedRecordSamples) = ValidityInputs(
        aborted = null, hardFailure = false, preflightSupported = true, preflightMismatch = false,
        launchSamples = profile.expectedLaunchSamples, stillSamples = profile.expectedStillSamples,
        observedFrames = 300, expectedLaunchSamples = profile.expectedLaunchSamples,
        expectedStillSamples = profile.expectedStillSamples,
        recordSamples = recordSamples, expectedRecordSamples = expectedRecords,
        cadenceFixed = true, thermalStart = 0, thermalMax = 0, thermalEnd = 0,
        powerSaveMode = false, charging = false, batteryStart = 80, profileDraft = false,
        debuggableBuild = false, subjectLabeled = true
    )
}
