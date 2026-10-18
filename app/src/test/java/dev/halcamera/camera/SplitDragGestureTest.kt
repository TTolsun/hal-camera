package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class SplitDragGestureTest {
    private val start = SplitArrangement.identity(2)
    private fun gesture(arrangement: SplitArrangement = start, panel: Int = 1, width: Int = 400, height: Int = 800) =
        SplitDragGesture(arrangement,panel,width,height,56f)

    @Test fun `a short upward drag works from either side on phones and tablets`() {
        for ((w,h) in listOf(400 to 800,800 to 400,1600 to 2560)) for (panel in 0..1) {
            val next = gesture(panel = panel,width = w,height = h).update(0f,-56f)
            assertEquals(SplitLayout.STACKED,next.layout)
            assertEquals(panel,next.order.first())
        }
    }

    @Test fun `pickup and small drift keep every panel in place in every layout`() {
        for (count in 2..5) for (layout in SplitLayout.entries) for (panel in 0 until count) {
            val initial = SplitArrangement.identity(count,layout)
            val drag = gesture(initial,panel)
            assertEquals(initial,drag.update(0f,0f))
            assertEquals(initial,drag.update(10f,-15f))
        }
    }

    @Test fun `all four directions place the selected panel on that side`() {
        for (layout in SplitLayout.entries) for (panel in 0..1) {
            val initial = SplitArrangement.identity(2,layout)
            for ((dx,dy) in listOf(-56f to 0f,56f to 0f,0f to -56f,0f to 56f)) {
                val next = gesture(initial,panel).update(dx,dy)
                assertEquals(if (dx != 0f) SplitLayout.SIDE_BY_SIDE else SplitLayout.STACKED,next.layout)
                assertEquals(panel,if (dx < 0 || dy < 0) next.order.first() else next.order.last())
            }
        }
    }

    @Test fun `threshold hysteresis holds a target until the finger returns near pickup`() {
        val drag = gesture()
        assertEquals(start,drag.update(0f,-55f))
        val top = drag.update(0f,-56f)
        assertNotEquals(start,top)
        assertEquals(top,drag.update(0f,-50f))
        assertEquals(top,drag.update(0f,-40f))
        assertEquals(start,drag.update(0f,-38f))
        assertEquals(start,drag.update(0f,-50f))
    }

    @Test fun `diagonal jitter does not alternate between vertical and horizontal`() {
        val drag = gesture()
        val top = drag.update(50f,-60f)
        assertEquals(SplitLayout.STACKED,top.layout)
        assertEquals(top,drag.update(61f,-60f))
        assertEquals(SplitLayout.SIDE_BY_SIDE,drag.update(80f,-60f).layout)
        assertEquals(SplitLayout.STACKED,drag.update(0f,60f).layout)
    }

    @Test fun `small areas shorten travel and multi panel diagonals select grid corners`() {
        assertEquals(SplitLayout.STACKED,gesture(width = 200,height = 100).update(0f,-18f).layout)
        val initial = SplitArrangement.identity(4)
        val drag = gesture(initial,panel = 0)
        val corner = drag.update(56f,56f)
        assertEquals(SplitLayout.GRID,corner.layout)
        assertEquals(0,corner.order.last())
        assertEquals(corner,drag.update(56f,50f))
        assertEquals(SplitLayout.SIDE_BY_SIDE,drag.update(100f,20f).layout)
    }
}
