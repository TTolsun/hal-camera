package dev.cameradoctor.benchmark

import dev.cameradoctor.check.CameraEndpoint
import dev.cameradoctor.check.LensRole
import dev.cameradoctor.telemetry.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Assembling a stored run from the runner's samples and the recorded events (chapter 6). */
class RunAssemblerTest {

    private val profile = BenchmarkProfile.CAMERA2_STANDARD_V1
    private val endpoint = CameraEndpoint(
        logicalCameraId = "0", physicalCameraId = null, role = LensRole.MAIN, facing = 1,
        independentlyOpenable = true, selectableByZoom = false, exposedToCameraX = null,
        equivalentFocalMm = 24.0, timestampSource = 1, hardwareLevel = 3, zoomRatioMin = 0.5f, zoomRatioMax = 10f
    )
    private val session = "bm-run-0-10"
    private val firstFrameNs = 1_000_000_000L
    private val observeStartNs = firstFrameNs + profile.warmupMs * 1_000_000
    private val observeEndNs = observeStartNs + profile.observeMs * 1_000_000

    /** 30 fps frames from the first preview frame to the end of the observation window. */
    private fun previewEvents(fpsRange: String? = "[30, 30]", intervalMs: Double = 33.333): List<Event> {
        val out = ArrayList<Event>()
        val stepNs = (intervalMs * 1e6).toLong()
        var at = firstFrameNs
        var frame = 0L
        while (at <= observeEndNs) {
            out += Event(at - 5_000_000, session, "capture_started", frame = frame, sensorNs = at)
            if (fpsRange != null) {
                out += Event(at - 5_000_000, session, "request_observed", frame = frame, values = mapOf("fpsRange" to fpsRange))
            }
            out += Event(
                at, session, "capture_result", frame = frame, sensorNs = at,
                values = mapOf(
                    "intervalMs" to intervalMs, "frameDurationNs" to stepNs.toDouble(),
                    "ae" to 2, "af" to 4, "awb" to 2, "afMode" to 4, "iso" to 100.0, "exposureNs" to 10_000_000.0
                )
            )
            at += stepNs
            frame++
        }
        return out
    }

    private fun configuredEvent(afMode: Int = 4) = Event(
        firstFrameNs - 100_000_000, session, "session_configured",
        values = mapOf("preview" to "1920x1080", "analysis" to "1920x1080", "jpeg" to "1920x1080", "afMode" to afMode)
    )

    private fun result(
        cycles: List<LaunchCycle> = goodCycles(),
        stills: List<StillSample> = goodStills(),
        aborted: String? = null,
        hardFailure: String? = null
    ) = BenchmarkRunner.Result(
        runId = "20260910-120000-000", endpoint = endpoint, cycles = cycles, stills = stills,
        observeSession = session, observeFirstFrameNs = firstFrameNs,
        observeStartNs = observeStartNs, observeEndNs = observeEndNs,
        hardFailure = hardFailure, aborted = aborted, sessions = listOf(session)
    )

    private fun goodCycles() = (0 until profile.launchIterations).map { i ->
        LaunchCycle(
            iteration = i, warmup = i == 0, openMs = 100.0 + i, configureMs = 40.0, firstStartedMs = 60.0,
            yuvProxyMs = 65.0, previewTotalMs = 205.0 + i, closeMs = 30.0
        )
    }

    private fun goodStills() = (0 until profile.stillCount).map { i ->
        val submit = observeEndNs + i * 300_000_000L
        StillSample(index = i, warmup = i == 0, submitNs = submit, imageNs = submit + 180_000_000L, resultNs = submit + 150_000_000L)
    }

    private fun context(
        thermalStart: Int? = 0, thermalMax: Int? = 0, thermalEnd: Int? = 0,
        charging: Boolean? = false, powerSave: Boolean? = false, battery: Int? = 80,
        compatibility: Compatibility = Compatibility("static_table", true, emptyList(), true),
        subject: SubjectLabel = SubjectLabel(subjectBuildLabel = "SW42", subjectCommit = "a8f29c1")
    ) = RunAssembler.Context(
        exportedAtUtc = "2026-09-10T03:00:00.000Z",
        device = DeviceInfo("samsung", "SM-S936N", "BP4A", "1", "samsung/x", null, 36, "2026-08-01", null),
        app = AppInfo("0.3.0", 3),
        subject = subject,
        env = RunEnv(thermalStart, thermalMax, thermalEnd, battery, battery, charging, powerSave, 0),
        compatibility = compatibility
    )

