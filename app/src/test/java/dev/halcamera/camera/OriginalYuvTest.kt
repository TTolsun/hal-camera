package dev.halcamera.camera

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class OriginalYuvTest {
    @Test fun `sidecar alone reconstructs cropped samples from overlapping padded planes`() {
        val y = ByteBuffer.wrap(ByteArray(24) { it.toByte() })
        val chroma = byteArrayOf(21, 11, 22, 12, 99, 99, 23, 13, 24, 14)
        val u = ByteBuffer.wrap(chroma).apply { position(1) }
        val v = ByteBuffer.wrap(chroma)
        val original = OriginalYuv.copy(listOf(YuvPacking.Plane(y, 6, 1), YuvPacking.Plane(u, 6, 2),
            YuvPacking.Plane(v, 6, 2)), 4, 4, 2, 2, 2, 2)
        @Suppress("UNCHECKED_CAST")
        val planes = original.metadata["planes"] as List<Map<String, Any>>
        fun samples(plane: Map<String, Any>): List<Byte> = buildList {
            for (row in 0 until plane["height"] as Int) for (column in 0 until plane["width"] as Int) {
                add(original.bytes[(plane["offset"] as Int) + row * (plane["rowStride"] as Int) + column * (plane["pixelStride"] as Int)])
            }
        }
        assertEquals(listOf<Byte>(14, 15, 20, 21), samples(planes[0]))
        assertEquals(listOf<Byte>(14), samples(planes[1]))
        assertEquals(listOf<Byte>(24), samples(planes[2]))
        assertEquals(6, original.metadata["byteLength"])
        assertEquals(1, u.position())
        chroma.fill(0) // The Image can be closed/reused without changing saved pixels.
        assertArrayEquals(byteArrayOf(14, 15, 20, 21, 24, 14), original.bytes)
    }

    @Test fun `odd oversized and overflow dimensions fail before allocating`() {
        assertFalse(OriginalYuv.supports(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(OriginalYuv.supports(6000, 4000))
        assertFalse(OriginalYuv.supports(3, 2))
        assertTrue(OriginalYuv.supports(3840, 2160))
        assertFalse(OriginalYuv.supports(0, 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `crop outside source dimensions is rejected`() {
        OriginalYuv.copy(emptyList(), 4, 4, 4, 0, 2, 2)
    }

    @Test fun `original export requires Camera2 support YUV and a bounded size`() {
        val size = LiveSize(640, 480)
        val huge = LiveSize(6000, 4000)
        val support = LiveStreamSupport(listOf(size), listOf(size, huge), listOf(size), emptyList(), emptyList(), originalYuv = true)
        val defaults = support.defaults()
        assertFalse(defaults.originalYuv)
        assertNull(support.rejection(defaults.copy(originalYuv = true)))
        assertNotNull(support.copy(originalYuv = false).rejection(defaults.copy(originalYuv = true)))
        assertNotNull(support.rejection(defaults.copy(yuv = null, originalYuv = true)))
        assertNotNull(support.rejection(defaults.copy(yuv = huge, originalYuv = true)))
    }
}
