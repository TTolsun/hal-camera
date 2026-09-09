package dev.cameradoctor.benchmark

import dev.cameradoctor.check.CameraEndpoint
import dev.cameradoctor.check.LensRole
import dev.cameradoctor.diagnosis.UnknownReason
import dev.cameradoctor.diagnosis.jsonName
import java.io.File

/**
 * Data contract of Camera BenchMarker v0.3 (docs/PLAN-BenchMarker-v0.3.md chapter 5). Pure Kotlin.
 * Every type here serializes to a Map<String, Any?> with snake_case keys; org.json is used only at the file
 * boundary (BenchmarkReport) so the whole contract is testable on the JVM.
 */

/**
 * Comparison compatibility contract. The profile says how the camera was driven; the metric definition version
 * says how those events were turned into numbers. Two runs are compared only when the whole id is equal.
 */
data class MeasurementContract(
    val profileId: String,
    val metricDefinitionVersion: String,
    val statsMethod: String,
    val clock: String
) {
    val comparisonContractId: String get() = "$profileId|$metricDefinitionVersion|$statsMethod|$clock"

    fun toJsonMap(): Map<String, Any?> = mapOf(
        "profile_id" to profileId, "metric_definition_version" to metricDefinitionVersion,
        "stats_method" to statsMethod, "clock" to clock, "comparison_contract_id" to comparisonContractId
    )

    companion object {
        /** Bumped whenever the computation of any metric changes. metrics-0.3 = METRICS.md v0.2 table + H.10. */
        const val METRIC_DEFINITION_VERSION = "metrics-0.3"
        const val STATS_METHOD = "nearest_rank"
        const val CLOCK = "elapsedRealtimeNanos"

        fun forProfile(profile: BenchmarkProfile) = MeasurementContract(profile.id, METRIC_DEFINITION_VERSION, STATS_METHOD, CLOCK)

        fun fromJsonMap(m: Map<String, Any?>) = MeasurementContract(
            m["profile_id"] as String, m["metric_definition_version"] as String, m["stats_method"] as String, m["clock"] as String
        )
    }
}

enum class Category { LAUNCH, PREVIEW, CAPTURE, STABILITY, THREE_A, RESOURCE, SWITCH }

enum class RegressionState { IMPROVED, STABLE, REGRESSED, UNKNOWN }

/**
 * One entry of metrics[] in the run JSON. Statistics come from BenchmarkEvaluator; baseline and reference
 * comparison fields are filled by the RegressionDetector (M4) and stay null / UNKNOWN(no_baseline) until then.
 *
 * [sampleCount] is always the number of raw samples the metric was computed from (JSON key "n").
 * [samples] is stored only for metrics whose sample count is bounded by the profile (launch, still, 3A);
 * observation-window metrics keep null and are recomputed from events.
 */
data class BenchmarkMetric(
    val id: String,
    val category: Category,
    val unit: String,
    val value: Double?,
    val p50: Double?,
    val p95: Double?,
    val min: Double?,
    val max: Double?,
    val sampleCount: Int,
    val samples: List<Double>?,
    val excludedWarmup: List<Double>?,
    /** 3A convergence did not complete inside the observation window; [value] is then the window length. */
    val timeout: Boolean = false,

    val baselineValue: Double? = null,
    val deltaPct: Double? = null,
    val regression: RegressionState = RegressionState.UNKNOWN,

    val referenceValue: Double? = null,
    val referenceDeltaPct: Double? = null,

    val score: Double? = null,
    val unknownReason: UnknownReason? = null
) {
    fun toJsonMap(): Map<String, Any?> = mapOf(
        "id" to id, "category" to category.jsonName, "unit" to unit,
        "value" to value, "p50" to p50, "p95" to p95, "min" to min, "max" to max, "n" to sampleCount,
        "samples" to samples, "excluded_warmup" to excludedWarmup, "timeout" to timeout,
        "baseline_value" to baselineValue, "delta_pct" to deltaPct, "regression" to regression.jsonName,
        "reference_value" to referenceValue, "reference_delta_pct" to referenceDeltaPct,
        "score" to score, "unknown_reason" to unknownReason?.jsonName
    )

    companion object {
        fun fromJsonMap(m: Map<String, Any?>): BenchmarkMetric = BenchmarkMetric(
            id = m["id"] as String,
            category = JsonMaps.enum("category", m, Category.values()) ?: Category.RESOURCE,
            unit = m["unit"] as? String ?: "",
            value = JsonMaps.d(m["value"]), p50 = JsonMaps.d(m["p50"]), p95 = JsonMaps.d(m["p95"]),
            min = JsonMaps.d(m["min"]), max = JsonMaps.d(m["max"]),
            sampleCount = JsonMaps.i(m["n"]) ?: 0,
            samples = JsonMaps.doubles(m["samples"]), excludedWarmup = JsonMaps.doubles(m["excluded_warmup"]),
            timeout = m["timeout"] as? Boolean ?: false,
            baselineValue = JsonMaps.d(m["baseline_value"]), deltaPct = JsonMaps.d(m["delta_pct"]),
            regression = JsonMaps.enum("regression", m, RegressionState.values()) ?: RegressionState.UNKNOWN,
            referenceValue = JsonMaps.d(m["reference_value"]), referenceDeltaPct = JsonMaps.d(m["reference_delta_pct"]),
            score = JsonMaps.d(m["score"]),
            unknownReason = JsonMaps.enum("unknown_reason", m, UnknownReason.values())
        )
    }
}

