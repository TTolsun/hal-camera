package dev.halcamera.camera

import kotlin.math.roundToInt

/** Refines coarse blur weights against the source image before either sampling or compositing blur. */
internal object BokehMask {
    fun refine(pixels: IntArray, width: Int, height: Int, coarse: FloatArray): FloatArray {
        // Local linear guided filter (He et al., ECCV 2010). Use full-resolution luminance so
        // structures narrower than one model pixel can influence the compositing boundary.
        val radius = (maxOf(width, height) * .035f).roundToInt().coerceAtLeast(1)
        val guide = FloatArray(pixels.size) { i ->
            val p = pixels[i]
            (.299f * ((p ushr 16) and 255) + .587f * ((p ushr 8) and 255) + .114f * (p and 255)) / 255f
        }
        val meanGuide = mean(guide, width, height, radius)
        val meanMask = mean(coarse, width, height, radius)
        val variance = mean(FloatArray(pixels.size) { guide[it] * guide[it] }, width, height, radius)
        val covariance = mean(FloatArray(pixels.size) { guide[it] * coarse[it] }, width, height, radius)
        for (i in pixels.indices) {
            val a = (covariance[i] - meanGuide[i] * meanMask[i]) /
                ((variance[i] - meanGuide[i] * meanGuide[i]).coerceAtLeast(0f) + .001f)
            covariance[i] = a
            variance[i] = meanMask[i] - a * meanGuide[i]
        }
        val meanA = mean(covariance, width, height, radius)
        val meanB = mean(variance, width, height, radius)
        return FloatArray(pixels.size) { i ->
            // Never add blur to a plane the depth model already protects. Constant-background
            // regions remain unchanged; refinement only recovers detail near a depth boundary.
            minOf(coarse[i], (meanA[i] * guide[i] + meanB[i]).coerceIn(0f, 1f))
        }
    }

    /** One box mean with clipped borders, O(pixels) work independent of radius. */
    private fun mean(input: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val horizontal = FloatArray(input.size)
        for (y in 0 until height) {
            var sum = 0.0
            for (x in 0..minOf(radius, width - 1)) sum += input[y * width + x]
            for (x in 0 until width) {
                horizontal[y * width + x] = (sum / (minOf(width - 1, x + radius) - maxOf(0, x - radius) + 1)).toFloat()
                if (x - radius >= 0) sum -= input[y * width + x - radius]
                if (x + radius + 1 < width) sum += input[y * width + x + radius + 1]
            }
        }
        val output = FloatArray(input.size)
        for (x in 0 until width) {
            var sum = 0.0
            for (y in 0..minOf(radius, height - 1)) sum += horizontal[y * width + x]
            for (y in 0 until height) {
                output[y * width + x] = (sum / (minOf(height - 1, y + radius) - maxOf(0, y - radius) + 1)).toFloat()
                if (y - radius >= 0) sum -= horizontal[(y - radius) * width + x]
                if (y + radius + 1 < height) sum += horizontal[(y + radius + 1) * width + x]
            }
        }
        return output
    }
}
