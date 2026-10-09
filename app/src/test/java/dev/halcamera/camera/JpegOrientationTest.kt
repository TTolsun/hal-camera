package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class JpegOrientationTest {
    /** A JPEG skeleton: SOI, a JFIF APP0, the start of scan and EOI. Enough for marker walking. */
    private val plain = byteArrayOf(0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xE0.toByte(), 0x00, 0x07, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0x00,
        0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0xFF.toByte(), 0xD9.toByte())

    @Test fun `no EXIF reads as upright`() {
        assertEquals(1, JpegOrientation.read(plain))
        assertEquals(1, JpegOrientation.read(byteArrayOf(1, 2, 3)))
    }

    @Test fun `written orientation reads back`() {
        for (o in 2..8) {
            val tagged = JpegOrientation.write(plain, o)
            assertEquals(o, JpegOrientation.read(tagged))
            assertEquals(0xFF, tagged[0].toInt() and 0xFF); assertEquals(0xD8, tagged[1].toInt() and 0xFF)
            assertEquals(0xE1, tagged[3].toInt() and 0xFF)
            // The original segments follow untouched.
            assertArrayEquals(plain.copyOfRange(2, plain.size), tagged.copyOfRange(tagged.size - plain.size + 2, tagged.size))
        }
    }

    @Test fun `upright needs no segment`() {
        assertSame(plain, JpegOrientation.write(plain, 1))
    }

    @Test fun `little endian EXIF from a camera is read`() {
        // Intel byte order, IFD0 with two entries; orientation 6 is the second.
        val tiff = byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x02, 0x00,
            0x0F, 0x01, 0x02, 0x00, 0x01, 0x00, 0x00, 0x00, 0x41, 0x00, 0x00, 0x00,
            0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00)
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val length = payload.size + 2
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) +
            payload + plain.copyOfRange(2, plain.size)
        assertEquals(6, JpegOrientation.read(jpeg))
    }
}
