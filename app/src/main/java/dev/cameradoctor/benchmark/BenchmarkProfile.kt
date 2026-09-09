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
         * The standard profile. The id keeps the "-draft" suffix until M2 confirms the 1080p YUV stream on the
         * Galaxy S25+; after that the id is "camera2-standard-v1" and the values never change again (3.5).
         */
        val CAMERA2_STANDARD_V1 = BenchmarkProfile(
            id = "camera2-standard-v1-draft",
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

        fun fromJsonMap(m: Map<String, Any?>): BenchmarkProfile = BenchmarkProfile(
            id = m["id"] as String,
            engine = m["engine"] as String,
            previewSize = m["preview_size"] as String,
            yuvSize = m["yuv_size"] as String,
            stillFormat = m["still_format"] as String,
            stillSize = m["still_size"] as String,
            fpsRange = m["fps_range"] as String,
            zsl = m["zsl"] as Boolean,
            trigger = m["trigger"] as Boolean,
            afMode = m["af_mode"] as String,
            launchMode = LaunchMode.values().firstOrNull { it.jsonName == m["launch_mode"] } ?: LaunchMode.WARM_REOPEN,
            launchIterations = (m["launch_iterations"] as Number).toInt(),
            warmupMs = (m["warmup_ms"] as Number).toLong(),
            observeMs = (m["observe_ms"] as Number).toLong(),
            stillCount = (m["still_count"] as Number).toInt(),
            excludeFirst = m["exclude_first"] as Boolean
        )
    }
}
