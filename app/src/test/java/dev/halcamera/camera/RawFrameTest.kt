package dev.halcamera.camera

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class RawFrameTest {
    @Test fun `padded rows are packed to two bytes per pixel without the padding`() {
        // 2x3 pixels, row stride 6: 4 sample bytes then 2 padding bytes (99) per row.
        val plane = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 99, 99, 5, 6, 7, 8, 99, 99, 9, 10, 11, 12))
        val frame = RawFrame.copy(plane, 6, 2, 2, 3)
        val packed = ByteArray(frame.pixels.remaining()).also { frame.pixels.duplicate().get(it) }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), packed)
        assertEquals(12, frame.byteLength)
        assertEquals(0, plane.position()) // The Image's own buffer is left as it was.
    }

    @Test fun `copy survives the Image buffer being reused`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val frame = RawFrame.copy(ByteBuffer.wrap(bytes), 4, 2, 2, 1)
        bytes.fill(0)
        assertEquals(1.toByte(), frame.pixels.get(0))
        assertEquals(4.toByte(), frame.pixels.get(3))
    }

    @Test fun `the last row needs no trailing padding`() {
        val frame = RawFrame.copy(ByteBuffer.wrap(byteArrayOf(1, 2, 0, 0, 3, 4)), 4, 2, 1, 2)
        assertEquals(4, frame.pixels.remaining())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `truncated plane is rejected`() {
        RawFrame.copy(ByteBuffer.wrap(ByteArray(10)), 6, 2, 2, 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non RAW16 pixel stride is rejected`() {
        RawFrame.copy(ByteBuffer.wrap(ByteArray(16)), 4, 1, 4, 4)
    }

    @Test fun `RAW requires a RAW-capable camera and a listed size`() {
        val size = LiveSize(640, 480)
        val sensor = LiveSize(4000, 3000)
        val none = LiveStreamSupport(listOf(size), listOf(size), listOf(size), emptyList(), emptyList())
        val settings = none.defaults().copy(raw = sensor)
        assertNull(none.defaults().raw)
        assertEquals("RAW/DNG requires Camera2 and a RAW-capable camera.", none.rejection(settings))
        val capable = none.copy(raw = listOf(sensor))
        assertNull(capable.rejection(settings))
        assertEquals("Unsupported RAW size.", capable.rejection(settings.copy(raw = size)))
        assertEquals("4000x3000", settings.metadata()["raw"])
        assertTrue(settings.copy(yuv = null, jpeg = null).canCapture)
    }
}
