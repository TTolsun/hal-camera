package dev.halcamera.camera

/** The same EV sequence, settling and restoration for UI and CLI AEB. No Android dependencies. */
object BracketCapture {
    const val SETTLE_MS = 500L

    fun <T> create(
        id: String, base: LiveControls, support: LiveControlSupport,
        clock: () -> Long, schedule: (Long, () -> Unit) -> Unit,
        ready: () -> Boolean, shoot: (String, (Result<T>) -> Unit) -> Unit,
        setControls: (LiveControls) -> Unit, canRestore: () -> Boolean = { true },
        progress: (BurstRun<T>) -> Unit, finished: (BurstSummary<T>) -> Unit
    ): BurstRun<T> {
        val evs = BracketPlan.evIndices(base.evIndex, requireNotNull(support.evRange), support.evStep)
        return BurstRun(id, evs.size, 0L, clock, schedule, ready, shoot, progress,
            finished = { summary ->
                if (canRestore()) runCatching { setControls(base) }
                finished(summary)
            },
            name = { "bracket-$id-${it + 1}-${BracketPlan.tag(evs[it], support.evStep)}" },
            prepare = { index, go ->
                setControls(base.copy(evIndex = evs[index]))
                schedule(SETTLE_MS, go)
            })
    }
}
