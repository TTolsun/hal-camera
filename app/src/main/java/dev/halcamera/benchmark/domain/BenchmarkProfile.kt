package dev.halcamera.benchmark.domain

import dev.halcamera.metrics.jsonName

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
    val excludeFirst: Boolean,
    /**
     * Recording conditions of the RECORD stage (docs/PLAN-Recording-v0.1.md chapter 4). All seven are null in a
     * profile that does not record: camera2-standard-v1 predates the stage and its stored runs carry no record
     * keys at all, so an absent key means "this profile never recorded" rather than a value to guess. Either all
     * seven are present or none is; [fromJsonMap] rejects anything in between.
     */
    val recordSize: String? = null,
    val recordCodec: String? = null,
    val recordBitrate: Int? = null,
    val recordFps: Int? = null,
    val recordDurationMs: Long? = null,
    val recordIterations: Int? = null,
    val recordAudio: Boolean? = null
) {
    /** Draft profiles may still change; their runs are never used for scoring (3.5). */
    val isDraft: Boolean get() = id.endsWith("-draft")

    /** True when this profile drives the RECORD stage, which is what the 3.x metrics are measured from. */
    val records: Boolean get() = recordSize != null

    /**
     * Stable-order condition string, used for logs and as part of stored keys. The record segment is appended
     * only when the profile records, so the key of camera2-standard-v1 stays what it was and the baselines
     * stored under it keep matching.
     */
    val conditionsKey: String
        get() = (listOf(
            "engine=$engine", "preview=$previewSize", "yuv=$yuvSize", "still=$stillFormat@$stillSize", "fps=$fpsRange",
            "zsl=${if (zsl) "on" else "off"}", "trigger=${if (trigger) "on" else "off"}", "af=$afMode",
            "launch=${launchMode.jsonName}"
        ) + if (!records) emptyList()
        else listOf("record=$recordCodec@$recordSize/${recordFps}fps/${recordDurationMs}ms x$recordIterations",
            "record_audio=${if (recordAudio == true) "on" else "off"}")).joinToString(",")

    private val excluded: Int get() = if (excludeFirst) 1 else 0

    /** Valid launch samples the profile promises: iterations minus the warm-up cycle. */
    val expectedLaunchSamples: Int get() = launchIterations - excluded
    /** Valid still latency samples: captures minus the warm-up capture. */
    val expectedStillSamples: Int get() = stillCount - excluded
    /** Valid shot-to-shot intervals: (captures - 1) intervals minus the warm-up interval (METRICS.md 2.5 note). */
    val expectedShotToShotSamples: Int get() = (stillCount - 1) - excluded

    /**
     * Valid recording samples for 3.1 and 3.6: cycles minus the warm-up cycle, 0 when the profile does not
     * record. With the confirmed five cycles this is 4, which is below the ten repetitions METRICS.md 0.2 asks
     * for; the count is reported as it is rather than padded (docs/PLAN-Recording-v0.1.md 3.2).
     */
    val expectedRecordSamples: Int get() = ((recordIterations ?: 0) - excluded).coerceAtLeast(0)

    fun toJsonMap(): Map<String, Any?> = mapOf(
        "id" to id, "engine" to engine, "preview_size" to previewSize, "yuv_size" to yuvSize,
        "still_format" to stillFormat, "still_size" to stillSize, "fps_range" to fpsRange,
        "zsl" to zsl, "trigger" to trigger, "af_mode" to afMode, "launch_mode" to launchMode.jsonName,
        "launch_iterations" to launchIterations, "warmup_ms" to warmupMs, "observe_ms" to observeMs,
        "still_count" to stillCount, "exclude_first" to excludeFirst,
        "record_size" to recordSize, "record_codec" to recordCodec, "record_bitrate" to recordBitrate,
        "record_fps" to recordFps, "record_duration_ms" to recordDurationMs,
        "record_iterations" to recordIterations, "record_audio" to recordAudio
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
         * The standard profile plus the RECORD stage (docs/PLAN-Recording-v0.1.md). Every v1 condition is kept
         * unchanged and seven recording conditions are added, so a v2 run measures everything a v1 run measures
         * and the 3.x metrics on top. The id is a new one rather than a changed v1 because the profile fields
         * are the comparison contract (3.5): v1 and v2 runs are never compared, and a device that had a
         * baseline under v1 needs a new baseline under v2.
         *
         * Recording is 1080p H.264 at the profile's own 30 fps with no audio track: no metric in METRICS.md
         * chapter 3 reads the audio track, and a denied microphone permission would fail the whole run.
         */
        val CAMERA2_STANDARD_V2 = CAMERA2_STANDARD_V1.copy(
            id = "camera2-standard-v2",
            recordSize = "1920x1080",
            recordCodec = "h264",
            recordBitrate = 10_000_000,
            recordFps = 30,
            recordDurationMs = 9_000,
            recordIterations = 5,
            recordAudio = false
        )

        /**
         * Profiles this app defines, by id. A stored run whose profile id is a confirmed (non-draft) canonical id
         * must carry exactly this definition, otherwise the file is rejected (BenchmarkReportCodec).
         */
        val CANONICAL: Map<String, BenchmarkProfile> =
            listOf(CAMERA2_STANDARD_V1, CAMERA2_STANDARD_V2).associateBy { it.id }

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
        ).let { base ->
            // The record keys are optional as a group, never one by one: a file that carries some of them has
            // either been edited or was written by a half-finished version, and reading it as a non-recording
            // profile would silently turn a v2 run into a v1-shaped one.
            val present = RECORD_KEYS.filter { m[it] != null }
            if (present.isEmpty()) base
            else {
                require(present.size == RECORD_KEYS.size) {
                    "profile record conditions are incomplete: missing ${(RECORD_KEYS - present.toSet()).joinToString(", ")}"
                }
                base.copy(
                    recordSize = JsonMaps.reqString(m, "record_size", "profile"),
                    recordCodec = JsonMaps.reqString(m, "record_codec", "profile"),
                    recordBitrate = JsonMaps.reqInt(m, "record_bitrate", "profile"),
                    recordFps = JsonMaps.reqInt(m, "record_fps", "profile"),
                    recordDurationMs = JsonMaps.reqLong(m, "record_duration_ms", "profile"),
                    recordIterations = JsonMaps.reqInt(m, "record_iterations", "profile"),
                    recordAudio = JsonMaps.reqBoolean(m, "record_audio", "profile")
                )
            }
        }

        private val RECORD_KEYS = listOf(
            "record_size", "record_codec", "record_bitrate", "record_fps",
            "record_duration_ms", "record_iterations", "record_audio"
        )
    }
}
