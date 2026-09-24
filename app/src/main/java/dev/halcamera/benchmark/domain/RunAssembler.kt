package dev.halcamera.benchmark.domain

import dev.halcamera.metrics.MetricExtractor
import dev.halcamera.metrics.RecordMetrics
import dev.halcamera.metrics.jsonName
import dev.halcamera.telemetry.Event

/**
 * Turns a finished [BenchmarkRunner.Result] plus the recorded events into a [BenchmarkRun].
 * [BenchmarkReportCodec] serializes that run using schema 5; file I/O remains outside this assembler.
 * Pure Kotlin so the whole path from raw samples to the stored contract is unit-testable; the Android facts
 * (device, app, thermal, battery) are gathered by [dev.halcamera.benchmark.BenchmarkActivity] and passed in.
 */
object RunAssembler {

    /** The Android-side facts the assembler cannot read itself. */
    data class Context(
        val exportedAtUtc: String,
        val device: DeviceInfo,
        val app: AppInfo,
        val subject: SubjectLabel,
        val env: RunEnv,
        val compatibility: Compatibility,
        val deviceInstanceId: String? = null
    )

    /**
     * Observation-window statistics recomputed from the events, plus the numbers the validity rules need.
     * [warmupFrames] are the frames between the first preview frame and the start of the observation window:
     * they are excluded from H.1 - H.5 but still count for the 3A convergence, which is measured from the first
     * result of the observation session (3.2).
     * [observedFrames] counts the steady frames after warm-up removal and feeds the run validity rules.
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
        // 3A convergence is measured from the first capture result of the observation session (3.2), which can
        // precede the first YUV image, so the frames are collected from the whole session rather than from
        // observeFirstFrameNs. The warm-up frames are then dropped for the cadence metrics only.
        val frames = extractor.frames(events, session, Long.MIN_VALUE, end)
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

    /**
     * One [RecordSample] per recording cycle: the runner's two latencies, plus the cadence recomputed from that
     * cycle's frames. The frames are picked by the cycle's request tag rather than by its time window, so a late
     * result of the previous cycle can never be counted here (docs/PLAN-Recording-v0.1.md 7).
     */
    fun records(result: BenchmarkRunner.Result, events: List<Event>, profile: BenchmarkProfile): List<RecordSample> {
        val session = result.observeSession ?: return emptyList()
        val expectedMs = profile.recordFps?.takeIf { it > 0 }?.let { 1000.0 / it }
        return result.records.map { cycle ->
            val frames = RecordMetrics.frames(events, session, cycle.requestTag)
            RecordSample(
                iteration = cycle.iteration,
                warmup = cycle.warmup,
                failed = cycle.failed,
                startToCallbackMs = cycle.startToCallbackMs,
                stopLatencyMs = cycle.stopLatencyMs,
                cadence = if (frames.isEmpty()) null else RecordMetrics.cadence(frames, expectedMs)
            )
        }
    }

    /** raw.record of the run JSON: one entry per cycle, keeping what the aggregated metrics cannot show. */
    fun rawRecords(cycles: List<RecordCycle>, samples: List<RecordSample>): List<Map<String, Any?>> {
        val byIteration = samples.associateBy { it.iteration }
        return cycles.map { cycle ->
            val cadence = byIteration[cycle.iteration]?.cadence
            cycle.toJsonMap() + mapOf(
                "frames" to cadence?.frames,
                "intervals" to cadence?.intervals?.size,
                "compared_intervals" to cadence?.comparedIntervals,
                "excluded_intervals" to cadence?.excludedIntervals,
                "anomaly_count" to cadence?.anomalyCount,
                "anomaly_unknown_reason" to cadence?.anomalyUnknownReason?.jsonName,
                // The window minimum is kept per cycle: the aggregated 3.4 reports the median of the cycle
                // medians, which cannot show that one window inside one cycle dropped.
                "window_fps_p50" to cadence?.windowFpsP50,
                "window_fps_min" to cadence?.windowFpsMin,
                "windows" to cadence?.windows,
                "jitter_stddev_ms" to cadence?.jitterStdDevMs,
                "jitter_p95_ms" to cadence?.jitterP95Ms
            )
        }
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
        val records = records(result, events, profile)
        val metrics = BenchmarkEvaluator(profile).evaluate(
            BenchmarkEvaluator.Input(
                cycles = result.cycles, stills = result.stills, observation = obs.observation,
                callbackFailures = obs.callbackFailures, observed = result.observed && obs.observation != null,
                records = records, recordUnsupported = result.recordUnsupported
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
                recordSamples = records.count { it.usable },
                expectedRecordSamples = profile.expectedRecordSamples,
                cadenceFixed = obs.cadenceFixed,
                thermalStart = context.env.thermalStart,
                thermalMax = context.env.thermalMax,
                thermalEnd = context.env.thermalEnd,
                powerSaveMode = context.env.powerSaveMode,
                charging = context.env.charging,
                batteryStart = context.env.batteryStart,
                profileDraft = profile.isDraft,
                debuggableBuild = context.app.debuggable,
                subjectLabeled = !context.subject.isUnlabeled
            )
        )
        return ScoreComposer.apply(BenchmarkRun(
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
                "device_instance_id" to context.deviceInstanceId,
                "launch_cycles" to result.cycles.map { it.toJsonMap() },
                "stills" to result.stills.map { it.toJsonMap() },
                "observation" to rawObservation(obs.observation),
                // Absent, not empty, for a profile without a RECORD stage: its run files stay what they were.
                "record" to if (!profile.records) null else rawRecords(result.records, records),
                "record_unsupported" to if (!profile.records) null else result.recordUnsupported,
                "hard_failure" to result.hardFailure,
                "sessions" to result.sessions,
                "observe_session" to result.observeSession,
                "observe_warmup_frames" to obs.warmupFrames
            )
        ), S25PlusScoreDraft.calibration)
    }
}
