package dev.halcamera.camera

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class YuvPackingTest {
    private fun plane(bytes: ByteArray, row: Int, pixel: Int, offset: Int = 0) =
        YuvPacking.Plane(ByteBuffer.wrap(bytes).apply { position(offset) }, row, pixel)

    @Test fun `planar padded rows are packed in NV21 VU order`() {
        val y = byteArrayOf(1,2,3,4,99,99,5,6,7,8)
        val u = byteArrayOf(11,12,99)
        val v = byteArrayOf(21,22,99)
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8,21,11,22,12),
            YuvPacking.nv21(listOf(plane(y,6,1), plane(u,3,1), plane(v,3,1)),0,0,4,2))
    }

    @Test fun `overlapping interleaved chroma and buffer offsets respect crop`() {
        val y = ByteArray(24) { it.toByte() }
        val chroma = byteArrayOf(21,11,22,12,99,99,23,13,24,14)
        val planes = listOf(plane(y,6,1), plane(chroma,6,2,1), plane(chroma,6,2))
        assertArrayEquals(byteArrayOf(14,15,20,21,24,14), YuvPacking.nv21(planes,2,2,2,2))
        assertArrayEquals(byteArrayOf(0,1,2,3,6,7,8,9,21,11,22,12), YuvPacking.nv21(planes,0,0,4,2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `odd crop is rejected instead of silently misaligning chroma`() {
        val p = plane(ByteArray(16),4,1)
        YuvPacking.nv21(listOf(p,p,p),1,0,2,2)
    }
}
