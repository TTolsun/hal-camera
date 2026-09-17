package dev.halcamera.cts.suite

import dev.halcamera.cts.vendored.VendoredReportPresenter
import dev.halcamera.ctsvendor.VendoredResult
import dev.halcamera.ctsvendor.VendoredTest
import dev.halcamera.ctsvendor.VendoredVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuiteReportPresenterTest {
    private val basic = SuiteItem(VendoredTest("android.hardware.camera2.cts.RecordingTest", "testBasicRecording"))
    private val slowMotion = SuiteItem(VendoredTest("android.hardware.camera2.cts.RecordingTest", "testSlowMotionRecording"))
    private val burst = SuiteItem(VendoredTest("android.hardware.camera2.cts.BurstCaptureTest", "testJpegBurst"))
    private val failure = "AssertionError: Camera 0: Video duration doesn't match\n  at RecordingTest.validateRecording(RecordingTest.java:2001)"

    private fun result(item: SuiteItem, verdict: VendoredVerdict, ms: Long, failures: List<String> = emptyList(), cancelled: Boolean = false) =
        VendoredResult(item.test, verdict, ms, failures, cancelled)

    @Test
    fun `entries map a vendored result to one outcome each`() {
        assertEquals(SuiteOutcome.PASS, SuiteEntry.of(basic, result(basic, VendoredVerdict.PASS, 118_000)).outcome)
        assertEquals(SuiteOutcome.FAIL, SuiteEntry.of(basic, result(basic, VendoredVerdict.FAIL, 45_000, listOf(failure))).outcome)
        assertEquals(SuiteOutcome.SKIP, SuiteEntry.of(basic, result(basic, VendoredVerdict.SKIP, 800)).outcome)
        assertEquals(SuiteOutcome.CANCELLED, SuiteEntry.of(basic, result(basic, VendoredVerdict.FAIL, 5_000, listOf(failure), cancelled = true)).outcome)
        assertEquals(SuiteOutcome.NOT_RUN, SuiteEntry.notRun(slowMotion).outcome)
    }

    @Test
    fun `headline counts every outcome and adds the total duration and the cancel`() {
        val done = SuiteReport(
            listOf(
                SuiteEntry.of(burst, result(burst, VendoredVerdict.PASS, 62_000)),
                SuiteEntry.of(basic, result(basic, VendoredVerdict.FAIL, 45_000, listOf(failure)))
            ),
            cancelled = false
        )
        assertEquals("2개 중 PASS 1 · FAIL 1 · 1분 47초", SuiteReportPresenter.headline(done))

        val stopped = SuiteReport(
            listOf(
                SuiteEntry.of(burst, result(burst, VendoredVerdict.PASS, 62_000)),
                SuiteEntry.of(basic, result(basic, VendoredVerdict.FAIL, 5_000, listOf(failure), cancelled = true)),
                SuiteEntry.notRun(slowMotion)
            ),
            cancelled = true
        )
        assertEquals("3개 중 PASS 1 · FAIL 0 · 실행 안 함 1 · 1분 7초 · 중단됨", SuiteReportPresenter.headline(stopped))
        assertEquals(1, stopped.passed)
        assertEquals(1, stopped.notRun)
    }

    @Test
    fun `entry line shows the source and the duration only for items that ran`() {
        assertEquals("BurstCaptureTest#testJpegBurst · 1분 2초", SuiteReportPresenter.entryLine(SuiteEntry.of(burst, result(burst, VendoredVerdict.PASS, 62_000))))
        assertEquals("RecordingTest#testSlowMotionRecording", SuiteReportPresenter.entryLine(SuiteEntry.notRun(slowMotion)))
    }

    @Test
    fun `details number the JUnit failures`() {
        assertEquals("", SuiteReportPresenter.vendoredDetail(emptyList()))
        assertTrue(SuiteReportPresenter.vendoredDetail(listOf(failure, failure)).startsWith("실패 1\nAssertionError"))
        assertTrue(SuiteReportPresenter.vendoredDetail(listOf(failure, failure)).contains("\n\n실패 2\n"))
    }

    @Test
    fun `full text carries the device line, the headline, the disclaimer and one block per item`() {
        val report = SuiteReport(
            listOf(
                SuiteEntry.of(burst, result(burst, VendoredVerdict.PASS, 62_000)),
                SuiteEntry.of(basic, result(basic, VendoredVerdict.FAIL, 45_000, listOf(failure))),
                SuiteEntry.notRun(slowMotion)
            ),
            cancelled = false
        )
        val lines = SuiteReportPresenter.fullText("samsung SM-S936N", "Android 16 (BP2A)", "0.9.0", report).split('\n')
        assertEquals("HAL CAM CTS suite", lines[0])
        assertEquals("samsung SM-S936N · Android 16 (BP2A) · app 0.9.0", lines[1])
        assertEquals("3개 중 PASS 1 · FAIL 1 · 실행 안 함 1 · 1분 47초", lines[2])
        assertEquals(VendoredReportPresenter.DISCLAIMER, lines[3])
        assertEquals("", lines[4])
        assertEquals("[PASS] testJpegBurst · BurstCaptureTest#testJpegBurst · 1분 2초", lines[5])
        assertEquals("", lines[6])
        assertEquals("[FAIL] testBasicRecording · RecordingTest#testBasicRecording · 45초", lines[7])
        assertEquals("실패 1", lines[8])
        assertEquals("[실행 안 함] testSlowMotionRecording · RecordingTest#testSlowMotionRecording", lines.last())
    }
}

class SuiteReportJsonTest {
    private val basic = SuiteItem(VendoredTest("android.hardware.camera2.cts.RecordingTest", "testBasicRecording"))
    private val burst = SuiteItem(VendoredTest("android.hardware.camera2.cts.BurstCaptureTest", "testJpegBurst"))

    @Test
    fun `json map carries the header, the counts and one entry per item with the screen's outcome names`() {
        val report = SuiteReport(
            listOf(
                SuiteEntry.of(burst, VendoredResult(burst.test, VendoredVerdict.PASS, 1_500, emptyList(), false)),
                SuiteEntry.of(basic, VendoredResult(basic.test, VendoredVerdict.FAIL, 5_000, listOf("boom"), false)),
                SuiteEntry.notRun(burst)
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
        assertEquals(
            listOf("vendored:android.hardware.camera2.cts.BurstCaptureTest#testJpegBurst", "vendored:android.hardware.camera2.cts.RecordingTest#testBasicRecording",
                "vendored:android.hardware.camera2.cts.BurstCaptureTest#testJpegBurst"),
            entries.map { it["key"] }
        )
        assertEquals(listOf("PASS", "FAIL", "NOT_RUN"), entries.map { it["outcome"] })
        assertEquals("RecordingTest#testBasicRecording", entries[1]["source"])
        assertTrue((entries[1]["detail"] as String).contains("boom"))
        assertEquals("", entries[2]["detail"])
    }
}
