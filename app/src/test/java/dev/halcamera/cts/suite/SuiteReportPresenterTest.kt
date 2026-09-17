package dev.halcamera.cts.suite

import dev.halcamera.cts.CameraCaseResult
import dev.halcamera.cts.CaseReport
import dev.halcamera.cts.CaseReportPresenter
import dev.halcamera.cts.CtsCatalog
import dev.halcamera.cts.StepResult
import dev.halcamera.cts.Verdict
import dev.halcamera.cts.vendored.VendoredReportPresenter
import dev.halcamera.ctsvendor.VendoredResult
import dev.halcamera.ctsvendor.VendoredTest
import dev.halcamera.ctsvendor.VendoredVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuiteReportPresenterTest {
    private val fast = SuiteItem.Custom(CtsCatalog.byId(CtsCatalog.FAST_ON_OFF)!!)
    private val snapshot = SuiteItem.Custom(CtsCatalog.byId(CtsCatalog.VIDEO_SNAPSHOT)!!)
    private val basic = SuiteItem.Vendored(VendoredTest("android.hardware.camera2.cts.RecordingTest", "testBasicRecording"))
    private val failure = "AssertionError: Camera 0: Video duration doesn't match\n  at RecordingTest.validateRecording(RecordingTest.java:2001)"

    private fun customReport(vararg steps: StepResult, cancelled: Boolean = false) =
        CaseReport(fast.source, listOf(CameraCaseResult("0", steps.toList())), cancelled)

    @Test
    fun `entries map a case report and a vendored result to one outcome each`() {
        assertEquals(SuiteOutcome.PASS, SuiteEntry.of(fast, customReport(StepResult("open", Verdict.PASS)), 1000).outcome)
        assertEquals(SuiteOutcome.FAIL, SuiteEntry.of(fast, customReport(StepResult("open", Verdict.FAIL)), 1000).outcome)
        assertEquals(SuiteOutcome.CANCELLED, SuiteEntry.of(fast, customReport(StepResult("open", Verdict.PASS), cancelled = true), 1000).outcome)
        assertEquals(SuiteOutcome.PASS, SuiteEntry.of(basic, VendoredResult(basic.test, VendoredVerdict.PASS, 118_000, emptyList(), false)).outcome)
        assertEquals(SuiteOutcome.SKIP, SuiteEntry.of(basic, VendoredResult(basic.test, VendoredVerdict.SKIP, 800, emptyList(), false)).outcome)
        assertEquals(SuiteOutcome.CANCELLED, SuiteEntry.of(basic, VendoredResult(basic.test, VendoredVerdict.FAIL, 5_000, listOf(failure), true)).outcome)
        assertEquals(SuiteOutcome.NOT_RUN, SuiteEntry.notRun(snapshot).outcome)
    }

    @Test
    fun `headline counts every outcome and adds the total duration and the cancel`() {
        val done = SuiteReport(
            listOf(
                SuiteEntry.of(fast, customReport(StepResult("open", Verdict.PASS)), 62_000),
                SuiteEntry.of(basic, VendoredResult(basic.test, VendoredVerdict.FAIL, 45_000, listOf(failure), false))
            ),
            cancelled = false
        )
        assertEquals("2개 중 PASS 1 · FAIL 1 · 1분 47초", SuiteReportPresenter.headline(done))

        val stopped = SuiteReport(
            listOf(
                SuiteEntry.of(fast, customReport(StepResult("open", Verdict.PASS)), 62_000),
                SuiteEntry.of(basic, VendoredResult(basic.test, VendoredVerdict.FAIL, 5_000, listOf(failure), true)),
                SuiteEntry.notRun(snapshot)
            ),
            cancelled = true
        )
        assertEquals("3개 중 PASS 1 · FAIL 0 · 실행 안 함 1 · 1분 7초 · 중단됨", SuiteReportPresenter.headline(stopped))
        assertEquals(1, stopped.passed)
        assertEquals(1, stopped.notRun)
    }

    @Test
    fun `entry line shows the source and the duration only for items that ran`() {
        assertEquals("custom#FastOnOff · 1분 2초", SuiteReportPresenter.entryLine(SuiteEntry.of(fast, customReport(StepResult("open", Verdict.PASS)), 62_000)))
        assertEquals("custom#VideoSnapshot", SuiteReportPresenter.entryLine(SuiteEntry.notRun(snapshot)))
    }

    @Test
    fun `details reuse the per-camera table and the numbered failures`() {
        val custom = SuiteReportPresenter.customDetail(listOf(CameraCaseResult("0", listOf(StepResult("open", Verdict.PASS, listOf("first frame 120 ms")))), CameraCaseResult("1", listOf(StepResult("open", Verdict.FAIL)))))
        assertEquals(
            listOf("ID 0 · PASS 1 FAIL 0 SKIP 0", "PASS  open", "      first frame 120 ms", "", "ID 1 · PASS 0 FAIL 1 SKIP 0", "FAIL  open"),
            custom.split('\n')
        )
        assertEquals("", SuiteReportPresenter.vendoredDetail(emptyList()))
        assertTrue(SuiteReportPresenter.vendoredDetail(listOf(failure, failure)).startsWith("실패 1\nAssertionError"))
        assertTrue(SuiteReportPresenter.vendoredDetail(listOf(failure, failure)).contains("\n\n실패 2\n"))
    }

    @Test
    fun `disclaimer follows the kind of the items`() {
        assertEquals(CaseReportPresenter.DISCLAIMER, SuiteReportPresenter.disclaimer(listOf(fast, snapshot)))
        assertEquals(VendoredReportPresenter.DISCLAIMER, SuiteReportPresenter.disclaimer(listOf(basic)))
    }

    @Test
    fun `full text carries the device line, the headline, the disclaimer and one block per item`() {
        val report = SuiteReport(
            listOf(
                SuiteEntry.of(fast, customReport(StepResult("open", Verdict.PASS)), 62_000),
                SuiteEntry.of(basic, VendoredResult(basic.test, VendoredVerdict.FAIL, 45_000, listOf(failure), false)),
                SuiteEntry.notRun(snapshot)
            ),
            cancelled = false
        )
        val lines = SuiteReportPresenter.fullText("samsung SM-S936N", "Android 16 (BP2A)", "0.9.0", report).split('\n')
        assertEquals("HAL CAM CTS suite", lines[0])
        assertEquals("samsung SM-S936N · Android 16 (BP2A) · app 0.9.0", lines[1])
        assertEquals("3개 중 PASS 1 · FAIL 1 · 실행 안 함 1 · 1분 47초", lines[2])
        assertEquals(CaseReportPresenter.DISCLAIMER + " " + VendoredReportPresenter.DISCLAIMER, lines[3])
        assertEquals("", lines[4])
        assertEquals("[PASS] 빠른 켜기·끄기 · custom#FastOnOff · 1분 2초", lines[5])
        assertEquals("ID 0 · PASS 1 FAIL 0 SKIP 0", lines[6])
        assertEquals("PASS  open", lines[7])
        assertEquals("", lines[8])
        assertEquals("[FAIL] testBasicRecording · RecordingTest#testBasicRecording · 45초", lines[9])
        assertEquals("실패 1", lines[10])
        assertEquals("[실행 안 함] 동영상 스냅샷 · custom#VideoSnapshot", lines.last())
    }
}

