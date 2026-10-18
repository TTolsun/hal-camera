package dev.halcamera.camera

import kotlin.math.abs

/** A short directional drag selects a snap; the panel itself still follows the finger one-to-one. */
internal class SplitDragGesture(
    private val start: SplitArrangement,
    private val panel: Int,
    private val width: Int,
    private val height: Int,
    activationDistance: Float,
) {
    init { require(panel in start.order.indices && width > 0 && height > 0 && activationDistance > 0) }

    // Small preview areas must not demand a drag longer than the area itself.
    private val threshold = minOf(activationDistance, minOf(width, height) * .18f)
    private var horizontal: Boolean? = null
    private var corner = false
    private var armed = false

    fun update(dx: Float, dy: Float): SplitArrangement {
        if (!dx.isFinite() || !dy.isFinite()) return start
        val x = abs(dx); val y = abs(dy)
        val distance = maxOf(x, y)
        // Separate enter/exit distances prevent the preview flickering under a resting finger.
        if (distance < threshold * .7f) {
            armed = false; horizontal = null; corner = false
            return start
        }
        if (!armed && distance < threshold) return start
        armed = true
        horizontal = when {
            horizontal == null -> x > y
            x > y * 1.2f -> true
            y > x * 1.2f -> false
            else -> horizontal
        }
        corner = start.count > 2 && minOf(x, y) >= threshold * (if (corner) .7f else 1f) &&
            minOf(x, y) >= distance * (if (corner) .6f else .8f)
        val u = if (corner || horizontal == true) { if (dx < 0) 0f else 1f } else .5f
        val v = if (corner || horizontal == false) { if (dy < 0) 0f else 1f } else .5f
        return start.snap(panel, u * width, v * height, width, height)
    }
}
