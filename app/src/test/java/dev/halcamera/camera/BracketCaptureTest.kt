package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class BracketCaptureTest {
    private val support = LiveControlSupport(-6..6, 1.0 / 3, true, true, false, false, false)
    private class Clock {
        var now = 0L
        val jobs = mutableListOf<Pair<Long, () -> Unit>>()
        fun schedule(delay: Long, block: () -> Unit) { jobs += now + delay to block }
        fun tick() {
            val next = jobs.minBy { it.first }; jobs.remove(next)
            now = next.first; next.second()
        }
        fun drain() { while (jobs.isNotEmpty()) tick() }
    }

    @Test fun `three shots settle at their requested EV and restore the original controls`() {
        val clock = Clock()
        val base = LiveControls(evIndex = 1, afLock = true)
        val controls = mutableListOf<LiveControls>()
        val shots = mutableListOf<Pair<Long, String>>()
        var outcome: BurstSummary<String>? = null
        val run = BracketCapture.create("aeb", base, support, { clock.now }, clock::schedule, { true },
            { id, done -> shots += clock.now to id; done(Result.success(id)) }, controls::add,
            progress = {}, finished = { outcome = it })
        run.start(); clock.drain()
        assertEquals(listOf(500L, 1000L, 1500L), shots.map { it.first })
        assertEquals(BracketPlan.evIndices(base.evIndex, support.evRange!!, support.evStep), controls.dropLast(1).map { it.evIndex })
        assertEquals(base, controls.last())
        assertEquals(3, outcome!!.saved)
        assertTrue(shots.all { it.second.startsWith("bracket-aeb-") })
    }

    @Test fun `cancellation keeps the accepted shot restores controls and takes no further photo`() {
        val clock = Clock()
        val base = LiveControls(evIndex = 0)
        val controls = mutableListOf<LiveControls>()
        var outcome: BurstSummary<String>? = null
        val run = BracketCapture.create("cancel", base, support, { clock.now }, clock::schedule, { true },
            { id, done -> done(Result.success(id)) }, controls::add, progress = {}, finished = { outcome = it })
        run.start(); clock.tick(); run.cancel("Stopped"); clock.drain()
        assertEquals(1, outcome!!.saved)
        assertEquals(2, outcome!!.notTaken)
        assertEquals(base, controls.last())
    }

    @Test fun `restoration never targets a replaced camera and completion is delivered once`() {
        val clock = Clock()
        val controls = mutableListOf<LiveControls>()
        var completed = 0
        val run = BracketCapture.create<String>("closed", LiveControls(), support, { clock.now }, clock::schedule, { true },
            { _, done -> done(Result.failure(IllegalStateException("closed"))) }, controls::add,
            canRestore = { false }, progress = {}, finished = { completed++ })
        run.start(); clock.drain(); run.cancel("Late cancel")
        assertEquals(2, controls.size)
        assertEquals(1, completed)
    }
}