/**
 * The configuration under test. The benchmark app's own version lives in [AppInfo]; every field here describes
 * the subject (a camera HAL commit, a platform build label, a manifest revision), never the app.
 */
data class SubjectLabel(
    val subjectBuildLabel: String? = null,
    val subjectCommit: String? = null,
    val subjectBranch: String? = null,
    val note: String? = null
) {
    val isUnlabeled: Boolean get() = subjectBuildLabel.isNullOrBlank() && subjectCommit.isNullOrBlank()

    fun toJsonMap(): Map<String, Any?> = mapOf(
        "build_label" to subjectBuildLabel, "commit" to subjectCommit, "branch" to subjectBranch, "note" to note
    )

    companion object {
        fun fromJsonMap(m: Map<String, Any?>?) = SubjectLabel(
            JsonMaps.s(m?.get("build_label")), JsonMaps.s(m?.get("commit")), JsonMaps.s(m?.get("branch")), JsonMaps.s(m?.get("note"))
        )
    }
}

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val buildDisplay: String,
    val buildIncremental: String?,
    val fingerprint: String,
    val vendorFingerprint: String?,
    val sdk: Int,
    val securityPatch: String?,
    val cameraInfoVersion: String?
) {
    fun toJsonMap(): Map<String, Any?> = mapOf(
        "manufacturer" to manufacturer, "model" to model, "build_display" to buildDisplay, "build_incremental" to buildIncremental,
        "fingerprint" to fingerprint, "vendor_fingerprint" to vendorFingerprint, "sdk" to sdk,
        "security_patch" to securityPatch, "camera_info_version" to cameraInfoVersion
    )

    companion object {
        fun fromJsonMap(m: Map<String, Any?>) = DeviceInfo(
            m["manufacturer"] as? String ?: "", m["model"] as? String ?: "", m["build_display"] as? String ?: "",
            JsonMaps.s(m["build_incremental"]), m["fingerprint"] as? String ?: "", JsonMaps.s(m["vendor_fingerprint"]),
            JsonMaps.i(m["sdk"]) ?: 0, JsonMaps.s(m["security_patch"]), JsonMaps.s(m["camera_info_version"])
        )
    }
}

data class AppInfo(val versionName: String, val versionCode: Int) {
    fun toJsonMap(): Map<String, Any?> = mapOf("version_name" to versionName, "version_code" to versionCode)

    companion object {
        fun fromJsonMap(m: Map<String, Any?>) = AppInfo(m["version_name"] as? String ?: "", JsonMaps.i(m["version_code"]) ?: 0)
    }
}

/** Environment around the run. thermal values are PowerManager.THERMAL_STATUS_* ints; null when unavailable. */
data class RunEnv(
    val thermalStart: Int?,
    val thermalMax: Int?,
    val thermalEnd: Int?,
    val batteryStart: Int?,
    val batteryEnd: Int?,
    val charging: Boolean?,
    val powerSaveMode: Boolean?,
    val rotation: Int?
) {
    fun toJsonMap(): Map<String, Any?> = mapOf(
        "thermal_start" to thermalStart, "thermal_max" to thermalMax, "thermal_end" to thermalEnd,
        "battery_start" to batteryStart, "battery_end" to batteryEnd, "charging" to charging,
        "power_save_mode" to powerSaveMode, "rotation" to rotation
    )

    companion object {
        fun fromJsonMap(m: Map<String, Any?>?) = RunEnv(
            JsonMaps.i(m?.get("thermal_start")), JsonMaps.i(m?.get("thermal_max")), JsonMaps.i(m?.get("thermal_end")),
            JsonMaps.i(m?.get("battery_start")), JsonMaps.i(m?.get("battery_end")), m?.get("charging") as? Boolean,
            m?.get("power_save_mode") as? Boolean, JsonMaps.i(m?.get("rotation"))
        )
    }
}

