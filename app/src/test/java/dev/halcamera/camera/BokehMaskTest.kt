package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class BokehMaskTest {
    @Test fun `thin foreground extensions survive a missing coarse depth edge in both contrast directions`() {
        val w = 256; val h = 128
        for (brightSubject in listOf(false, true)) {
            fun gray(value: Int) = -0x1000000 or (value shl 16) or (value shl 8) or value
            val subject = if (brightSubject) 220 else 35
            val pixels = IntArray(w * h) { i ->
                val x = i % w; val y = i / w
                gray(if (x < 72 || (x < 96 && y in 63..65)) subject
                    else if ((x + y) % 2 == 0) { if (brightSubject) 15 else 240 }
                    else { if (brightSubject) 47 else 208 })
            }
            // The model misses the narrow extension entirely; source pixels still show it.
            val depth = RelativeDepth(w, h, FloatArray(w * h) { if (it % w < 72) 1f else 0f })
            val result = DepthBokeh.render(pixels, w, h, depth, .1f, .5f, 100)
            val recovered = result[64 * w + 77] and 255
            val backgroundMean = if (brightSubject) 31 else 224
            assertTrue("thin extension value=$recovered subject=$subject",
                kotlin.math.abs(recovered - subject) < kotlin.math.abs(backgroundMean - subject) / 2)
            // A disconnected high-contrast background texture must still be blurred.
            val far = result[64 * w + 180] and 255
            assertTrue(far in if (brightSubject) 25..37 else 218..230)
            for (y in 0 until h) for (x in 0 until 72) assertEquals(pixels[y * w + x], result[y * w + x])
        }
    }

    @Test fun `uniform background weight does not copy image texture into the blur mask`() {
        val w = 80; val h = 50
        val pixels = IntArray(w * h) { if (it % 3 == 0) -1 else 0xff123456.toInt() }
        for (weight in listOf(0f, .4f, 1f)) {
            val result = BokehMask.refine(pixels, w, h, FloatArray(pixels.size) { weight })
            result.forEach { assertEquals(weight, it, .00001f) }
        }
    }

    @Test fun `flat guidance and narrow images stay finite and cannot increase blur`() {
        for ((w, h) in listOf(1 to 1, 1 to 64, 64 to 1, 20 to 30)) {
            val coarse = FloatArray(w * h) { (it % 11) / 10f }
            val result = BokehMask.refine(IntArray(w * h) { 0xff808080.toInt() }, w, h, coarse)
            result.forEachIndexed { i, value -> assertTrue(value.isFinite() && value in 0f..coarse[i]) }
        }
    }
}