    @Test
    fun `a clean run is measurement valid and comparison eligible`() {
        val run = RunAssembler.assemble(result(), listOf(configuredEvent()) + previewEvents(), profile, context())
        assertTrue(run.validity.measurementValid)
        assertTrue(run.validity.comparisonEligible)
        // The profile id still carries -draft, which blocks scoring only (5.3).
        assertFalse(run.validity.scoringEligible)
        assertEquals(listOf(ValidityFlags.PROFILE_DRAFT.code), run.validity.flags)
    }

    @Test
    fun `launch statistics use the nine non-warmup cycles`() {
        val run = RunAssembler.assemble(result(), listOf(configuredEvent()) + previewEvents(), profile, context())
        val open = run.metric("1.1")!!
        assertEquals(profile.expectedLaunchSamples, open.sampleCount)
        assertEquals(listOf(100.0), open.excludedWarmup)
        assertEquals(101.0, open.min!!, 0.001)
        assertEquals(109.0, open.max!!, 0.001)
    }

    @Test
    fun `the observation window feeds the cadence metrics and the warm-up frames do not`() {
        val events = listOf(configuredEvent()) + previewEvents()
        val obs = RunAssembler.observe(result(), events, profile)
        assertTrue("the 3 s warm-up frames are counted and dropped", obs.warmupFrames > 80)
        assertTrue(obs.observedFrames > 250)
        assertEquals(true, obs.cadenceFixed)
        val run = RunAssembler.assemble(result(), events, profile, context())
        assertEquals(33.333, run.metric("H.1")!!.value!!, 0.01)
    }

    @Test
    fun `an effective fps range other than the profile range is not comparable`() {
        val events = listOf(configuredEvent()) + previewEvents(fpsRange = "[15, 30]")
        val run = RunAssembler.assemble(result(), events, profile, context())
        assertTrue(run.validity.measurementValid)
        assertFalse(run.validity.comparisonEligible)
        assertTrue(ValidityFlags.CADENCE_NOT_FIXED.code in run.validity.flags)
    }

    @Test
    fun `a window without any reported range does not raise the cadence flag`() {
        val events = listOf(configuredEvent()) + previewEvents(fpsRange = null)
        assertNull(RunAssembler.observe(result(), events, profile).cadenceFixed)
        val run = RunAssembler.assemble(result(), events, profile, context())
        assertFalse(ValidityFlags.CADENCE_NOT_FIXED.code in run.validity.flags)
    }

    @Test
    fun `a run that never observed reports the H metrics as not run and is invalid`() {
        val noObservation = result().copy(observeSession = null, observeStartNs = null, observeEndNs = null, hardFailure = "FIRST_FRAME_timeout")
        val run = RunAssembler.assemble(noObservation, emptyList(), profile, context())
        assertFalse(run.validity.measurementValid)
        assertTrue(ValidityFlags.HARD_FAILURE.code in run.validity.flags)
        assertTrue(ValidityFlags.INSUFFICIENT_SAMPLES.code in run.validity.flags)
        assertEquals(dev.cameradoctor.diagnosis.UnknownReason.NOT_RUN, run.metric("H.1")!!.unknownReason)
    }

    @Test
    fun `a hard failure after a supported preflight is recorded as a preflight mismatch`() {
        val run = RunAssembler.assemble(
            result(hardFailure = "CONFIGURE_timeout"), listOf(configuredEvent()) + previewEvents(), profile, context()
        )
        assertTrue(ValidityFlags.PREFLIGHT_MISMATCH.code in run.validity.flags)
        assertTrue(ValidityFlags.HARD_FAILURE.code in run.validity.flags)
    }

    @Test
    fun `the effective conditions come from what the camera configured, not from the profile`() {
        // A fixed-focus camera runs AF OFF; the profile asks for CONTINUOUS_PICTURE and that is allowed (3.1).
        val events = listOf(configuredEvent(afMode = 0)) + previewEvents()
        val conditions = RunAssembler.effectiveConditions(events, session, profile)
        assertEquals("OFF", conditions["af_mode"])
        assertEquals("[30,30]", conditions["fps_range"])
        assertEquals("1920x1080", conditions["preview_size"])
    }

