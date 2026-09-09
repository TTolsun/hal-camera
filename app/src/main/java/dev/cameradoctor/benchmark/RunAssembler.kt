package dev.cameradoctor.benchmark

import dev.cameradoctor.diagnosis.MetricExtractor
import dev.cameradoctor.telemetry.Event

/**
 * Turns a finished [BenchmarkRunner.Result] plus the recorded events into a [BenchmarkRun] (schema 3).
 * Pure Kotlin so the whole path from raw samples to the stored contract is unit-testable; the Android facts
 * (device, app, thermal, battery) are gathered by [BenchmarkActivity] and passed in.
 */
object RunAssembler {

    /** The Android-side facts the assembler cannot read itself. */
    data class Context(
        val exportedAtUtc: String,
        val device: DeviceInfo,
        val app: AppInfo,
        val subject: SubjectLabel,
        val env: RunEnv,
        val compatibility: Compatibility
    )

    /**
     * Observation-window statistics recomputed from the events, plus the numbers the validity rules need.
     * [warmupFrames] are the frames between the first preview frame and the start of the observation window:
     * they are excluded from H.1 - H.5 but still count for the 3A convergence, which is measured from the first
     * result of the observation session (3.2).
     */
    data class ObservationInput(
        val observation: MetricExtractor.Observation?,
        val callbackFailures: Int,
        val warmupFrames: Int,
        val observedFrames: Int,
        val cadenceFixed: Boolean?
    )

    /** Recomputes the observation from the events of the observation session. Null result when the run never observed. */
    fun observe(result: BenchmarkRunner.Result, events: List<Event>, profile: BenchmarkProfile): ObservationInput {
        val session = result.observeSession
        val start = result.observeStartNs
        val end = result.observeEndNs
        if (session == null || start == null || end == null) return ObservationInput(null, 0, 0, 0, null)
        val extractor = MetricExtractor(ValidityFlags.MIN_OBSERVED_FRAMES)
        // The 3A window opens at the first frame of the observation session, so the frames are taken from there
        // and the warm-up frames are dropped for the cadence metrics only.
        val from = result.observeFirstFrameNs ?: start
        val frames = extractor.frames(events, session, from, end)
        val warmup = frames.count { it.resultAtNs < start }
        val expectedMs = fixedFpsExpectedMs(profile)
        val observation = extractor.observe(frames, fixedFpsExpectedMs = expectedMs, warmupFrames = warmup)
        val failures = events.count {
            it.session == session && (it.kind == "capture_failed" || it.kind == "buffer_lost") && it.atNs in start..end
        }
        return ObservationInput(
            observation = observation,
            callbackFailures = failures,
            warmupFrames = warmup,
            observedFrames = observation.steadyFrames.size,
            cadenceFixed = cadenceFixed(events, session, start, end, profile)
        )
    }

    /** Expected interval of a fixed fps range, ms. Null when the profile does not pin a single fps. */
    fun fixedFpsExpectedMs(profile: BenchmarkProfile): Double? {
        val parts = profile.fpsRange.replace(" ", "").removePrefix("[").removeSuffix("]").split(",")
        if (parts.size != 2) return null
        val lower = parts[0].toIntOrNull() ?: return null
        val upper = parts[1].toIntOrNull() ?: return null
        return if (lower == upper && lower > 0) 1000.0 / lower else null
    }

    /**
     * CADENCE_NOT_FIXED (5.3): the effective fps range of the observation window must be the profile's range.
     * The value comes from the requests the camera actually reported (`request_observed`), not from what was
     * asked for. Null when no request in the window carried a range, so the flag is not raised on missing data.
     */
    fun cadenceFixed(events: List<Event>, session: String, fromNs: Long, toNs: Long, profile: BenchmarkProfile): Boolean? {
        val wanted = ProfileCompatibility.normalizeRange(profile.fpsRange)
        val seen = events.asSequence()
            .filter { it.session == session && it.kind == "request_observed" && it.atNs in fromNs..toNs }
            .mapNotNull { it.values["fpsRange"] as? String }
            .map { ProfileCompatibility.normalizeRange(it) }
            .toList()
        if (seen.isEmpty()) return null
        return seen.all { it == wanted }
    }

