package dev.halcamera.camera

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Exposure fusion for a bracket (#178), after Mertens, Kautz and Van Reeth (2007): each pixel of the result is a
 * weighted mix of the same pixel in every shot, and a shot weighs more where it is well exposed, saturated and
 * detailed. There is no HDR radiance map and no tone mapping, so the result is an ordinary 8-bit image.
 *
 * The paper blends Laplacian pyramids; this keeps one smooth weight map per shot instead, computed on a small copy
 * of each image and blurred so the mix changes gradually. That avoids seams without holding full-size pyramids of
 * three 12 MP images in memory, at the cost of some local contrast. The shots are not aligned first, so anything
 * that moved between them can ghost.
 *
 * Pure Kotlin on ARGB ints: [BracketFusion] decodes and encodes the JPEGs.
 */
object ExposureFusion {
    /** Mertens' well-exposedness: a Gaussian around mid-grey with this sigma, on 0..1 intensities. */
    const val SIGMA = 0.2
    /** The weight map's longest side; [BracketFusion] decodes the small copies to about this size. */
    const val MAP_SIZE = 512
    private const val EPSILON = 1e-6f

    /** One shot's quality per pixel: contrast × saturation × well-exposedness, each in 0..1. */
    fun quality(argb: IntArray, width: Int, height: Int): FloatArray {
        require(argb.size == width * height)
        val grey = FloatArray(argb.size) { i -> luma(argb[i]) }
        return FloatArray(argb.size) { i ->
            val x = i % width; val y = i / width
            val c = argb[i]
            val r = (c shr 16 and 0xFF) / 255f; val g = (c shr 8 and 0xFF) / 255f; val b = (c and 0xFF) / 255f
            val mean = (r + g + b) / 3f
            val saturation = sqrt(((r - mean) * (r - mean) + (g - mean) * (g - mean) + (b - mean) * (b - mean)) / 3f)
            val exposure = (well(r) * well(g) * well(b)).toFloat()
            fun at(dx: Int, dy: Int) = grey[min(max(y + dy, 0), height - 1) * width + min(max(x + dx, 0), width - 1)]
            val contrast = min(1f, abs(at(-1, 0) + at(1, 0) + at(0, -1) + at(0, 1) - 4 * grey[i]))
            // A flat, grey image would otherwise weigh nothing in every shot; the floor keeps exposure deciding.
            (contrast + 0.05f) * (saturation + 0.05f) * exposure
        }
    }

    /**
     * Turns per-shot quality into blend weights: blurred by [radius] pixels so neighbouring pixels mix alike, then
     * scaled so the shots' weights add up to 1 at every pixel. Where every shot weighs nothing, they share equally.
     */
    fun weights(qualities: List<FloatArray>, width: Int, height: Int, radius: Int): List<FloatArray> {
        require(qualities.isNotEmpty() && qualities.all { it.size == width * height })
        val blurred = qualities.map { boxBlur(boxBlur(it, width, height, radius), width, height, radius) }
        val share = 1f / blurred.size
        for (i in 0 until width * height) {
            val sum = blurred.sumOf { it[i].toDouble() }.toFloat()
            blurred.forEach { it[i] = if (sum > EPSILON) it[i] / sum else share }
        }
        return blurred
    }

    /**
     * Mixes one strip of rows: [strips] holds the same [rows] × [width] pixels from each shot, starting at row [top]
     * of the full image, and the weights come from maps of [mapWidth] × [mapHeight] scaled over the full
     * [fullWidth] × [fullHeight] image. Writes opaque pixels into [out].
     */
    fun blendStrip(strips: List<IntArray>, width: Int, rows: Int, top: Int, fullWidth: Int, fullHeight: Int,
                   weights: List<FloatArray>, mapWidth: Int, mapHeight: Int, out: IntArray) {
        require(strips.size == weights.size && strips.all { it.size >= width * rows } && out.size >= width * rows)
        val sx = mapWidth.toFloat() / fullWidth; val sy = mapHeight.toFloat() / fullHeight
        val w = FloatArray(strips.size)
        for (row in 0 until rows) {
            val my = ((top + row + 0.5f) * sy - 0.5f).coerceIn(0f, mapHeight - 1f)
            val y0 = my.toInt(); val y1 = min(y0 + 1, mapHeight - 1); val fy = my - y0
            for (x in 0 until width) {
                val mx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, mapWidth - 1f)
                val x0 = mx.toInt(); val x1 = min(x0 + 1, mapWidth - 1); val fx = mx - x0
                var total = 0f
                for (k in weights.indices) {
                    val m = weights[k]
                    val top0 = m[y0 * mapWidth + x0] * (1 - fx) + m[y0 * mapWidth + x1] * fx
                    val bottom = m[y1 * mapWidth + x0] * (1 - fx) + m[y1 * mapWidth + x1] * fx
                    w[k] = top0 * (1 - fy) + bottom * fy; total += w[k]
                }
                var r = 0f; var g = 0f; var b = 0f
                val i = row * width + x
                for (k in strips.indices) {
                    val c = strips[k][i]; val wk = if (total > EPSILON) w[k] / total else 1f / strips.size
                    r += (c shr 16 and 0xFF) * wk; g += (c shr 8 and 0xFF) * wk; b += (c and 0xFF) * wk
                }
                out[i] = (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
            }
        }
    }

    /** The blur radius for a weight map of this size: about a sixteenth of its longest side. */
    fun radiusFor(width: Int, height: Int) = max(1, max(width, height) / 16)

    private fun well(v: Float) = exp(-((v - 0.5) * (v - 0.5)) / (2 * SIGMA * SIGMA))
    private fun luma(c: Int) = (0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF)) / 255f
    private fun channel(v: Float) = (v + 0.5f).toInt().coerceIn(0, 255)

    /** A box blur along rows then columns, clamped at the edges. */
    private fun boxBlur(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val tmp = FloatArray(src.size); val out = FloatArray(src.size)
        val span = 2 * radius + 1
        for (y in 0 until height) {
            var sum = 0f
            for (k in -radius..radius) sum += src[y * width + min(max(k, 0), width - 1)]
            for (x in 0 until width) {
                tmp[y * width + x] = sum / span
                sum += src[y * width + min(x + radius + 1, width - 1)] - src[y * width + max(x - radius, 0)]
            }
        }
        for (x in 0 until width) {
            var sum = 0f
            for (k in -radius..radius) sum += tmp[min(max(k, 0), height - 1) * width + x]
            for (y in 0 until height) {
                out[y * width + x] = sum / span
                sum += tmp[min(y + radius + 1, height - 1) * width + x] - tmp[max(y - radius, 0) * width + x]
            }
        }
        return out
    }
}
