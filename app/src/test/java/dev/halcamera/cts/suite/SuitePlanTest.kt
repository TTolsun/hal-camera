package dev.halcamera.cts.suite

import dev.halcamera.ctsvendor.VendoredTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuitePlanTest {
    private val basic = VendoredTest("android.hardware.camera2.cts.RecordingTest", "testBasicRecording")
    private val slowMotion = VendoredTest("android.hardware.camera2.cts.RecordingTest", "testSlowMotionRecording")
    private val burst = VendoredTest("android.hardware.camera2.cts.BurstCaptureTest", "testJpegBurst")
    private val all = SuitePlan.items(listOf(basic, slowMotion, burst))

    @Test
    fun `items keep the catalog order and every key carries the vendored prefix once`() {
        assertEquals(listOf(basic, slowMotion, burst).map { SuiteItem.PREFIX + it.id }, all.map { it.key })
        assertEquals(all.size, all.map { it.key }.toSet().size)
        assertEquals("testJpegBurst", all[2].title)
        assertEquals("BurstCaptureTest#testJpegBurst", all[2].source)
    }

    @Test
    fun `select keeps the list order whatever order the keys come in and drops unknown keys`() {
        val keys = listOf(SuiteItem.PREFIX + burst.id, "vendored:nope", SuiteItem.PREFIX + basic.id)
        val chosen = SuitePlan.select(all, keys)
        assertEquals(listOf("testBasicRecording", "testJpegBurst"), chosen.map { it.title })
    }

    @Test
    fun `only measured methods carry an estimate and every method may record`() {
        assertEquals(120, SuiteItem(basic).estimateSeconds(4))
        assertNull(SuiteItem(slowMotion).estimateSeconds(4))
        assertTrue(SuiteItem(slowMotion).needsAudio)
    }

    @Test
    fun `labels and the summary line round up to whole minutes and name the unmeasured items`() {
        assertEquals("약 2분", SuitePlan.estimateLabel(SuiteItem(basic), 4))
        assertEquals("시간 미상", SuitePlan.estimateLabel(SuiteItem(slowMotion), 4))
        assertEquals("선택한 항목이 없습니다", SuitePlan.summaryLine(emptyList(), 4))
        assertEquals("선택 1개 · 약 2분", SuitePlan.summaryLine(listOf(SuiteItem(basic)), 4))
        assertEquals("선택 2개 · 약 2분 + 미상 1개", SuitePlan.summaryLine(listOf(SuiteItem(basic), SuiteItem(slowMotion)), 4))
        assertEquals("선택 1개 · 시간 미상", SuitePlan.summaryLine(listOf(SuiteItem(slowMotion)), 4))
    }

    @Test
    fun `minutes never read as zero`() {
        assertEquals(1, SuitePlan.minutes(0))
        assertEquals(1, SuitePlan.minutes(59))
        assertEquals(2, SuitePlan.minutes(61))
    }
}
