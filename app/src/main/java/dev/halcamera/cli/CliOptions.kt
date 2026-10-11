package dev.halcamera.cli

import dev.halcamera.camera.*

/** Validated strings are shared by adb and JSON; no shell expressions or implicit UI preferences. */
data class CliOptions(val values: Map<String, String>) {
    init {
        if (values.isEmpty() || values.keys.any { it !in KEYS }) invalid("Unknown or empty options")
        values.forEach { (key, value) ->
            when (key) {
                "limit" -> if (value.toIntOrNull() !in dev.halcamera.benchmark.domain.RunRetention.OPTIONS) invalid("limit must be 0 (unlimited), 10, 20, ... or 100")
                "build", "commit", "branch", "note" -> if (value.length > 500 || value.any { it == '\n' || it == '\r' || it == '\u0000' }) invalid("Label must be a single line of at most 500 characters")
                "zoom" -> number(value, 0.01, 1000.0)
                "ev" -> if (value.toIntOrNull() !in -100..100) invalid("ev must be an integer compensation step")
                "ae_lock", "af_lock", "confirm" -> if (value !in setOf("true", "false")) invalid("$key must be true or false")
                "flash" -> if (value !in FlashMode.entries.map { it.name }) invalid("flash must be OFF, AUTO, ON or TORCH")
                "iso" -> if (value.toIntOrNull() !in 1..1_000_000) invalid("iso must be a positive integer")
                "exposure_ns" -> if (value.toLongOrNull() !in 1L..60_000_000_000L) invalid("exposure_ns must be 1–60000000000")
                "focus" -> number(value, 0.0, 1000.0)
                "wb" -> if (value !in WhiteBalance.entries.map { it.name }) invalid("Unknown white balance; use help controls")
                "gains", "matrix" -> {
                    val parts = value.split(',')
                    if (parts.size != if (key == "gains") 4 else 9) invalid("$key has the wrong number of values")
                    parts.forEach { number(it, if (key == "gains") 1.0 else -100.0, 100.0) }
                }
                "count" -> if (value.toIntOrNull() !in 1..BurstRun.MAX_COUNT) invalid("count must be 1–${BurstRun.MAX_COUNT}")
                "interval_ms" -> if (value.toLongOrNull() !in 0L..60_000L) invalid("interval_ms must be 0–60000")
                "x", "y" -> number(value, 0.0, 1.0)
                "pip_type" -> if (value !in setOf("off", "physical", "service")) invalid("pip_type must be off, physical or service")
                "pip_x", "pip_y" -> number(value, 0.0, 1.0)
                "pip_width", "pip_height" -> number(value, 0.01, 1.0)
                "meter" -> if (value !in setOf("focus", "exposure")) invalid("meter must be focus or exposure")
                else -> if (!value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) invalid("$key must be a listed ID")
            }
        }
        if (values.containsKey("iso") != values.containsKey("exposure_ns")) invalid("Use --iso and --exposure-ns together")
        if (values.keys.any { it in setOf("gains", "matrix") } && values["wb"] != "CUSTOM") invalid("gains and matrix require --wb CUSTOM")
        if (values.containsKey("x") != values.containsKey("y")) invalid("Use --x and --y together")
        val pip = values["pip_type"]
        if ((pip in setOf("physical", "service")) != values.containsKey("pip_camera")) invalid("Use --pip-type physical|service and --pip-camera together")
        val positionKeys = setOf("pip_x", "pip_y", "pip_width", "pip_height")
        if (values.keys.any { it in positionKeys }) {
            if (pip !in setOf("physical", "service") || !values.keys.containsAll(positionKeys)) invalid("PIP position requires a selected PIP and all four coordinates")
            if (values.getValue("pip_x").toDouble() + values.getValue("pip_width").toDouble() > 1.0 ||
                values.getValue("pip_y").toDouble() + values.getValue("pip_height").toDouble() > 1.0) invalid("PIP rectangle must fit within 0–1")
        }
    }

    fun controls(support: LiveControlSupport, manual: ManualSupport, video: Boolean, base: LiveControls = LiveControls(), composited: Boolean = false): LiveControls {
        val result = LiveControls(values["ev"]?.toInt() ?: base.evIndex, values["ae_lock"]?.toBoolean() ?: base.aeLock, values["af_lock"]?.toBoolean() ?: base.afLock,
            values["flash"]?.let(FlashMode::valueOf) ?: base.flash,
            ManualControls(values["iso"]?.let { ManualExposure(it.toInt(), values.getValue("exposure_ns").toLong()) } ?: base.manual.exposure,
                values["focus"]?.toFloat() ?: base.manual.focusDiopters, values["wb"]?.let(WhiteBalance::valueOf) ?: base.manual.wb,
                ManualColor(values["gains"]?.split(',')?.map(String::toFloat) ?: base.manual.color.gains,
                    values["matrix"]?.split(',')?.map(String::toFloat) ?: base.manual.color.transform)))
        manual.rejection(result.manual)?.let { throw CliFailure("PREFLIGHT_FAILED", it) }
        if (result.coerce(support, video, composited || values["pip_type"] in setOf("physical", "service")) != result) throw CliFailure("PREFLIGHT_FAILED", "Unsupported control combination. Run streams for supported controls.")
        return result
    }

    companion object {
        // PIP selection is a per-request scene option; it is not an in-flight camera control.
        val CONTROL_KEYS get() = CliCommands.controlKeys
        val KEYS get() = CliCommands.specs.values.flatMap { it.options }.toSet()
        fun allowed(command: String): Set<String> = if (command == "live.set") CONTROL_KEYS else CliCommands.specs[command]?.options.orEmpty()
        private fun number(value: String, min: Double, max: Double) {
            val n = value.toDoubleOrNull()
            if (n == null || !n.isFinite() || n !in min..max) invalid("Expected a number from $min to $max")
        }
        private fun invalid(message: String): Nothing = throw CliFailure("INVALID_ARGUMENT", message)
    }

    fun pipSource(): PipSource? = values["pip_camera"]?.let { PipSource(it, values["pip_type"] == "physical", "CLI PIP $it") }
    fun pipPosition(): PipRect = if (values.containsKey("pip_x")) PipRect(values.getValue("pip_x").toFloat(), values.getValue("pip_y").toFloat(),
        values.getValue("pip_width").toFloat(), values.getValue("pip_height").toFloat()) else PipScene.liveDefault
}
