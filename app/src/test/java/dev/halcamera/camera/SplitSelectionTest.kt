package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class SplitSelectionTest {
    private fun plan(vararg ids: String, advertised: Boolean = true) = ConcurrentPlan(ids.map {
        ConcurrentStream(ConcurrentCamera(it, null, emptyList(), emptyList()), LiveSize(1280,720), LiveSize(1920,1440))
    }, advertised)
    private val pair = plan("0", "1")
    private val triple = plan("0", "1", "2")

    @Test fun `draft changes never modify the active selection`() {
        val active = listOf("0", "1")
        val draft = SplitSelection(listOf(pair), active)
        assertTrue(draft.assign(0,"1"))
        assertEquals(listOf("0", "1"), active)
        assertEquals(listOf("1", "0"), draft.ids)
        assertTrue(draft.valid)
    }

    @Test fun `count transitions retain valid combinations`() {
        val draft = SplitSelection(listOf(pair,triple), listOf("0","1","2"))
        assertEquals(listOf(2,3), draft.counts)
        draft.selectCount(2)
        assertEquals(pair.streams.map { it.camera.id }, draft.ids)
        draft.selectCount(3)
        assertTrue(draft.valid)
    }

    @Test fun `unadvertised combinations cannot be applied`() {
        val unavailable = plan("0","3", advertised = false)
        val draft = SplitSelection(listOf(unavailable,pair), listOf("0","3"))
        assertEquals(listOf("0","1"), draft.ids)
        assertThrows(IllegalArgumentException::class.java) { draft.selectPlan(unavailable) }
        assertThrows(IllegalArgumentException::class.java) { draft.selectCount(4) }
        assertTrue(draft.valid)
    }

    @Test fun `reordered valid selection survives reopening and no op count selection`() {
        val draft = SplitSelection(listOf(pair,triple), listOf("1","0"))
        draft.selectCount(2)
        assertFalse(draft.assign(0,"1"))
        assertEquals(listOf("1","0"), draft.ids)
        assertThrows(IllegalArgumentException::class.java) { draft.assign(0,"2") }
    }
}
