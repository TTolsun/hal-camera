package dev.halcamera.cts.suite

import dev.halcamera.cts.vendored.VendoredReportPresenter
import dev.halcamera.ctsvendor.VendoredResult
import dev.halcamera.ctsvendor.VendoredVerdict

/**
 * What one suite item ended as. CANCELLED is the item that was running when the user pressed 중단 and
 * NOT_RUN the items after it; both are kept apart from SKIP, which a test reports itself when the device has
 * nothing for it to check.
 */
enum class SuiteOutcome { PASS, FAIL, SKIP, CANCELLED, NOT_RUN }

/** One finished line of the suite: the item, its outcome, how long it ran and the detail text under it. */
data class SuiteEntry(val item: SuiteItem, val outcome: SuiteOutcome, val durationMs: Long, val detail: String) {
    companion object {
        fun of(item: SuiteItem, result: VendoredResult): SuiteEntry = SuiteEntry(
            item,
            when {
                result.cancelled -> SuiteOutcome.CANCELLED
                result.verdict == VendoredVerdict.FAIL -> SuiteOutcome.FAIL
                result.verdict == VendoredVerdict.SKIP -> SuiteOutcome.SKIP
                else -> SuiteOutcome.PASS
            },
            result.durationMs,
            SuiteReportPresenter.vendoredDetail(result.failures)
        )

        fun notRun(item: SuiteItem): SuiteEntry = SuiteEntry(item, SuiteOutcome.NOT_RUN, 0, "")
    }
}

/** The whole suite once every item has an entry. [cancelled] is set when the user stopped it. */
data class SuiteReport(val entries: List<SuiteEntry>, val cancelled: Boolean) {
    val passed: Int get() = entries.count { it.outcome == SuiteOutcome.PASS }
    val failed: Int get() = entries.count { it.outcome == SuiteOutcome.FAIL }
    val skipped: Int get() = entries.count { it.outcome == SuiteOutcome.SKIP }
    /** Items never started. The item that was cancelled mid-way is not among them; the headline's 중단됨 covers it. */
    val notRun: Int get() = entries.count { it.outcome == SuiteOutcome.NOT_RUN }
    val durationMs: Long get() = entries.sumOf { it.durationMs }

    /**
     * The report as the CLI files it: the same entries the screen lists, with the outcome names of the screen
     * and the detail text under each row. [device], [build] and [app] are the header the shared text carries.
     */
    fun toJsonMap(device: String, build: String, app: String): Map<String, Any?> = mapOf(
        "schema" to SCHEMA,
        "device" to device,
        "build" to build,
        "app" to app,
        "headline" to SuiteReportPresenter.headline(this),
        "cancelled" to cancelled,
        "passed" to passed,
        "failed" to failed,
        "skipped" to skipped,
        "not_run" to notRun,
        "duration_ms" to durationMs,
        "entries" to entries.map { entry ->
            mapOf(
                "key" to entry.item.key,
                "title" to entry.item.title,
                "source" to entry.item.source,
                "outcome" to entry.outcome.name,
                "duration_ms" to entry.durationMs,
                "detail" to entry.detail
            )
        }
    )

    companion object {
        const val SCHEMA = "cts_suite/1"
    }
}

/** Plain-text rendering of a [SuiteReport] for the screen and the clipboard. Pure Kotlin. */
object SuiteReportPresenter {
    /** The one sentence every CTS screen repeats: what these results are and are not. */
    fun disclaimer(): String = VendoredReportPresenter.DISCLAIMER

    /** "7개 중 PASS 6 · FAIL 1 · 12분 34초", with SKIP and 실행 안 함 counts only when they are not zero. */
    fun headline(report: SuiteReport): String {
        val parts = ArrayList<String>()
        parts += "${report.entries.size}개 중 PASS ${report.passed}"
        parts += "FAIL ${report.failed}"
        if (report.skipped > 0) parts += "SKIP ${report.skipped}"
        if (report.notRun > 0) parts += "실행 안 함 ${report.notRun}"
        parts += VendoredReportPresenter.duration(report.durationMs)
        if (report.cancelled) parts += "중단됨"
        return parts.joinToString(" · ")
    }

    fun outcomeLabel(outcome: SuiteOutcome): String = when (outcome) {
        SuiteOutcome.PASS -> "PASS"
        SuiteOutcome.FAIL -> "FAIL"
        SuiteOutcome.SKIP -> "SKIP"
        SuiteOutcome.CANCELLED -> "중단됨"
        SuiteOutcome.NOT_RUN -> "실행 안 함"
    }

    /** The second line of an entry row: the source, then the duration when the item ran. */
    fun entryLine(entry: SuiteEntry): String =
        if (entry.outcome == SuiteOutcome.NOT_RUN) entry.item.source
        else "${entry.item.source} · ${VendoredReportPresenter.duration(entry.durationMs)}"

    /** The JUnit failures of a vendored method, numbered as the single-method screen numbers them. */
    fun vendoredDetail(failures: List<String>): String = failures.mapIndexed { index, failure ->
        "실패 ${index + 1}\n$failure"
    }.joinToString("\n\n")

    fun fullText(device: String, build: String, app: String, report: SuiteReport): String = buildString {
        append("HAL CAM CTS suite").append('\n')
        append(device).append(" · ").append(build).append(" · app ").append(app).append('\n')
        append(headline(report)).append('\n')
        append(disclaimer()).append('\n')
        report.entries.forEach { entry ->
            append('\n').append('[').append(outcomeLabel(entry.outcome)).append("] ").append(entry.item.title)
            append(" · ").append(entryLine(entry)).append('\n')
            if (entry.detail.isNotEmpty()) append(entry.detail).append('\n')
        }
    }.trimEnd()
}
