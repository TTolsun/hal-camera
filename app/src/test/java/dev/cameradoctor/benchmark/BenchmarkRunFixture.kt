package dev.cameradoctor.benchmark

import dev.cameradoctor.check.CameraEndpoint
import dev.cameradoctor.check.LensRole
import dev.cameradoctor.diagnosis.UnknownReason

/**
 * Minimal runs for the M4 comparison tests. Only the fields the comparison actually reads are meaningful:
 * contract, endpoint, env, validity and metric values. Everything else is a fixed placeholder so that a test
 * changing one condition reads as one line.
 */
object BenchmarkRunFixture {

    val DEVICE = DeviceInfo("samsung", "SM-S936N", "BP4A.251205.006", "inc", "samsung/fp", "vendor/fp", 36, "2025-12-01", "hal-1")
    val APP = AppInfo("0.3.0", 3)

    fun endpoint(key: String = "0") = CameraEndpoint(key, null, LensRole.MAIN, 1, true, true, null, 24.0, 1, 3, 0.6f, 10f)

    fun metric(id: String, value: Double?, timeout: Boolean = false, unknownReason: UnknownReason? = null): BenchmarkMetric {
        val info = BenchmarkMetricCatalog.info(id)
        return BenchmarkMetric(
            id = id, category = info?.category ?: Category.RESOURCE, unit = info?.unit ?: "ms",
            value = value, p50 = value, p95 = value, min = value, max = value,
            sampleCount = if (value == null) 0 else 9, samples = null, excludedWarmup = null,
            timeout = timeout, unknownReason = unknownReason
        )
    }

    /** [flags] are applied through the real table so eligibility is never hand-set (5.3). */
    fun run(
        runId: String = "20260910-120000-000",
        metrics: List<BenchmarkMetric> = emptyList(),
        endpointKey: String = "0",
        profile: BenchmarkProfile = BenchmarkProfile.CAMERA2_STANDARD_V1,
        metricDefinitionVersion: String = MeasurementContract.METRIC_DEFINITION_VERSION,
        thermalMax: Int? = 1,
        powerSaveMode: Boolean? = false,
        charging: Boolean? = false,
        exposureLoad: Double? = 1.0e6,
        flags: List<ValidityFlag> = emptyList(),
        subject: SubjectLabel = SubjectLabel("SW42", "a8f29c1"),
        device: DeviceInfo = DEVICE,
        app: AppInfo = APP
    ): BenchmarkRun = BenchmarkRun(
        runId = runId,
        exportedAtUtc = "2026-09-10T03:00:00.000Z",
        aborted = null,
        profile = profile,
        contract = MeasurementContract(profile.id, metricDefinitionVersion, MeasurementContract.STATS_METHOD, MeasurementContract.CLOCK),
        compatibility = Compatibility("device_setup", true, emptyList(), true),
        effectiveConditions = mapOf("af_mode" to "CONTINUOUS_PICTURE", "fps_range" to "[30,30]"),
        endpoint = endpoint(endpointKey),
        device = device, app = app, subject = subject,
        env = RunEnv(0, thermalMax, thermalMax, 82, 80, charging, powerSaveMode, 0),
        validity = RunValidity.from(flags),
        baselineRef = null, referenceRef = null,
        metrics = metrics,
        raw = if (exposureLoad == null) emptyMap() else mapOf("observation" to mapOf("exposure_load_p50" to exposureLoad))
    )
}
