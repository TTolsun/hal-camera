package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class SplitArrangementTest {
    private val w = 1000
    private val h = 1600

    @Test fun `default is side by side in camera order`() {
        val two = SplitArrangement.identity(2)
        assertEquals(SplitLayout.SIDE_BY_SIDE, two.layout)
        assertEquals(listOf(0,1), two.order)
        assertEquals(concurrentFrames(2,w,h,SplitLayout.SIDE_BY_SIDE), two.frames(w,h))
    }

    @Test fun `edges snap like windows arrow keys`() {
        val two = SplitArrangement.identity(2)
        assertEquals(SplitArrangement(SplitLayout.SIDE_BY_SIDE, listOf(1,0)), two.snap(1, 10f, 800f, w, h))
        assertEquals(SplitArrangement(SplitLayout.SIDE_BY_SIDE, listOf(0,1)), two.snap(1, 990f, 800f, w, h))
        assertEquals(SplitArrangement(SplitLayout.STACKED, listOf(1,0)), two.snap(1, 500f, 10f, w, h))
        assertEquals(SplitArrangement(SplitLayout.STACKED, listOf(0,1)), two.snap(1, 500f, 1590f, w, h))
        val top = two.snap(1, 500f, 10f, w, h).frames(w,h)
        assertEquals(ConcurrentFrame(0,0,w,h/2), top[1])
        assertEquals(ConcurrentFrame(0,h/2,w,h/2), top[0])
    }

    @Test fun `two panels take the nearer edge at a corner`() {
        val two = SplitArrangement.identity(2)
        assertEquals(SplitLayout.SIDE_BY_SIDE, two.snap(1, 5f, 200f, w, h).layout)
        assertEquals(SplitLayout.STACKED, two.snap(1, 200f, 5f, w, h).layout)
    }

    @Test fun `centre drop swaps with the panel underneath and keeps the layout`() {
        val stacked = SplitArrangement(SplitLayout.STACKED, listOf(0,1))
        assertEquals(SplitArrangement(SplitLayout.STACKED, listOf(1,0)), stacked.snap(0, 500f, 1100f, w, h))
        assertSame(stacked, stacked.snap(0, 500f, 500f, w, h))
    }

    @Test fun `corners pick a grid cell for three or more panels`() {
        val three = SplitArrangement.identity(3)
        val topRight = three.snap(2, 990f, 10f, w, h)
        assertEquals(SplitLayout.GRID, topRight.layout)
        assertEquals(listOf(0,2,1), topRight.order)
        assertEquals(ConcurrentFrame(w/2,0,w/2,h/2), topRight.frames(w,h)[2])
        val four = SplitArrangement.identity(4).snap(0, 990f, 1590f, w, h)
        assertEquals(listOf(1,2,3,0), four.order)
    }

    @Test fun `every snap keeps a full non overlapping tiling`() {
        for (count in 2..5) {
            val start = SplitArrangement.identity(count)
            for (x in listOf(0f, 300f, 500f, 900f, 1000f)) for (y in listOf(0f, 400f, 800f, 1500f, 1600f)) {
                val next = start.snap(count-1, x, y, w, h)
                assertEquals((0 until count).toList(), next.order.sorted())
                assertEquals(w*h, next.frames(w,h).sumOf { it.width*it.height })
            }
        }
    }

    @Test fun `grid falls back to side by side below three panels`() {
        assertEquals(SplitLayout.SIDE_BY_SIDE, SplitArrangement.identity(2, SplitLayout.GRID).layout)
        assertEquals(SplitLayout.GRID, SplitArrangement.identity(3, SplitLayout.GRID).layout)
        assertThrows(IllegalArgumentException::class.java) { SplitArrangement(SplitLayout.STACKED, listOf(0,0)) }
    }
}
