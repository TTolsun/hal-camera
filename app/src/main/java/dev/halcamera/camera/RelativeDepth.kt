package dev.halcamera.camera

import kotlin.math.roundToInt

/** Relative inverse depth, not metres: 1 is nearer, 0 is farther. */
class RelativeDepth(val width: Int, val height: Int, val values: FloatArray) {
    init { require(width > 0 && height > 0 && values.size == width * height && values.all { it.isFinite() && it in 0f..1f }) }

    fun at(x: Float, y: Float): Float {
        val px = x.coerceIn(0f, 1f) * (width - 1)
        val py = y.coerceIn(0f, 1f) * (height - 1)
        val x0 = px.toInt(); val y0 = py.toInt()
        val x1 = (x0 + 1).coerceAtMost(width - 1); val y1 = (y0 + 1).coerceAtMost(height - 1)
        val a = px - x0; val b = py - y0
        return (values[y0 * width + x0] * (1 - a) + values[y0 * width + x1] * a) * (1 - b) +
            (values[y1 * width + x0] * (1 - a) + values[y1 * width + x1] * a) * b
    }

    fun focus(x: Float, y: Float): Float {
        val samples = FloatArray(9) { i -> at(x + (i % 3 - 1).toFloat() / width, y + (i / 3 - 1).toFloat() / height) }
        samples.sort()
        return samples[4]
    }

    companion object {
        fun normalize(width: Int, height: Int, raw: FloatArray): RelativeDepth {
            require(raw.size == width * height && raw.all(Float::isFinite)) { "Depth model returned invalid values" }
            val sorted = raw.sorted()
            val low = sorted[(sorted.size * .02f).toInt()]
            val high = sorted[((sorted.size - 1) * .98f).toInt()]
            require(high - low > 1e-5f) { "Cannot distinguish depth in this scene. Try a textured subject and background." }
            return RelativeDepth(width, height, FloatArray(raw.size) { ((raw[it] - low) / (high - low)).coerceIn(0f, 1f) })
        }
    }
}

/** CPU background-only blur. Foreground colour is excluded from the blur kernel to limit halos. */
object DepthBokeh {
    fun amount(depth: Float, focus: Float): Float {
        val t = ((focus - depth - .04f) / .25f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun render(pixels: IntArray, width: Int, height: Int, depth: RelativeDepth,
               focusX: Float, focusY: Float, strength: Int): IntArray {
        require(width > 0 && height > 0 && pixels.size == width * height && strength in 0..100)
        require(focusX.isFinite() && focusY.isFinite())
        if (strength == 0) return pixels.copyOf()
        val focus = depth.focus(focusX, focusY)
        val weights = FloatArray(pixels.size) { i -> amount(depth.at((i % width).toFloat() / (width - 1).coerceAtLeast(1),
            (i / width).toFloat() / (height - 1).coerceAtLeast(1)), focus) }
        val radius = (maxOf(width, height) * .018f * strength / 100).roundToInt().coerceAtLeast(1)
        val blurredWeight = blur(weights, width, height, radius)
        val channels = arrayOf(16, 8, 0).map { shift ->
            blur(FloatArray(pixels.size) { ((pixels[it] ushr shift) and 255) * weights[it] }, width, height, radius)
        }
        return IntArray(pixels.size) { i ->
            val blend = weights[i]
            var result = -0x1000000
            for (c in 0..2) {
                val shift = (2 - c) * 8
                val original = (pixels[i] ushr shift) and 255
                val softened = if (blurredWeight[i] > .001f) channels[c][i] / blurredWeight[i] else original.toFloat()
                result = result or ((original * (1 - blend) + softened * blend).roundToInt().coerceIn(0, 255) shl shift)
            }
            result
        }
    }

    // Three separable box passes approximate a Gaussian, with bounded O(width * height) work.
    private fun blur(input: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        var source = input
        repeat(3) {
            val horizontal = FloatArray(input.size)
            for (y in 0 until height) {
                var sum = 0f
                for (x in 0..minOf(radius, width - 1)) sum += source[y * width + x]
                for (x in 0 until width) {
                    horizontal[y * width + x] = sum / (minOf(width - 1, x + radius) - maxOf(0, x - radius) + 1)
                    if (x - radius >= 0) sum -= source[y * width + x - radius]
                    if (x + radius + 1 < width) sum += source[y * width + x + radius + 1]
                }
            }
            val vertical = FloatArray(input.size)
            for (x in 0 until width) {
                var sum = 0f
                for (y in 0..minOf(radius, height - 1)) sum += horizontal[y * width + x]
                for (y in 0 until height) {
                    vertical[y * width + x] = sum / (minOf(height - 1, y + radius) - maxOf(0, y - radius) + 1)
                    if (y - radius >= 0) sum -= horizontal[(y - radius) * width + x]
                    if (y + radius + 1 < height) sum += horizontal[(y + radius + 1) * width + x]
                }
            }
            source = vertical
        }
        return source
    }
}
