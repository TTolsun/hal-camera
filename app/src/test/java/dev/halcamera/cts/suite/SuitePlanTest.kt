package dev.halcamera.cts.suite

import dev.halcamera.cts.CtsCatalog
import dev.halcamera.ctsvendor.VendoredTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuitePlanTest {
    private val basic = VendoredTest("android.hardware.camera2.cts.RecordingTest", "testBasicRecording")
    private val slowMotion = VendoredTest("android.hardware.camera2.cts.RecordingTest", "testSlowMotionRecording")
    private val all = SuitePlan.items(CtsCatalog.cases, listOf(basic, slowMotion))

    @Test
    fun `items keep the custom cases first and the vendored methods after them, every key unique`() {
        assertEquals(CtsCatalog.cases.size + 2, all.size)
        assertEquals(CtsCatalog.cases.map { SuiteItem.CUSTOM_PREFIX + it.id }, all.take(CtsCatalog.cases.size).map { it.key })
        assertEquals(SuiteItem.VENDORED_PREFIX + basic.id, all[CtsCatalog.cases.size].key)
        assertEquals(all.size, all.map { it.key }.toSet().size)
    }

    @Test
    fun `select keeps the list order whatever order the keys come in and drops unknown keys`() {
        val keys = listOf(SuiteItem.VENDORED_PREFIX + basic.id, "custom:nope", SuiteItem.CUSTOM_PREFIX + CtsCatalog.VIDEO_SNAPSHOT, SuiteItem.CUSTOM_PREFIX + CtsCatalog.FAST_ON_OFF)
        val chosen = SuitePlan.select(all, keys)
        assertEquals(listOf("빠른 켜기·끄기", "동영상 스냅샷", "testBasicRecording"), chosen.map { it.title })
    }

    @Test
    fun `every custom case carries a positive estimate and only measured vendored methods do`() {
        CtsCatalog.cases.forEach { spec -> assertTrue(spec.id, spec.estimateSeconds(4) > 0) }
        assertEquals(120, SuiteItem.Vendored(basic).estimateSeconds(4))
        assertNull(SuiteItem.Vendored(slowMotion).estimateSeconds(4))
        assertTrue(SuiteItem.Vendored(slowMotion).needsAudio)
    }

    @Test
    fun `labels and the summary line round up to whole minutes and name the unmeasured items`() {
        val fast = SuiteItem.Custom(CtsCatalog.byId(CtsCatalog.FAST_ON_OFF)!!)
        assertEquals("약 1분", SuitePlan.estimateLabel(fast, 4))
        assertEquals("시간 미상", SuitePlan.estimateLabel(SuiteItem.Vendored(slowMotion), 4))
        assertEquals("선택한 항목이 없습니다", SuitePlan.summaryLine(emptyList(), 4))
        assertEquals("선택 2개 · 약 3분", SuitePlan.summaryLine(listOf(fast, SuiteItem.Vendored(basic)), 4))
        assertEquals("선택 2개 · 약 1분 + 미상 1개", SuitePlan.summaryLine(listOf(fast, SuiteItem.Vendored(slowMotion)), 4))
        assertEquals("선택 1개 · 시간 미상", SuitePlan.summaryLine(listOf(SuiteItem.Vendored(slowMotion)), 4))
    }

    @Test
    fun `minutes never read as zero`() {
        assertEquals(1, SuitePlan.minutes(0))
        assertEquals(1, SuitePlan.minutes(59))
        assertEquals(2, SuitePlan.minutes(61))
    }
}
