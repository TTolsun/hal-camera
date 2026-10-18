package dev.halcamera.cli

/** One command contract drives argument validation, defaults, dispatch and advertised capabilities. */
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
    val currentScreen: Boolean = false
) {
    enum class Target { QUERY, LIBRARY, LIVE }
    val acceptsMode get() = name in setOf("preview", "streams")
    val cameraPermission get() = target != Target.LIBRARY && name != "cts.cases"
    fun toMap(): Map<String, Any?> = mapOf("camera" to camera, "engine" to engine, "streams" to streams,
        "audio" to audio, "default_timeout_ms" to timeoutMs, "foreground" to (target == Target.LIVE),
        "options" to options.toList(), "required_options" to requiredOptions.toList(), "current_screen" to currentScreen,
        "mode" to acceptsMode)
}

object CliCommands {
    private val controls = setOf("zoom", "ev", "ae_lock", "af_lock", "flash", "iso", "exposure_ns", "focus", "wb", "gains", "matrix")
    val controlKeys: Set<String> get() = controls
    val pipKeys = setOf("pip_type", "pip_camera", "pip_x", "pip_y", "pip_width", "pip_height")
    val specs: Map<String, CliCommandSpec> = buildList {
        fun library(name: String, required: Set<String> = emptySet(), optional: Set<String> = emptySet()) {
            add(CliCommandSpec(name, CliCommandSpec.Target.LIBRARY, options = required + optional, requiredOptions = required))
        }
        library("settings.show")
        library("settings.limit", setOf("limit"), setOf("confirm"))
        library("incidents.list")
        library("incidents.export", setOf("incident"))
        library("incidents.delete", setOf("incident"), setOf("confirm"))
        fun live(name: String, extra: Set<String> = emptySet(), audio: Boolean = false, timeout: Long = 30_000) {
            add(CliCommandSpec(name, camera = true, engine = true, streams = true, audio = audio,
                timeoutMs = timeout, options = controls + extra + (if (name in setOf("preview", "capture", "record.start")) pipKeys else emptySet()), savesMedia = name != "preview"))
        }
        live("burst", setOf("count", "interval_ms"))
        live("bracket")
        add(CliCommandSpec("meter", options = setOf("x", "y", "meter"), requiredOptions = setOf("x", "y"), currentScreen = true))
        add(CliCommandSpec("events", currentScreen = true))
        add(CliCommandSpec("live.info", currentScreen = true))
        add(CliCommandSpec("pip.sources"))
        add(CliCommandSpec("dual.cameras", CliCommandSpec.Target.QUERY))
        add(CliCommandSpec("multi.cameras", CliCommandSpec.Target.QUERY))
        for (name in listOf("dual.preview", "dual.capture", "dual.record")) {
            add(CliCommandSpec(name, camera = true, engine = true, audio = name == "dual.record",
                timeoutMs = if (name == "dual.record") 3_600_000 else 30_000,
                options = setOf("first", "second") + controls, requiredOptions = setOf("first", "second"),
                savesMedia = name != "dual.preview"))
        }
        library("results.list")
        for (name in listOf("results.show", "results.export")) library(name, setOf("run"))
        library("results.compare", setOf("run", "reference"))
        library("results.delete", setOf("run"), setOf("confirm"))
        for (name in listOf("baseline.add", "baseline.remove")) library(name, setOf("run"))
        library("gallery.list")
        library("gallery.export", setOf("media"))
        library("gallery.delete", setOf("media"), setOf("confirm"))
        add(CliCommandSpec("streams", CliCommandSpec.Target.QUERY, camera = true, engine = true))
        add(CliCommandSpec("cameras", CliCommandSpec.Target.QUERY))
        live("preview")
        add(CliCommandSpec("preview.stop", currentScreen = true))
        live("capture")
        live("record.start", audio = true, timeout = 3_600_000)
        add(CliCommandSpec("probe", CliCommandSpec.Target.QUERY))
        add(CliCommandSpec("cts.cases", CliCommandSpec.Target.QUERY))
        add(CliCommandSpec("cts.run", timeoutMs = 1_800_000))
        add(CliCommandSpec("benchmark.run", camera = true, timeoutMs = 600_000, options = setOf("build", "commit", "branch", "note")))
    }.associateBy { it.name }

    fun get(name: String): CliCommandSpec = specs[name] ?: throw CliFailure("INVALID_ARGUMENT", "Unknown command: $name")
}
