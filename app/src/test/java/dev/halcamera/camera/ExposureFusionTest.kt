package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ExposureFusionTest {
    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun image(w: Int, h: Int, f: (Int, Int) -> Int) = IntArray(w * h) { f(it % w, it / w) }
    private fun r(c: Int) = c shr 16 and 0xFF

    private fun fuse(shots: List<IntArray>, w: Int, h: Int): IntArray {
        val weights = ExposureFusion.weights(shots.map { ExposureFusion.quality(it, w, h) }, w, h, 1)
        return IntArray(w * h).also { ExposureFusion.blendStrip(shots, w, h, 0, w, h, weights, w, h, it) }
    }

    @Test fun `weights add up to one at every pixel`() {
        val w = 8; val h = 6
        val shots = listOf(image(w, h) { x, _ -> grey(x * 30) }, image(w, h) { _, y -> grey(y * 40) }, image(w, h) { _, _ -> grey(128) })
        val weights = ExposureFusion.weights(shots.map { ExposureFusion.quality(it, w, h) }, w, h, 2)
        for (i in 0 until w * h) assertEquals(1f, weights.sumOf { it[i].toDouble() }.toFloat(), 1e-4f)
    }

    @Test fun `the well exposed shot dominates flat images`() {
        val w = 4; val h = 4
        val out = fuse(listOf(image(w, h) { _, _ -> grey(10) }, image(w, h) { _, _ -> grey(128) }, image(w, h) { _, _ -> grey(250) }), w, h)
        out.forEach { assertTrue("got ${r(it)}", r(it) in 110..150) }
    }

    @Test fun `each region takes the shot that exposes it well`() {
        // Left half is bright in the scene, right half dark: the darker shot exposes the left, the brighter the right.
        val w = 32; val h = 8
        val dark = image(w, h) { x, _ -> grey(if (x < w / 2) 128 else 5) }
        val bright = image(w, h) { x, _ -> grey(if (x < w / 2) 252 else 128) }
        val out = fuse(listOf(dark, bright), w, h)
        assertTrue("left ${r(out[2])}", r(out[2]) in 120..170)
        assertTrue("right ${r(out[w - 3])}", r(out[w - 3]) in 100..140)
    }

    @Test fun `identical shots come out unchanged and opaque`() {
        val w = 5; val h = 3
        val shot = image(w, h) { x, y -> (0xFF shl 24) or ((x * 40) shl 16) or ((y * 80) shl 8) or 77 }
        val out = fuse(listOf(shot, shot.copyOf(), shot.copyOf()), w, h)
        assertArrayEquals(shot, out)
    }

    @Test fun `strips with small weight maps cover the whole image`() {
        val w = 40; val h = 30; val mapW = 8; val mapH = 6
        val shots = listOf(image(w, h) { _, _ -> grey(60) }, image(w, h) { _, _ -> grey(180) })
        val weights = ExposureFusion.weights(shots.map { s ->
            ExposureFusion.quality(IntArray(mapW * mapH) { s[0] }, mapW, mapH) }, mapW, mapH, 1)
        val out = IntArray(w * h)
        var top = 0
        while (top < h) {
            val rows = minOf(7, h - top)
            val strips = shots.map { it.copyOfRange(top * w, (top + rows) * w) }
            val part = IntArray(w * rows)
            ExposureFusion.blendStrip(strips, w, rows, top, w, h, weights, mapW, mapH, part)
            part.copyInto(out, top * w)
            top += rows
        }
        assertTrue(out.all { it ushr 24 == 0xFF && r(it) in 60..180 })
        assertEquals(1, out.distinct().size)
    }
}
