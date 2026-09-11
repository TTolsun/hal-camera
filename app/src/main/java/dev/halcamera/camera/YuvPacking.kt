package dev.halcamera.camera

import java.nio.ByteBuffer

/** Copies a cropped YUV_420_888 image without assuming packed or non-overlapping planes. */
object YuvPacking {
    data class Plane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

    fun nv21(planes: List<Plane>, left: Int, top: Int, width: Int, height: Int): ByteArray {
        require(planes.size == 3 && width > 0 && height > 0)
        require(listOf(left, top, width, height).all { it % 2 == 0 }) { "YUV crop must be even" }
        val out = ByteArray(width * height * 3 / 2)
        planes.forEachIndexed { index, plane ->
            val shift = if (index == 0) 0 else 1
            val rows = height shr shift
            val columns = width shr shift
            val base = plane.buffer.position() + (top shr shift) * plane.rowStride + (left shr shift) * plane.pixelStride
            for (row in 0 until rows) for (column in 0 until columns) {
                val target = if (index == 0) row * width + column
                    else width * height + row * width + column * 2 + if (index == 1) 1 else 0
                out[target] = plane.buffer.get(base + row * plane.rowStride + column * plane.pixelStride)
            }
        }
        return out
    }
}
