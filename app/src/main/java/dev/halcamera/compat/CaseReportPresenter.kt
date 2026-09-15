package dev.halcamera.compat

/** Plain-text rendering of a [CaseReport] for the screen and the clipboard. Pure Kotlin, monospace-aligned. */
object CaseReportPresenter {
    const val DISCLAIMER = "CTS 케이스를 앱 안에 옮긴 자체 실행입니다. 공식 판정은 cts-tradefed의 test_result.xml만 인정됩니다."

    fun headline(report: CaseReport): String {
        val cameras = report.cameras.size
        val head = if (report.failed == 0) "PASS · 카메라 ${cameras}대, FAIL 0" else "FAIL · 카메라 ${cameras}대, FAIL ${report.failed}"
        return if (report.cancelled) "$head · 중단됨" else head
    }

    fun cameraLine(r: CameraCaseResult): String =
        "ID ${r.cameraId} · PASS ${r.passed} FAIL ${r.failed} SKIP ${r.skipped}"

    /** One row per step: verdict then step id; every detail line follows, indented. */
    fun cameraTable(r: CameraCaseResult): String = buildString {
        val idWidth = r.steps.maxOfOrNull { it.id.length } ?: 0
        r.steps.forEach { s ->
            append(s.verdict.name.padEnd(5)).append(' ').append(s.id.padEnd(idWidth)).append('\n')
            s.details.forEach { append("      ").append(it).append('\n') }
        }
    }.trimEnd()

    fun fullText(device: String, build: String, app: String, report: CaseReport): String = buildString {
        append("HAL CAM CTS case · ").append(report.source).append('\n')
        append(device).append(" · ").append(build).append(" · app ").append(app).append('\n')
        append(headline(report)).append('\n')
        append(DISCLAIMER).append('\n')
        report.cameras.forEach { r ->
            append('\n').append(cameraLine(r)).append('\n')
            append(cameraTable(r)).append('\n')
        }
    }.trimEnd()
}
