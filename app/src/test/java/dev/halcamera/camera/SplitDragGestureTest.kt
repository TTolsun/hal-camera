package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class SplitDragGestureTest {
    private val start = SplitArrangement.identity(2)
    private fun gesture(arrangement: SplitArrangement = start, panel: Int = 1, width: Int = 400, height: Int = 800) =
        SplitDragGesture(arrangement,panel,width,height,24f)

    @Test fun `a short upward drag works from either side on phones and tablets`() {
        for ((w,h) in listOf(400 to 800,800 to 400,1600 to 2560)) for (panel in 0..1) {
            val next = gesture(panel = panel,width = w,height = h).update(0f,-24f)
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
            for ((dx,dy) in listOf(-24f to 0f,24f to 0f,0f to -24f,0f to 24f)) {
                val next = gesture(initial,panel).update(dx,dy)
                assertEquals(if (dx != 0f) SplitLayout.SIDE_BY_SIDE else SplitLayout.STACKED,next.layout)
                assertEquals(panel,if (dx < 0 || dy < 0) next.order.first() else next.order.last())
            }
        }
    }

    @Test fun `both layouts and panel orders use the same physical travel in every direction`() {
        for (density in listOf(1f,2f,4f)) for ((width,height) in listOf(360 to 720,720 to 360)) {
            val w = (width*density).toInt(); val h = (height*density).toInt()
            for (layout in listOf(SplitLayout.STACKED,SplitLayout.SIDE_BY_SIDE)) {
                for (order in listOf(listOf(0,1),listOf(1,0))) for (panel in 0..1) {
                    val initial = SplitArrangement(layout,order)
                    for ((dx,dy) in listOf(-1f to 0f,1f to 0f,0f to -1f,0f to 1f)) {
                        val drag = SplitDragGesture(initial,panel,w,h,24f*density)
                        assertEquals(initial,drag.update(dx*23f*density,dy*23f*density))
                        val result = drag.update(dx*24f*density,dy*24f*density)
                        assertEquals(if (dx == 0f) SplitLayout.STACKED else SplitLayout.SIDE_BY_SIDE,result.layout)
                        assertEquals(panel,if (dx < 0 || dy < 0) result.order.first() else result.order.last())
                        assertEquals(initial,drag.update(0f,0f))
                    }
                }
            }
        }
    }

    @Test fun `threshold hysteresis holds a target until the finger returns near pickup`() {
        val drag = gesture()
        assertEquals(start,drag.update(0f,-23f))
        val top = drag.update(0f,-24f)
        assertNotEquals(start,top)
        assertEquals(top,drag.update(0f,-22f))
        assertEquals(top,drag.update(0f,-18f))
        assertEquals(start,drag.update(0f,-16f))
        assertEquals(start,drag.update(0f,-22f))
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
        val corner = drag.update(24f,24f)
        assertEquals(SplitLayout.GRID,corner.layout)
        assertEquals(0,corner.order.last())
        assertEquals(corner,drag.update(24f,22f))
        assertEquals(SplitLayout.SIDE_BY_SIDE,drag.update(100f,20f).layout)
    }
}
