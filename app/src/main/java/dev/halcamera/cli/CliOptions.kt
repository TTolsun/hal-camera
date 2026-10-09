package dev.halcamera.cli

import dev.halcamera.camera.*

/** Validated strings are shared by adb and JSON; no shell expressions or implicit UI preferences. */
data class CliOptions(val values: Map<String, String>) {
    init {
        if (values.isEmpty() || values.keys.any { it !in KEYS }) invalid("Unknown or empty options")
        values.forEach { (key, value) ->
            when (key) {
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
                "meter" -> if (value !in setOf("focus", "exposure")) invalid("meter must be focus or exposure")
                else -> if (!value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) invalid("$key must be a listed ID")
            }
        }
        if (values.containsKey("iso") != values.containsKey("exposure_ns")) invalid("Use --iso and --exposure-ns together")
        if (values.keys.any { it in setOf("gains", "matrix") } && values["wb"] != "CUSTOM") invalid("gains and matrix require --wb CUSTOM")
        if (values.containsKey("x") != values.containsKey("y")) invalid("Use --x and --y together")
    }

    fun controls(support: LiveControlSupport, manual: ManualSupport, video: Boolean): LiveControls {
        val result = LiveControls(values["ev"]?.toInt() ?: 0, values["ae_lock"] == "true", values["af_lock"] == "true",
            values["flash"]?.let(FlashMode::valueOf) ?: FlashMode.OFF,
            ManualControls(values["iso"]?.let { ManualExposure(it.toInt(), values.getValue("exposure_ns").toLong()) },
                values["focus"]?.toFloat(), values["wb"]?.let(WhiteBalance::valueOf) ?: WhiteBalance.AUTO,
                ManualColor(values["gains"]?.split(',')?.map(String::toFloat) ?: ManualColor().gains,
                    values["matrix"]?.split(',')?.map(String::toFloat) ?: ManualColor().transform)))
        manual.rejection(result.manual)?.let { throw CliFailure("PREFLIGHT_FAILED", it) }
        if (result.coerce(support, video) != result) throw CliFailure("PREFLIGHT_FAILED", "Unsupported control combination. Run streams for supported controls.")
        return result
    }

    companion object {
        val CONTROL_KEYS = setOf("zoom", "ev", "ae_lock", "af_lock", "flash", "iso", "exposure_ns", "focus", "wb", "gains", "matrix")
        val KEYS = CONTROL_KEYS + setOf("count", "interval_ms", "x", "y", "meter", "run", "reference", "media", "confirm", "first", "second")
        fun allowed(command: String): Set<String> = when (command) {
            "preview", "capture", "record.start", "bracket" -> CONTROL_KEYS
            "burst" -> CONTROL_KEYS + setOf("count", "interval_ms")
            "meter" -> setOf("x", "y", "meter")
            "results.show", "results.export", "baseline.add", "baseline.remove" -> setOf("run")
            "results.compare" -> setOf("run", "reference")
            "results.delete" -> setOf("run", "confirm")
            "gallery.export" -> setOf("media")
            "gallery.delete" -> setOf("media", "confirm")
            "dual.preview", "dual.capture", "dual.record" -> setOf("first", "second") + CONTROL_KEYS
            else -> emptySet()
        }
        private fun number(value: String, min: Double, max: Double) {
            val n = value.toDoubleOrNull()
            if (n == null || !n.isFinite() || n !in min..max) invalid("Expected a number from $min to $max")
        }
        private fun invalid(message: String): Nothing = throw CliFailure("INVALID_ARGUMENT", message)
    }
}
