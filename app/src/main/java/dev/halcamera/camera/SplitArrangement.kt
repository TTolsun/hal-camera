package dev.halcamera.camera

/** Split preview placement. [order] lists panel indexes slot by slot. Preview geometry only; no Android dependencies. */
internal data class SplitArrangement(val layout: SplitLayout, val order: List<Int>) {
    init { require(order.sorted() == order.indices.toList()) }
    val count get() = order.size

    /** One frame per panel index, not per slot. */
    fun frames(width: Int, height: Int): List<ConcurrentFrame> {
        val slots = concurrentFrames(count, width, height, layout)
        return if (slots.isEmpty()) slots else List(count) { panel -> slots[order.indexOf(panel)] }
    }

    fun normalized() = if (layout == SplitLayout.GRID && count < 3) copy(layout = SplitLayout.SIDE_BY_SIDE) else this

    /**
     * Windows-style snap for a panel released at ([x], [y]) inside a [width] x [height] preview area.
     * Edges split into halves with the panel on that side, corners pick a grid cell (three or more panels),
     * and the centre swaps places with the panel underneath while keeping the layout.
     */
    fun snap(panel: Int, x: Float, y: Float, width: Int, height: Int): SplitArrangement {
        require(panel in order.indices && width > 0 && height > 0)
        val u = (x / width).coerceIn(0f, 1f)
        val v = (y / height).coerceIn(0f, 1f)
        val left = u < EDGE; val right = u > 1 - EDGE
        val top = v < EDGE; val bottom = v > 1 - EDGE
        if (count > 2 && (left || right) && (top || bottom)) {
            val columns = splitGridColumns(count)
            val rows = (count + columns - 1) / columns
            val slot = when {
                top && left -> 0
                top -> columns - 1
                left -> (rows - 1) * columns
                else -> count - 1
            }
            return place(SplitLayout.GRID, panel, slot)
        }
        // Two panels have no corner cell, so the nearer edge wins.
        val sideways = (left || right) && (!(top || bottom) || minOf(u, 1 - u) <= minOf(v, 1 - v))
        if (sideways) return place(SplitLayout.SIDE_BY_SIDE, panel, if (left) 0 else count - 1)
        if (top || bottom) return place(SplitLayout.STACKED, panel, if (top) 0 else count - 1)
        val target = frames(width, height).indexOfFirst { x >= it.left && x < it.left + it.width && y >= it.top && y < it.top + it.height }
        if (target < 0 || target == panel) return this
        return copy(order = order.map { when (it) { panel -> target; target -> panel; else -> it } })
    }

    /**
     * Snap judged by where the dragged panel's centre has moved, not by the finger. The grab handle sits on the
     * panel's top edge, so a finger-based judgement would read every pick-up as a top-edge drop.
     */
    fun drag(panel: Int, dx: Float, dy: Float, width: Int, height: Int): SplitArrangement {
        val frame = frames(width, height).getOrNull(panel) ?: return this
        return snap(panel, frame.left + frame.width / 2f + dx, frame.top + frame.height / 2f + dy, width, height)
    }

    private fun place(next: SplitLayout, panel: Int, slot: Int) =
        SplitArrangement(next, (order - panel).toMutableList().apply { add(slot, panel) })

    companion object {
        /** Fraction of the preview area that counts as an edge. */
        const val EDGE = .22f
        fun identity(count: Int, layout: SplitLayout = SplitLayout.SIDE_BY_SIDE) =
            SplitArrangement(layout, List(count) { it }).normalized()
    }
}