class SuiteReportJsonTest {
    private val fast = SuiteItem.Custom(CtsCatalog.byId(CtsCatalog.FAST_ON_OFF)!!)
    private val basic = SuiteItem.Vendored(VendoredTest("android.hardware.camera2.cts.RecordingTest", "testBasicRecording"))

    @Test
    fun `json map carries the header, the counts and one entry per item with the screen's outcome names`() {
        val report = SuiteReport(
            listOf(
                SuiteEntry.of(fast, CaseReport(fast.source, listOf(CameraCaseResult("0", listOf(StepResult("open", Verdict.PASS)))), false), 1_500),
                SuiteEntry.of(basic, VendoredResult(basic.test, VendoredVerdict.FAIL, 5_000, listOf("boom"), false)),
                SuiteEntry.notRun(fast)
            ),
            cancelled = true
        )
        val map = report.toJsonMap("samsung SM-S936N", "Android 16 (build)", "0.9.0")
        assertEquals(SuiteReport.SCHEMA, map["schema"])
        assertEquals("samsung SM-S936N", map["device"])
        assertEquals(true, map["cancelled"])
        assertEquals(1, map["passed"]); assertEquals(1, map["failed"]); assertEquals(1, map["not_run"])
        assertEquals(6_500L, map["duration_ms"])
        assertEquals(SuiteReportPresenter.headline(report), map["headline"])
        @Suppress("UNCHECKED_CAST") val entries = map["entries"] as List<Map<String, Any?>>
        assertEquals(listOf("custom:fast_on_off", "vendored:android.hardware.camera2.cts.RecordingTest#testBasicRecording", "custom:fast_on_off"), entries.map { it["key"] })
        assertEquals(listOf("PASS", "FAIL", "NOT_RUN"), entries.map { it["outcome"] })
        assertEquals("RecordingTest#testBasicRecording", entries[1]["source"])
        assertTrue((entries[1]["detail"] as String).contains("boom"))
        assertEquals("", entries[2]["detail"])
    }
}