    /** raw.observation of the run JSON (chapter 6). */
    fun rawObservation(o: MetricExtractor.Observation?): Map<String, Any?>? = o?.let {
        mapOf(
            "frames" to it.frames.size, "steady_frames" to it.steadyFrames.size,
            "interval_p50_ms" to it.intervalP50, "stall_count" to it.stallCount,
            "three_a_stable" to it.threeAStable, "af_supported" to it.afSupported,
            "exposure_load_p50" to it.exposureLoadP50, "interval_jitter_ms" to it.intervalJitterMs
        )
    }

    /**
     * conditions.effective (chapter 6): what the camera actually ran with. AF is the one condition the profile
     * allows to differ (3.1): a fixed-focus camera runs AF OFF and that is recorded here rather than failing.
     */
    fun effectiveConditions(events: List<Event>, session: String?, profile: BenchmarkProfile): Map<String, String> {
        val configured = events.lastOrNull { it.session == session && it.kind == "session_configured" }?.values
        val af = (configured?.get("afMode") as? Number)?.toInt()
        val requested = events.asSequence()
            .filter { it.session == session && it.kind == "request_observed" }
            .mapNotNull { it.values["fpsRange"] as? String }.firstOrNull()
        return mapOf(
            "af_mode" to (af?.let(::afModeName) ?: profile.afMode),
            "fps_range" to ProfileCompatibility.normalizeRange(requested ?: profile.fpsRange),
            "preview_size" to (configured?.get("preview") as? String ?: profile.previewSize),
            "yuv_size" to (configured?.get("analysis") as? String ?: profile.yuvSize),
            "still_size" to (configured?.get("jpeg") as? String ?: profile.stillSize)
        )
    }

    /** CaptureRequest.CONTROL_AF_MODE values; the profile writes the same names. */
    fun afModeName(mode: Int): String = when (mode) {
        0 -> "OFF"; 1 -> "AUTO"; 2 -> "MACRO"; 3 -> "CONTINUOUS_VIDEO"; 4 -> "CONTINUOUS_PICTURE"; 5 -> "EDOF"
        else -> "UNKNOWN($mode)"
    }

    fun assemble(
        result: BenchmarkRunner.Result,
        events: List<Event>,
        profile: BenchmarkProfile,
        context: Context
    ): BenchmarkRun {
        val obs = observe(result, events, profile)
        val metrics = BenchmarkEvaluator(profile).evaluate(
            BenchmarkEvaluator.Input(
                cycles = result.cycles, stills = result.stills, observation = obs.observation,
                callbackFailures = obs.callbackFailures, observed = result.observed && obs.observation != null
            )
        )
        val validity = RunValidityEvaluator.evaluate(
            ValidityInputs(
                aborted = result.aborted,
                hardFailure = result.hardFailure != null,
                preflightSupported = context.compatibility.supported,
                // SUPPORTED at preflight and still a failure at configure time means the preflight was wrong (3.6).
                preflightMismatch = context.compatibility.supported && result.hardFailure != null,
                launchSamples = result.validLaunchSamples,
                stillSamples = result.validStillSamples,
                observedFrames = obs.observedFrames,
                expectedLaunchSamples = profile.expectedLaunchSamples,
                expectedStillSamples = profile.expectedStillSamples,
                cadenceFixed = obs.cadenceFixed,
                thermalStart = context.env.thermalStart,
                thermalMax = context.env.thermalMax,
                thermalEnd = context.env.thermalEnd,
                powerSaveMode = context.env.powerSaveMode,
                charging = context.env.charging,
                batteryStart = context.env.batteryStart,
                profileDraft = profile.isDraft,
                subjectLabeled = !context.subject.isUnlabeled
            )
        )
        return BenchmarkRun(
            runId = result.runId,
            exportedAtUtc = context.exportedAtUtc,
            aborted = result.aborted,
            profile = profile,
            contract = MeasurementContract.forProfile(profile),
            compatibility = context.compatibility,
            effectiveConditions = effectiveConditions(events, result.observeSession, profile),
            endpoint = result.endpoint,
            device = context.device,
            app = context.app,
            subject = context.subject,
            env = context.env,
            validity = validity,
            baselineRef = null,
            referenceRef = null,
            metrics = metrics,
            raw = mapOf(
                "launch_cycles" to result.cycles.map { it.toJsonMap() },
                "stills" to result.stills.map { it.toJsonMap() },
                "observation" to rawObservation(obs.observation),
                "hard_failure" to result.hardFailure,
                "sessions" to result.sessions,
                "observe_session" to result.observeSession,
                "observe_warmup_frames" to obs.warmupFrames
            )
        )
    }
}
