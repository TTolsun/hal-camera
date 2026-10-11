package dev.halcamera.camera

/** A disposable draft. Only advertised combinations can reach Apply. No Android dependencies. */
internal class SplitSelection(
    private val plans: List<ConcurrentPlan>,
    initialIds: List<String>,
    initialLayout: SplitLayout,
) {
    val counts = plans.filter { it.advertised }.map { it.streams.size }.distinct().sorted()
    var ids = initialIds.toList()
        private set
    val count get() = ids.size
    val candidates get() = plans.filter { it.streams.size == count }
    val layouts get() = SplitLayout.entries.filter { it != SplitLayout.GRID || count > 2 }
    var layout = initialLayout
        private set

    init {
        require(counts.isNotEmpty())
        if (!valid) ids = plans.first { it.advertised }.streams.map { it.camera.id }
        normalizeLayout()
    }

    val valid get() = ids.size >= 2 && ids.distinct().size == ids.size && plans.any {
        it.advertised && it.streams.map { stream -> stream.camera.id }.toSet() == ids.toSet()
    }

    fun selectCount(next: Int) {
        require(next in counts)
        if (next != count) ids = plans.first { it.advertised && it.streams.size == next }.streams.map { it.camera.id }
        normalizeLayout()
    }

    fun selectPlan(plan: ConcurrentPlan) {
        require(plan in candidates && plan.advertised)
        ids = plan.streams.map { it.camera.id }
    }

    fun selectLayout(next: SplitLayout) {
        require(next in layouts)
        layout = next
    }

    fun assign(slot: Int, id: String): Boolean {
        require(slot in ids.indices && id in ids)
        if (ids[slot] == id) return false
        val peer = ids.indexOf(id)
        ids = ids.toMutableList().also { next ->
            next[peer] = next[slot]
            next[slot] = id
        }
        return true
    }

    private fun normalizeLayout() {
        if (layout !in layouts) layout = SplitLayout.SIDE_BY_SIDE
    }
}
