package dev.halcamera.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class DepthFrameTest {
    private fun plane(vararg samples: Int) = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        samples.forEach { putShort(it.toShort()) }; flip()
    }

    @Test fun `confidence zero code means full confidence and code one means invalid`() {
        assertEquals(1f, DepthFrame.confidence(1200), 0f)
        assertEquals(0f, DepthFrame.confidence((1 shl 13) or 1200), 0f)
        assertEquals(1f / 7, DepthFrame.confidence((2 shl 13) or 1200), 0.00001f)
        assertEquals(6f / 7, DepthFrame.confidence((7 shl 13) or 1200), 0.00001f)
    }

    @Test fun `zero range and low confidence are black but far valid depth is visible`() {
        val frame = DepthFrame.render(plane(0, 1, 8191, (1 shl 13) or 1000, (4 shl 13) or 1000, (5 shl 13) or 1000), 6, 1, 12, 2)
        assertEquals(-0x1000000, frame.pixels[0])
        assertTrue((frame.pixels[1] and 255) > (frame.pixels[2] and 255))
        assertEquals(32, frame.pixels[2] and 255)
        assertEquals(-0x1000000, frame.pixels[3])
        assertEquals(-0x1000000, frame.pixels[4])
        assertNotEquals(-0x1000000, frame.pixels[5])
        assertEquals(50, frame.validPercent)
        assertNull(frame.centerMm)
    }

    @Test fun `crop and padded rows honor buffer position without requiring last row padding`() {
        val buffer = plane(9999, 0, 0, 0, 0, 0, 10, 20, 0, 0, 30, 40)
        buffer.position(2)
        val frame = DepthFrame.render(buffer, 2, 2, 8, 2, left = 1, top = 1)
        assertEquals(40, frame.centerMm)
        assertEquals(100, frame.validPercent)
        assertEquals(2, buffer.position())
        assertTrue((frame.pixels[0] and 255) >= (frame.pixels[3] and 255))
    }

    @Test fun `large planes are sampled to a bounded display frame`() {
        val buffer = ByteBuffer.allocate(1280 * 720 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(1280 * 720) { buffer.putShort(2000) }; buffer.flip()
        val frame = DepthFrame.render(buffer, 1280, 720, 2560, 2)
        assertEquals(640, frame.width)
        assertEquals(360, frame.height)
        assertEquals(2000, frame.centerMm)
        assertEquals(100, frame.validPercent)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `truncated planes are rejected`() { DepthFrame.render(plane(1000, 2000, 3000), 2, 2, 4, 2) }

    @Test(expected = IllegalArgumentException::class)
    fun `unexpected pixel stride is rejected`() { DepthFrame.render(plane(1000, 2000), 2, 1, 4, 1) }
}
