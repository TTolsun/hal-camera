package dev.halcamera.camera

import java.nio.ByteBuffer

/**
 * A RAW_SENSOR frame copied out of its Image as tightly packed 16-bit samples, so the Image goes back to its reader
 * at once and DngCreator.writeByteBuffer can write it later on the media thread. The copy is a direct buffer: a
 * full-sensor frame is tens of megabytes, which belongs outside the Java heap.
 */
internal class RawFrame private constructor(val pixels: ByteBuffer, val width: Int, val height: Int) {
    val byteLength get() = width * height * BYTES_PER_PIXEL

    companion object {
        const val BYTES_PER_PIXEL = 2

        /** Copies [width]×[height] samples from a plane that may pad its rows; RAW_SENSOR has a pixel stride of 2. */
        fun copy(plane: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int,
                 allocate: (Int) -> ByteBuffer = ByteBuffer::allocateDirect): RawFrame {
            require(width > 0 && height > 0) { "RAW frame has no pixels" }
            require(pixelStride == BYTES_PER_PIXEL) { "RAW_SENSOR pixel stride $pixelStride is not 2" }
            val row = width * BYTES_PER_PIXEL
            require(rowStride >= row) { "RAW_SENSOR row stride $rowStride is shorter than a row" }
            require(width.toLong() * height * BYTES_PER_PIXEL <= Int.MAX_VALUE) { "RAW frame is too large" }
            val source = plane.duplicate()
            val start = source.position()
            require(source.remaining().toLong() >= rowStride.toLong() * (height - 1) + row) { "RAW_SENSOR plane is truncated" }
            val target = allocate(row * height)
            for (y in 0 until height) {
                source.limit(start + y * rowStride + row).position(start + y * rowStride)
                target.put(source)
            }
            target.flip()
            return RawFrame(target, width, height)
        }
    }
}