/** Result of the ProfileCompatibility preflight (3.6). [method] is "device_setup", "static_table" or "none". */
data class Compatibility(
    val method: String,
    val supported: Boolean,
    val reasons: List<String>,
    val frameBudgetOk: Boolean?
) {
    fun toJsonMap(): Map<String, Any?> = mapOf(
        "method" to method, "supported" to supported, "reasons" to reasons, "frame_budget_ok" to frameBudgetOk
    )

    companion object {
        val NOT_CHECKED = Compatibility("none", true, emptyList(), null)
        fun fromJsonMap(m: Map<String, Any?>?) = if (m == null) NOT_CHECKED else Compatibility(
            m["method"] as? String ?: "none", m["supported"] as? Boolean ?: true,
            JsonMaps.strings(m["reasons"]), m["frame_budget_ok"] as? Boolean
        )
    }
}

/** Pointer to another run used as baseline or reference, with the build identity comparison against it. */
data class RunRef(
    val runId: String,
    val sameProfile: Boolean,
    val identity: BuildIdentityComparison
) {
    fun toJsonMap(): Map<String, Any?> = mapOf("run_id" to runId, "same_profile" to sameProfile, "identity" to identity.toJsonMap())

    companion object {
        fun fromJsonMap(m: Map<String, Any?>?): RunRef? {
            if (m == null) return null
            val id = m["run_id"] as? String ?: return null
            return RunRef(id, m["same_profile"] as? Boolean ?: false, BuildIdentityComparison.fromJsonMap(JsonMaps.map(m["identity"])))
        }
    }
}

/** One benchmark run as stored in files/benchmarks/<runId>.json (schema 3). */
data class BenchmarkRun(
    val runId: String,
    val exportedAtUtc: String,
    val aborted: String?,
    val profile: BenchmarkProfile,
    val contract: MeasurementContract,
    val compatibility: Compatibility,
    val effectiveConditions: Map<String, String>,
    val endpoint: CameraEndpoint,
    val device: DeviceInfo,
    val app: AppInfo,
    val subject: SubjectLabel,
    val env: RunEnv,
    val validity: RunValidity,
    val baselineRef: RunRef?,
    val referenceRef: RunRef?,
    val metrics: List<BenchmarkMetric>,
    val regressionRuleVersion: String = RegressionRules.VERSION,
    val scoringRuleVersion: String? = null,
    val endpointScore: Int? = null,
    val raw: Map<String, Any?> = emptyMap(),
    val file: File? = null
) {
    val regressedCount: Int get() = metrics.count { it.regression == RegressionState.REGRESSED }
    val improvedCount: Int get() = metrics.count { it.regression == RegressionState.IMPROVED }
    val stableCount: Int get() = metrics.count { it.regression == RegressionState.STABLE }
    val unknownCount: Int get() = metrics.count { it.regression == RegressionState.UNKNOWN }

    fun metric(id: String): BenchmarkMetric? = metrics.firstOrNull { it.id == id }
}

/** Map helpers tolerant of both Kotlin-built maps and maps converted from org.json (Int/Long/Double mixes). */
object JsonMaps {
    fun d(v: Any?): Double? = (v as? Number)?.toDouble()?.takeUnless { it.isNaN() }
    fun i(v: Any?): Int? = (v as? Number)?.toInt()
    fun l(v: Any?): Long? = when (v) { is Number -> v.toLong(); is String -> v.toLongOrNull(); else -> null }
    fun s(v: Any?): String? = (v as? String)?.takeIf { it.isNotEmpty() && it != "null" }
    fun doubles(v: Any?): List<Double>? = (v as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }
    fun strings(v: Any?): List<String> = (v as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
    @Suppress("UNCHECKED_CAST")
    fun map(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>
    fun <E : Enum<E>> enum(key: String, m: Map<String, Any?>, values: Array<E>): E? =
        s(m[key])?.let { name -> values.firstOrNull { it.jsonName == name } }

    fun endpointToMap(e: CameraEndpoint): Map<String, Any?> = e.toJsonMap()

    fun endpointFromMap(m: Map<String, Any?>): CameraEndpoint = CameraEndpoint(
        logicalCameraId = m["logicalCameraId"] as? String ?: "?",
        physicalCameraId = s(m["physicalCameraId"]),
        role = (m["role"] as? String)?.let { r -> LensRole.values().firstOrNull { it.name == r } } ?: LensRole.UNKNOWN,
        facing = i(m["facing"]) ?: -1,
        independentlyOpenable = m["independentlyOpenable"] as? Boolean ?: true,
        selectableByZoom = m["selectableByZoom"] as? Boolean ?: false,
        exposedToCameraX = m["exposedToCameraX"] as? Boolean,
        equivalentFocalMm = d(m["equivalentFocalMm"]),
        timestampSource = i(m["timestampSource"]),
        hardwareLevel = i(m["hardwareLevel"]),
        zoomRatioMin = d(m["zoomRatioMin"])?.toFloat(),
        zoomRatioMax = d(m["zoomRatioMax"])?.toFloat()
    )
}
