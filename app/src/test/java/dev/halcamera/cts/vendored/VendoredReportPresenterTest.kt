package dev.halcamera.cts.vendored

import dev.halcamera.ctsvendor.VendoredCatalog
import dev.halcamera.ctsvendor.VendoredResult
import dev.halcamera.ctsvendor.VendoredTest
import dev.halcamera.ctsvendor.VendoredVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VendoredReportPresenterTest {
    private val test = VendoredTest("android.hardware.camera2.cts.RecordingTest", "testBasicRecording")
    private val failure = "AssertionError: Camera 0: Video duration doesn't match\n  at RecordingTest.validateRecording(RecordingTest.java:2001)"

    private fun result(verdict: VendoredVerdict, durationMs: Long, failures: List<String> = emptyList(), cancelled: Boolean = false) =
        VendoredResult(test, verdict, durationMs, failures, cancelled)

    @Test
    fun `test names itself as the CTS class#method`() {
        assertEquals("RecordingTest#testBasicRecording", test.source)
        assertEquals("android.hardware.camera2.cts.RecordingTest#testBasicRecording", test.id)
    }

    @Test
    fun `headline states the verdict, the failure count, the duration and a cancel`() {
        assertEquals("PASS · 1분 23초", VendoredReportPresenter.headline(result(VendoredVerdict.PASS, 83_000)))
        assertEquals("FAIL · 실패 2건 · 45초", VendoredReportPresenter.headline(result(VendoredVerdict.FAIL, 45_400, listOf(failure, failure))))
        assertEquals("SKIP · 이 기기에서는 검사할 것이 없습니다 · 0.8초", VendoredReportPresenter.headline(result(VendoredVerdict.SKIP, 850)))
        assertEquals("중단됨 · 12초 · 실패 1건", VendoredReportPresenter.headline(result(VendoredVerdict.FAIL, 12_000, listOf(failure), cancelled = true)))
        assertEquals("중단됨 · 12초", VendoredReportPresenter.headline(result(VendoredVerdict.PASS, 12_000, cancelled = true)))
    }

    @Test
    fun `full text carries the source, the device line, the disclaimer and every failure`() {
        val text = VendoredReportPresenter.fullText("samsung SM-S936N", "Android 16 (BP2A)", "0.8.0", result(VendoredVerdict.FAIL, 45_000, listOf(failure)))
        val lines = text.split('\n')
        assertEquals("HAL CAM CTS vendored · RecordingTest#testBasicRecording", lines[0])
        assertEquals("android.hardware.camera2.cts.RecordingTest", lines[1])
        assertEquals("samsung SM-S936N · Android 16 (BP2A) · app 0.8.0", lines[2])
        assertEquals("FAIL · 실패 1건 · 45초", lines[3])
        assertEquals(VendoredReportPresenter.DISCLAIMER, lines[4])
        assertEquals("", lines[5])
        assertEquals("실패 1", lines[6])
        assertEquals("AssertionError: Camera 0: Video duration doesn't match", lines[7])
        assertTrue(lines[8].startsWith("  at RecordingTest.validateRecording"))
        assertEquals(9, lines.size)
    }
}

class VendoredSkipPresenterTest {
    private val test = VendoredTest("android.hardware.camera2.cts.StillCaptureTest", "testHeicUltraHdrCapture")

    @Test
    fun `a SKIP shows its own reasons, a silent skipper its hint, and the rest the fallback line`() {
        val reasons = listOf("Camera 0 does not support HEIC_ULTRAHDR, skipping", "Camera 1 does not support HEIC_ULTRAHDR, skipping")
        val skipped = VendoredResult(test, VendoredVerdict.SKIP, 300, emptyList(), false, reasons)
        assertEquals(reasons.joinToString("\n"), VendoredReportPresenter.detail(skipped))
        assertTrue(VendoredReportPresenter.fullText("d", "b", "0.9.0", skipped).endsWith("건너뛴 이유\n" + reasons.joinToString("\n")))

        val av1 = VendoredTest("android.hardware.camera2.cts.RecordingTest", "testBasic10BitRecordingAV1")
        val silent = VendoredResult(av1, VendoredVerdict.SKIP, 40, emptyList(), false)
        assertEquals(VendoredCatalog.silentSkipHints[av1.id], VendoredReportPresenter.detail(silent))

        val unknown = VendoredResult(test, VendoredVerdict.SKIP, 40, emptyList(), false)
        assertTrue(VendoredReportPresenter.detail(unknown).startsWith("카메라를 하나도 열지 않고"))

        val failed = VendoredResult(test, VendoredVerdict.FAIL, 40, listOf("boom"), false)
        assertEquals("실패 1\nboom", VendoredReportPresenter.detail(failed))
    }

    @Test
    fun `the catalog leaves the upstream TODO stubs out`() {
        assertTrue(VendoredCatalog.unimplemented.all { it.startsWith("android.hardware.camera2.cts.RecordingTest#") })
        assertEquals(3, VendoredCatalog.unimplemented.size)
    }
}
