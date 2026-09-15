package dev.halcamera.cts

import dev.halcamera.cts.recording.BasicRecordingRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseReportPresenterTest {
    private val pass = StepResult("HIGH", Verdict.PASS, listOf("3840x2160@30fps · duration 3049ms"))
    private val fail = StepResult("1080P", Verdict.FAIL, listOf("1920x1080@30fps · duration 2000ms", "Camera 0: Video duration doesn't match"))
    private val skip = StepResult("CIF", Verdict.SKIP, listOf("CamcorderProfile CIF is not present"))

    private fun report(vararg cameras: CameraCaseResult, cancelled: Boolean = false) =
        CaseReport(BasicRecordingRules.SOURCE, cameras.toList(), cancelled)

    @Test
    fun `headline counts failures across cameras and marks a cancelled run`() {
        assertEquals("PASS · 카메라 2대, FAIL 0", CaseReportPresenter.headline(report(CameraCaseResult("0", listOf(pass)), CameraCaseResult("1", listOf(pass, skip)))))
        assertEquals("FAIL · 카메라 1대, FAIL 1", CaseReportPresenter.headline(report(CameraCaseResult("0", listOf(pass, fail)))))
        assertEquals("PASS · 카메라 1대, FAIL 0 · 중단됨", CaseReportPresenter.headline(report(CameraCaseResult("0", listOf(pass)), cancelled = true)))
    }

    @Test
    fun `camera line carries the three counts`() {
        assertEquals("ID 0 · PASS 1 FAIL 1 SKIP 1", CaseReportPresenter.cameraLine(CameraCaseResult("0", listOf(pass, fail, skip))))
    }

    @Test
    fun `table pads step ids and indents every detail line`() {
        val lines = CaseReportPresenter.cameraTable(CameraCaseResult("0", listOf(pass, fail, skip))).split('\n')
        assertEquals("PASS  HIGH ", lines[0])
        assertEquals("      3840x2160@30fps · duration 3049ms", lines[1])
        assertEquals("FAIL  1080P", lines[2])
        assertEquals("      1920x1080@30fps · duration 2000ms", lines[3])
        assertEquals("      Camera 0: Video duration doesn't match", lines[4])
        assertEquals("SKIP  CIF  ", lines[5])
        assertEquals(7, lines.size)
    }

    @Test
    fun `full text leads with the source, device, headline and disclaimer`() {
        val text = CaseReportPresenter.fullText("samsung SM-S936N", "Android 16 (BUILD)", "0.5.1", report(CameraCaseResult("0", listOf(pass, fail))))
        val lines = text.split('\n')
        assertEquals("HAL CAM CTS case · RecordingTest#testBasicRecording", lines[0])
        assertEquals("samsung SM-S936N · Android 16 (BUILD) · app 0.5.1", lines[1])
        assertEquals("FAIL · 카메라 1대, FAIL 1", lines[2])
        assertEquals(CaseReportPresenter.DISCLAIMER, lines[3])
        assertEquals("", lines[4])
        assertEquals("ID 0 · PASS 1 FAIL 1 SKIP 0", lines[5])
        assertTrue(text.endsWith("      Camera 0: Video duration doesn't match"))
    }
}
