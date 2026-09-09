package dev.cameradoctor.benchmark

import dev.cameradoctor.diagnosis.jsonName

/**
 * How the camera was launched for the launch metrics. WARM_REOPEN is the only mode in v0.3: the same process
 * opens and closes the camera repeatedly (METRICS.md 0.2 "warm_sequence"). PROCESS_COLD is reserved for a
 * separate profile and is never used by camera2-standard-v1.
 */
enum class LaunchMode { WARM_REOPEN, PROCESS_COLD }

/**
 * Benchmark Profile (docs/PLAN-BenchMarker-v0.3.md chapter 3). Immutable description of how a run was captured.
 * Every run JSON stores the id and the full content. Changing any value after the id is confirmed means a new id.
 */
data class BenchmarkProfile(
    val id: String,
    val engine: String,
    val previewSize: String,
    val yuvSize: String,
    val stillFormat: String,
    val stillSize: String,
    val fpsRange: String,
    val zsl: Boolean,
    val trigger: Boolean,
    val afMode: String,
    val launchMode: LaunchMode,
    val launchIterations: Int,
    val warmupMs: Long,
    val observeMs: Long,
    val stillCount: Int,
    val excludeFirst: Boolean
) {
    /** Draft profiles may still change; their runs are never used for scoring (3.5). */
    val isDraft: Boolean get() = id.endsWith("-draft")

    /** Stable-order condition string, used for logs and as part of stored keys. */
    val conditionsKey: String
        get() = listOf(
            "engine=$engine", "preview=$previewSize", "yuv=$yuvSize", "still=$stillFormat@$stillSize", "fps=$fpsRange",
            "zsl=${if (zsl) "on" else "off"}", "trigger=${if (trigger) "on" else "off"}", "af=$afMode",
            "launch=${launchMode.jsonName}"
        ).joinToString(",")

    private val excluded: Int get() = if (excludeFirst) 1 else 0

    /** Valid launch samples the profile promises: iterations minus the warm-up cycle. */
    val expectedLaunchSamples: Int get() = launchIterations - excluded
    /** Valid still latency samples: captures minus the warm-up capture. */
    val expectedStillSamples: Int get() = stillCount - excluded
    /** Valid shot-to-shot intervals: (captures - 1) intervals minus the warm-up interval (METRICS.md 2.5 note). */
    val expectedShotToShotSamples: Int get() = (stillCount - 1) - excluded

    fun toJsonMap(): Map<String, Any?> = mapOf(
        "id" to id, "engine" to engine, "preview_size" to previewSize, "yuv_size" to yuvSize,
        "still_format" to stillFormat, "still_size" to stillSize, "fps_range" to fpsRange,
        "zsl" to zsl, "trigger" to trigger, "af_mode" to afMode, "launch_mode" to launchMode.jsonName,
        "launch_iterations" to launchIterations, "warmup_ms" to warmupMs, "observe_ms" to observeMs,
        "still_count" to stillCount, "exclude_first" to excludeFirst
    )

    companion object {
        /**
         * The standard profile, confirmed on 2026-09-10 (plan 3.5). The M2 device check on a Galaxy S25+ ran
         * three rear-main runs with the 1080p YUV stream and reported zero stalls in every observation window,
         * so the 1080p condition stands and the id lost its "-draft" suffix. From here the values never change:
         * a different condition needs a new id (v2), because runs of one id must stay comparable forever.
         */
        val CAMERA2_STANDARD_V1 = BenchmarkProfile(
            id = "camera2-standard-v1",
            engine = "camera2",
            previewSize = "1920x1080",
            yuvSize = "1920x1080",
            stillFormat = "jpeg",
            stillSize = "1920x1080",
            fpsRange = "[30,30]",
            zsl = false,
            trigger = false,
            afMode = "CONTINUOUS_PICTURE",
            launchMode = LaunchMode.WARM_REOPEN,
            launchIterations = 10,
            warmupMs = 3000,
            observeMs = 10_000,
            stillCount = 10,
            excludeFirst = true
        )

        /**
         * Profiles this app defines, by id. A stored run whose profile id is a confirmed (non-draft) canonical id
         * must carry exactly this definition, otherwise the file is rejected (BenchmarkReportCodec).
         */
        val CANONICAL: Map<String, BenchmarkProfile> = listOf(CAMERA2_STANDARD_V1).associateBy { it.id }

        fun canonical(id: String): BenchmarkProfile? = CANONICAL[id]

        /**
         * Every profile field is part of the comparison contract, so a missing or mistyped field is an
         * IllegalArgumentException with the key name rather than a silently substituted default: a profile read
         * back with a guessed stream size would compare runs that must not be compared.
         */
        fun fromJsonMap(m: Map<String, Any?>): BenchmarkProfile = BenchmarkProfile(
            id = JsonMaps.reqString(m, "id", "profile"),
            engine = JsonMaps.reqString(m, "engine", "profile"),
            previewSize = JsonMaps.reqString(m, "preview_size", "profile"),
            yuvSize = JsonMaps.reqString(m, "yuv_size", "profile"),
            stillFormat = JsonMaps.reqString(m, "still_format", "profile"),
            stillSize = JsonMaps.reqString(m, "still_size", "profile"),
            fpsRange = JsonMaps.reqString(m, "fps_range", "profile"),
            zsl = JsonMaps.reqBoolean(m, "zsl", "profile"),
            trigger = JsonMaps.reqBoolean(m, "trigger", "profile"),
            afMode = JsonMaps.reqString(m, "af_mode", "profile"),
            launchMode = JsonMaps.enum("launch_mode", m, LaunchMode.values())
                ?: throw IllegalArgumentException("profile.launch_mode missing or unknown: ${m["launch_mode"]}"),
            launchIterations = JsonMaps.reqInt(m, "launch_iterations", "profile"),
            warmupMs = JsonMaps.reqLong(m, "warmup_ms", "profile"),
            observeMs = JsonMaps.reqLong(m, "observe_ms", "profile"),
            stillCount = JsonMaps.reqInt(m, "still_count", "profile"),
            excludeFirst = JsonMaps.reqBoolean(m, "exclude_first", "profile")
        )
    }
}
