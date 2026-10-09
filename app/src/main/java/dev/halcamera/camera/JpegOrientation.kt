package dev.halcamera.camera

/**
 * The EXIF orientation of a JPEG, read and written without a library (#178). The camera's JPEG is stored sideways
 * with an orientation tag; the fused bracket image is drawn from those stored pixels, so it needs the same tag to
 * show upright. Only the orientation (tag 0x0112 in IFD0) is handled; everything else in EXIF is left out.
 */
object JpegOrientation {
    /** The orientation value 1..8, or 1 (upright) when there is no EXIF or no orientation tag. */
    fun read(jpeg: ByteArray): Int {
        if (jpeg.size < 4 || u8(jpeg, 0) != 0xFF || u8(jpeg, 1) != 0xD8) return 1
        var p = 2
        while (p + 4 <= jpeg.size && u8(jpeg, p) == 0xFF) {
            val marker = u8(jpeg, p + 1)
            if (marker == 0xDA || marker == 0xD9) break
            val length = u16(jpeg, p + 2, true)
            if (marker == 0xE1 && isExif(jpeg, p + 4)) return orientation(jpeg, p + 10, p + 2 + length)
            p += 2 + length
        }
        return 1
    }

    /** [jpeg] with an APP1 EXIF segment that carries only [orientation], inserted right after the start marker. */
    fun write(jpeg: ByteArray, orientation: Int): ByteArray {
        require(jpeg.size >= 2 && u8(jpeg, 0) == 0xFF && u8(jpeg, 1) == 0xD8) { "Not a JPEG" }
        if (orientation == 1) return jpeg
        require(orientation in 2..8)
        // Big-endian TIFF: header, IFD0 with one SHORT entry, no next IFD.
        val tiff = byteArrayOf(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
            0x00, 0x01, 0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, orientation.toByte(), 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00)
        val payload = "Exif".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0) + tiff
        val length = payload.size + 2
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun isExif(b: ByteArray, at: Int) = at + 6 <= b.size &&
        b[at] == 'E'.code.toByte() && b[at + 1] == 'x'.code.toByte() && b[at + 2] == 'i'.code.toByte() &&
        b[at + 3] == 'f'.code.toByte() && b[at + 4] == 0.toByte() && b[at + 5] == 0.toByte()

    private fun orientation(b: ByteArray, tiff: Int, end: Int): Int {
        if (tiff + 8 > end || end > b.size) return 1
        val big = when { u8(b, tiff) == 0x4D && u8(b, tiff + 1) == 0x4D -> true
            u8(b, tiff) == 0x49 && u8(b, tiff + 1) == 0x49 -> false
            else -> return 1 }
        val ifd = tiff + u32(b, tiff + 4, big)
        if (ifd + 2 > end) return 1
        val count = u16(b, ifd, big)
        for (i in 0 until count) {
            val entry = ifd + 2 + i * 12
            if (entry + 12 > end) return 1
            if (u16(b, entry, big) == 0x0112) return u16(b, entry + 8, big).takeIf { it in 1..8 } ?: 1
        }
        return 1
    }

    private fun u8(b: ByteArray, at: Int) = b[at].toInt() and 0xFF
    private fun u16(b: ByteArray, at: Int, big: Boolean) =
        if (big) (u8(b, at) shl 8) or u8(b, at + 1) else (u8(b, at + 1) shl 8) or u8(b, at)
    private fun u32(b: ByteArray, at: Int, big: Boolean) =
        if (big) (u16(b, at, true) shl 16) or u16(b, at + 2, true) else (u16(b, at + 2, false) shl 16) or u16(b, at, false)
}