    @Test
    fun `effective conditions fall back to the profile when nothing was recorded`() {
        val conditions = RunAssembler.effectiveConditions(emptyList(), null, profile)
        assertEquals("CONTINUOUS_PICTURE", conditions["af_mode"])
        assertEquals("[30,30]", conditions["fps_range"])
    }

    @Test
    fun `a fixed fps range gives the expected interval and a variable one does not`() {
        assertEquals(33.333, RunAssembler.fixedFpsExpectedMs(profile)!!, 0.001)
        assertNull(RunAssembler.fixedFpsExpectedMs(profile.copy(fpsRange = "[15,30]")))
    }

    @Test
    fun `raw keeps the cycles, the stills and the observation summary`() {
        val run = RunAssembler.assemble(result(), listOf(configuredEvent()) + previewEvents(), profile, context())
        @Suppress("UNCHECKED_CAST")
        val cycles = run.raw["launch_cycles"] as List<Map<String, Any?>>
        assertEquals(profile.launchIterations, cycles.size)
        assertEquals(true, cycles.first()["warmup"])
        @Suppress("UNCHECKED_CAST")
        val stills = run.raw["stills"] as List<Map<String, Any?>>
        assertEquals(profile.stillCount, stills.size)
        @Suppress("UNCHECKED_CAST")
        val observation = run.raw["observation"] as Map<String, Any?>
        assertEquals(0, observation["stall_count"])
        assertEquals(session, run.raw["observe_session"])
    }

    @Test
    fun `a window whose frames are all warm-up leaves no observation samples`() {
        // The camera delivered results during the 3 s warm-up and then stopped: the observation window is empty
        // and the warm-up frames must not be reused as if they had been observed (PR #14 review).
        val warmupOnly = previewEvents().filter { it.atNs < observeStartNs }
        val obs = RunAssembler.observe(result(), warmupOnly, profile)
        assertEquals(0, obs.observedFrames)
        val run = RunAssembler.assemble(result(), listOf(configuredEvent()) + warmupOnly, profile, context())
        assertFalse(run.validity.measurementValid)
        assertTrue(ValidityFlags.INSUFFICIENT_SAMPLES.code in run.validity.flags)
        assertNull(run.metric("H.1")!!.value)
    }

    @Test
    fun `3A convergence is measured from the first result even when it precedes the first YUV`() {
        // A result at 100 ms is SEARCHING, the first YUV image only arrives at 120 ms, and AE converges at
        // 133 ms. Starting the window at the YUV image would report the 33 ms convergence as 0 ms.
        val start = firstFrameNs
        val events = listOf(
            Event(start - 5_000_000, session, "capture_started", frame = 0L, sensorNs = start),
            Event(start, session, "capture_result", frame = 0L, sensorNs = start,
                values = mapOf("intervalMs" to 33.333, "ae" to 1, "af" to 4, "awb" to 2, "afMode" to 4)),
            Event(start + 28_000_000, session, "capture_started", frame = 1L, sensorNs = start + 33_333_000),
            Event(start + 33_333_000, session, "capture_result", frame = 1L, sensorNs = start + 33_333_000,
                values = mapOf("intervalMs" to 33.333, "ae" to 2, "af" to 4, "awb" to 2, "afMode" to 4))
        ) + previewEvents().filter { it.atNs > start + 33_333_000 }
        // The first YUV image is recorded after the first result, which is what the runner reports as
        // observeFirstFrameNs; the 3A window must still open at the result.
        val withLateYuv = result().copy(observeFirstFrameNs = start + 20_000_000)
        val obs = RunAssembler.observe(withLateYuv, events, profile)
        val ae = obs.observation!!.samples.first { it.id == "H.6" }
        assertEquals(33.333, ae.value!!, 0.1)
    }

    @Test
    fun `thermal throttling in the middle of a run blocks comparison even when it cools down`() {
        val run = RunAssembler.assemble(
            result(), listOf(configuredEvent()) + previewEvents(), profile,
            context(thermalStart = 0, thermalMax = 3, thermalEnd = 0)
        )
        assertTrue(run.validity.measurementValid)
        assertFalse(run.validity.comparisonEligible)
        assertTrue(ValidityFlags.THERMAL_HIGH.code in run.validity.flags)
    }
}
