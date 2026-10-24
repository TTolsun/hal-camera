package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class DepthBokehTest {
    @Test fun `relative depth preserves near far order without claiming metres`() {
        val depth = RelativeDepth.normalize(10, 10, FloatArray(100) { 10f + it })
        assertEquals(0f, depth.at(0f, 0f), .001f)
        assertEquals(1f, depth.at(1f, 1f), .001f)
        assertTrue(depth.at(.8f, .8f) > depth.at(.2f, .2f))
    }

    @Test fun `invalid and flat model outputs fail instead of fabricated depth`() {
        for (raw in listOf(floatArrayOf(Float.NaN, 1f), floatArrayOf(1f, 1f))) {
            assertThrows(IllegalArgumentException::class.java) { RelativeDepth.normalize(2, 1, raw) }
        }
    }

    @Test fun `foreground stays exact while textured background is blurred`() {
        val w = 128; val h = 64
        val pixels = IntArray(w * h) { if (it % w < 64) 0xffff0000.toInt() else if (it % 2 == 0) -1 else 0xff000000.toInt() }
        val depth = RelativeDepth(w, h, FloatArray(pixels.size) { if (it % w < 64) 1f else 0f })
        val result = DepthBokeh.render(pixels, w, h, depth, .25f, .5f, 100)
        for (y in 0 until h) for (x in 0 until 64) assertEquals(pixels[y * w + x], result[y * w + x])
        val background = result[32 * w + 90]
        assertTrue((background and 255) in 30..225)
        // Red foreground must not bleed into the blurred monochrome background.
        for (x in 65..75) {
            val pixel = result[32 * w + x]
            assertEquals((pixel ushr 16) and 255, pixel and 255)
        }
    }

    @Test fun `zero strength is identity and choosing background protects its plane`() {
        val pixels = IntArray(64) { if (it % 2 == 0) -1 else 0xff123456.toInt() }
        val depth = RelativeDepth(8, 8, FloatArray(64) { if (it % 8 < 4) 1f else 0f })
        assertArrayEquals(pixels, DepthBokeh.render(pixels, 8, 8, depth, .1f, .5f, 0))
        assertArrayEquals(pixels, DepthBokeh.render(pixels, 8, 8, depth, .9f, .5f, 100))
    }

    @Test fun `constant colours and one pixel dimensions are stable`() {
        val pixels = IntArray(20) { 0xffabcdef.toInt() }
        val depth = RelativeDepth(1, 20, FloatArray(20) { it / 19f })
        assertArrayEquals(pixels, DepthBokeh.render(pixels, 1, 20, depth, 0f, 1f, 100))
    }
}
