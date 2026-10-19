package dev.halcamera.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A display copy only; never serialized into benchmark JSON or incident ZIPs. */
data class DepthFrame(val width: Int, val height: Int, val pixels: IntArray, val centerMm: Int?, val validPercent: Int) {
    companion object {
        const val MAX_MM = 8191
        private const val BLACK = -0x1000000

        fun confidence(sample: Int): Float {
            val code = (sample ushr 13) and 7
            return if (code == 0) 1f else (code - 1) / 7f
        }

        /** Absolute reads honor row/pixel strides, crop offsets and buffer position, including the final row's padding. */
        fun render(source: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
                   left: Int = 0, top: Int = 0, maxEdge: Int = 640): DepthFrame {
            require(width > 0 && height > 0 && left >= 0 && top >= 0 && maxEdge > 0)
            require(pixelStride == 2 && rowStride.toLong() >= (left.toLong() + width) * pixelStride)
            val end = (top.toLong() + height - 1) * rowStride + (left.toLong() + width - 1) * pixelStride + 2
            require(end <= source.remaining().toLong()) { "DEPTH16 plane is truncated" }
            val buffer = source.slice().order(ByteOrder.LITTLE_ENDIAN)
            val step = ((maxOf(width, height) - 1) / maxEdge) + 1
            val outWidth = (width - 1) / step + 1
            val outHeight = (height - 1) / step + 1
            val pixels = IntArray(outWidth * outHeight)
            fun sample(x: Int, y: Int) = buffer.getShort((top + y) * rowStride + (left + x) * pixelStride).toInt() and 0xffff
            fun valid(value: Int) = (value and 0x1fff) > 0 && confidence(value) >= 0.5f
            var count = 0
            for (y in 0 until outHeight) for (x in 0 until outWidth) {
                val value = sample(x * step, y * step)
                val mm = value and 0x1fff
                pixels[y * outWidth + x] = if (!valid(value)) BLACK else {
                    count++
                    val gray = 32 + (MAX_MM - mm) * 223 / MAX_MM
                    BLACK or (gray shl 16) or (gray shl 8) or gray
                }
            }
            val center = sample(width / 2, height / 2).takeIf(::valid)?.and(0x1fff)
            return DepthFrame(outWidth, outHeight, pixels, center, (count.toLong() * 100 / pixels.size).toInt())
        }
    }
}
