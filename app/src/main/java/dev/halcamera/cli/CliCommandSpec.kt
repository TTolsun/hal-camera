package dev.halcamera.cli

/**
 * One command contract drives argument validation, defaults, dispatch and advertised capabilities. Code asks the
 * spec what a command does instead of comparing its name, so adding a command means adding one entry here.
 */
data class CliCommandSpec(
    val name: String,
    val target: Target = Target.LIVE,
    val camera: Boolean = false,
    val engine: Boolean = false,
    val streams: Boolean = false,
    val audio: Boolean = false,
    val timeoutMs: Long = 30_000,
    val options: Set<String> = emptySet(),
    val requiredOptions: Set<String> = emptySet(),
    val savesMedia: Boolean = false,
    val currentScreen: Boolean = false,
    /** How a LIVE screen runs the command; [Flow.NONE] for screenless commands. */
    val flow: Flow = Flow.NONE,
    val acceptsMode: Boolean = false,
    val cameraPermission: Boolean = target != Target.LIBRARY,
    /** Ends with `record.stop`, may be tuned while it runs, and must report recording within 30 seconds. */
    val recording: Boolean = false,
    /** Accepts `record.snapshot` while it records. */
    val snapshots: Boolean = false,
    /** Takes `audio` only to turn it off. */
    val silentOnly: Boolean = false,
    val profile: Boolean = false,
    val cases: Boolean = false,
    /** Deletes something, so it runs only with `confirm=true`. */
    val confirmRequired: Boolean = false,
    /**
     * Whether a cancel that arrives before the request runs must also reach the screen. A command that never
     * reaches the screen leaves this false: telling Live to stop preparing would pause its preview.
     */
    val earlyCancelNotifiesHost: Boolean = target == Target.LIVE
) {
    enum class Target { QUERY, LIBRARY, LIVE }
    enum class Flow { NONE, INSPECT, EVENTS, STOP_PREVIEW, DUAL, CTS, BENCHMARK, PREVIEW, CAPTURE, SEQUENCE, RECORD }
    /** Saves still photos, so at least one still output must stay enabled. */
    val stills get() = flow == Flow.CAPTURE || flow == Flow.SEQUENCE
    fun toMap(): Map<String, Any?> = mapOf("camera" to camera, "engine" to engine, "streams" to streams,
        "audio" to audio, "default_timeout_ms" to timeoutMs, "foreground" to (target == Target.LIVE),
        "options" to options.toList(), "required_options" to requiredOptions.toList(), "current_screen" to currentScreen,
        "mode" to acceptsMode, "profile" to profile, "cases" to cases, "recording" to recording)
}

object CliCommands {
    private val controls = setOf("zoom", "ev", "ae_lock", "af_lock", "flash", "iso", "exposure_ns", "focus", "wb", "gains", "matrix")
    val controlKeys: Set<String> get() = controls
    val pipKeys = setOf("pip_type", "pip_camera", "pip_x", "pip_y", "pip_width", "pip_height")
    /** Names `content call` accepts for a command besides its own. */
    val aliases = mapOf("preview.start" to "preview")
    val specs: Map<String, CliCommandSpec> = buildList {
        fun library(name: String, required: Set<String> = emptySet(), optional: Set<String> = emptySet(), confirm: Boolean = false) {
            add(CliCommandSpec(name, CliCommandSpec.Target.LIBRARY, options = required + optional + (if (confirm) setOf("confirm") else emptySet()),
                requiredOptions = required, confirmRequired = confirm))
        }
        library("settings.show")
        library("settings.limit", setOf("limit"), setOf("confirm"))
        library("incidents.list")
        library("incidents.export", setOf("incident"))
        library("incidents.delete", setOf("incident"), confirm = true)
        fun live(name: String, flow: CliCommandSpec.Flow, extra: Set<String> = emptySet(), pip: Boolean = false, audio: Boolean = false, timeout: Long = 30_000,
                 mode: Boolean = false, recording: Boolean = false) {
            add(CliCommandSpec(name, camera = true, engine = true, streams = true, audio = audio,
                timeoutMs = timeout, options = controls + extra + (if (pip) pipKeys else emptySet()), savesMedia = flow != CliCommandSpec.Flow.PREVIEW,
                flow = flow, acceptsMode = mode, recording = recording, snapshots = recording))
        }
        live("burst", CliCommandSpec.Flow.SEQUENCE, setOf("count", "interval_ms"))
        live("bracket", CliCommandSpec.Flow.SEQUENCE)
        add(CliCommandSpec("meter", options = setOf("x", "y", "meter"), requiredOptions = setOf("x", "y"), currentScreen = true, flow = CliCommandSpec.Flow.INSPECT))
        add(CliCommandSpec("events", currentScreen = true, flow = CliCommandSpec.Flow.EVENTS))
        add(CliCommandSpec("live.info", currentScreen = true, flow = CliCommandSpec.Flow.INSPECT))
        // Kept off the early-cancel list as before: the screen answers it at once.
        add(CliCommandSpec("pip.sources", flow = CliCommandSpec.Flow.INSPECT, earlyCancelNotifiesHost = false))
        add(CliCommandSpec("dual.cameras", CliCommandSpec.Target.QUERY))
        add(CliCommandSpec("multi.cameras", CliCommandSpec.Target.QUERY))
        for (name in listOf("dual.preview", "dual.capture", "dual.record")) {
            val record = name == "dual.record"
            add(CliCommandSpec(name, camera = true, engine = true, audio = record,
                timeoutMs = if (record) 3_600_000 else 30_000,
                options = setOf("first", "second") + controls, requiredOptions = setOf("first", "second"),
                savesMedia = name != "dual.preview", flow = CliCommandSpec.Flow.DUAL, recording = record, silentOnly = record))
        }
        library("results.list")
        for (name in listOf("results.show", "results.export")) library(name, setOf("run"))
        library("results.compare", setOf("run", "reference"))
        library("results.delete", setOf("run"), confirm = true)
        for (name in listOf("baseline.add", "baseline.remove")) library(name, setOf("run"))
        library("gallery.list")
        library("gallery.export", setOf("media"))
        library("gallery.delete", setOf("media"), confirm = true)
        add(CliCommandSpec("streams", CliCommandSpec.Target.QUERY, camera = true, engine = true, acceptsMode = true))
        add(CliCommandSpec("cameras", CliCommandSpec.Target.QUERY))
        live("preview", CliCommandSpec.Flow.PREVIEW, pip = true, mode = true)
        add(CliCommandSpec("preview.stop", currentScreen = true, flow = CliCommandSpec.Flow.STOP_PREVIEW))
        live("capture", CliCommandSpec.Flow.CAPTURE, pip = true)
        live("record.start", CliCommandSpec.Flow.RECORD, pip = true, audio = true, timeout = 3_600_000, recording = true)
        add(CliCommandSpec("probe", CliCommandSpec.Target.QUERY))
        add(CliCommandSpec("cts.cases", CliCommandSpec.Target.QUERY, cameraPermission = false))
        add(CliCommandSpec("cts.run", timeoutMs = 1_800_000, flow = CliCommandSpec.Flow.CTS, cases = true))
        add(CliCommandSpec("benchmark.run", camera = true, timeoutMs = 600_000, options = setOf("build", "commit", "branch", "note"),
            flow = CliCommandSpec.Flow.BENCHMARK, profile = true))
    }.associateBy { it.name }

    fun get(name: String): CliCommandSpec = specs[name] ?: throw CliFailure("INVALID_ARGUMENT", "Unknown command: $name")
}
