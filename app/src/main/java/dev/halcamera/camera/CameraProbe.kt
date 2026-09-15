package dev.halcamera.camera

import java.util.Locale

/**
 * PROBE: a static capability report of every camera the public Camera2 API exposes, in the spirit of the
 * "Camera2 API Probe" apps. Nothing here opens a camera or measures anything; the report is a rendering of
 * CameraCharacteristics, so it says what the HAL claims, not what it does. The measured answer is BENCHMARK.
 *
 * This file is pure Kotlin. [CameraProbeReader] is the only part that knows CameraManager; it fills the model
 * below and the text and JSON renderings are testable on the JVM.
 */
data class ProbeRow(val key: String, val value: String)

data class ProbeSection(val title: String, val rows: List<ProbeRow>)

data class CameraProbeEntry(
    val cameraId: String,
    /** The logical camera this physical camera sits behind, or null for a camera that is listed by itself. */
    val physicalOf: String?,
    /** One-line label for pickers: "0 · 후면 · FULL". */
    val title: String,
    val sections: List<ProbeSection>
) {
    val key: String get() = physicalOf?.let { "$it.$cameraId" } ?: cameraId
}

data class CameraProbeSnapshot(
    /** ISO-8601 wall-clock time; the probe has no elapsedRealtime domain because nothing is measured. */
    val capturedAt: String,
    val device: List<ProbeRow>,
    val cameras: List<CameraProbeEntry>,
    /** Cameras or sections that could not be read. Kept so an exported file says what is missing. */
    val errors: List<String>
) {
    fun camera(key: String): CameraProbeEntry? = cameras.firstOrNull { it.key == key }

    fun toJsonMap(): Map<String, Any?> = mapOf(
        "schema" to SCHEMA,
        "captured_at" to capturedAt,
        "device" to device.associate { it.key to it.value },
        "cameras" to cameras.map { camera ->
            mapOf(
                "camera_id" to camera.cameraId,
                "physical_of" to camera.physicalOf,
                "title" to camera.title,
                "sections" to camera.sections.map { section ->
                    mapOf("title" to section.title, "rows" to section.rows.map { mapOf("key" to it.key, "value" to it.value) })
                }
            )
        },
        "errors" to errors
    )

    companion object {
        const val SCHEMA = "camera_probe/1"
    }
}

/** Value formatting shared by the reader and the tests, so a frame duration reads the same on screen and in a file. */
object ProbeFormat {
    /** "33.3 ms (30.0 fps)"; 0 is "0 ms" because the HAL uses 0 for "no constraint"; null is "—". */
    fun frameDuration(ns: Long?): String = when {
        ns == null -> "—"
        ns <= 0L -> "0 ms"
        else -> String.format(Locale.US, "%.1f ms (%.1f fps)", ns / 1_000_000.0, 1_000_000_000.0 / ns)
    }

    /** "0 ms" or "66.7 ms"; a stall is a delay, never a rate. */
    fun stall(ns: Long?): String = when {
        ns == null -> "—"
        ns <= 0L -> "0 ms"
        else -> String.format(Locale.US, "%.1f ms", ns / 1_000_000.0)
    }

    fun exposureNs(ns: Long?): String = when {
        ns == null -> "—"
        ns >= 1_000_000_000L -> String.format(Locale.US, "%.2f s", ns / 1_000_000_000.0)
        ns >= 1_000_000L -> String.format(Locale.US, "%.2f ms", ns / 1_000_000.0)
        else -> String.format(Locale.US, "%.1f µs", ns / 1_000.0)
    }

    /** A stream row: "1920x1080" against "min 33.3 ms (30.0 fps) · stall 0 ms". */
    fun streamRow(width: Int, height: Int, minFrameNs: Long?, stallNs: Long?): ProbeRow =
        ProbeRow("${width}x$height", "min ${frameDuration(minFrameNs)} · stall ${stall(stallNs)}")

    fun list(values: List<String>): String = if (values.isEmpty()) "(none)" else values.joinToString("\n")
}

/**
 * Plain-text rendering, one column of keys and one of values, so the file diffs across devices and firmware
 * versions. Assumes a monospaced face, as every table in this app does (see [dev.halcamera.ui.Look.mono]).
 */
object CameraProbeText {
    private const val MAX_KEY_WIDTH = 26

    fun render(snapshot: CameraProbeSnapshot, cameraKey: String? = null): String {
        val out = StringBuilder()
        out.appendLine("HAL CAM · CAMERA PROBE")
        out.appendLine("captured  ${snapshot.capturedAt}")
        out.appendLine()
        out.append(section(ProbeSection("DEVICE", snapshot.device)))
        val cameras = if (cameraKey == null) snapshot.cameras else snapshot.cameras.filter { it.key == cameraKey }
        cameras.forEach { camera ->
            out.appendLine()
            out.appendLine("######## CAMERA ${camera.key} · ${camera.title}")
            camera.sections.forEach { out.appendLine(); out.append(section(it)) }
        }
        if (snapshot.errors.isNotEmpty()) {
            out.appendLine()
            out.append(section(ProbeSection("ERRORS", snapshot.errors.map { ProbeRow("!", it) })))
        }
        return out.toString()
    }

    fun section(section: ProbeSection): String {
        val out = StringBuilder()
        out.appendLine("== ${section.title} ==")
        if (section.rows.isEmpty()) { out.appendLine("(none)"); return out.toString() }
        val width = section.rows.maxOf { it.key.length }.coerceAtMost(MAX_KEY_WIDTH)
        section.rows.forEach { row ->
            val lines = row.value.split('\n')
            if (row.key.length > width) {
                // A key wider than the column gets its own line so the value column stays aligned below it.
                out.appendLine(row.key)
                lines.forEach { out.append(" ".repeat(width + 2)).appendLine(it) }
            } else {
                out.append(row.key.padEnd(width + 2)).appendLine(lines.first())
                lines.drop(1).forEach { out.append(" ".repeat(width + 2)).appendLine(it) }
            }
        }
        return out.toString()
    }
}
