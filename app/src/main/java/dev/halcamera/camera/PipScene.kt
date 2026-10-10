package dev.halcamera.camera

/** Coordinates belong to one logical device, never to the Multi stage. */
data class PipRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    fun moved(x: Float, y: Float) = copy(x = x.coerceIn(0f, 1f - width), y = y.coerceIn(0f, 1f - height))
    fun contains(x: Float, y: Float) = x >= this.x && x <= this.x + width && y >= this.y && y <= this.y + height
}

object PipScene {
    /** The producer matrix already includes camera buffer rotation/mirroring; do not rotate it twice. */
    fun sourceAspect(width: Int, height: Int, xx: Float, xy: Float, yx: Float, yy: Float): Float {
        val horizontal = kotlin.math.hypot(xx * width, xy * height)
        val vertical = kotlin.math.hypot(yx * width, yy * height)
        return if (vertical > 0f) horizontal / vertical else 1f
    }

    fun initial(count: Int): List<PipRect> = List(count) { index ->
        val height = minOf(.32f, .92f / count.coerceAtLeast(1))
        PipRect(.64f, .04f + index * height, .32f, height)
    }

    fun selected(supported: Set<String>, requested: List<String>): List<String> {
        require(requested.distinct().size == requested.size && requested.all { it in supported }) { "Unsupported physical camera" }
        return requested
    }
}
