package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class BurstRunTest {
    /** A single-threaded fake: scheduled blocks run in time order when [advance] moves the clock. */
    private class Fake {
        var now = 0L
        private val queue = mutableListOf<Pair<Long, () -> Unit>>()
        var busy = false
        val requests = mutableListOf<String>()
        val pending = mutableListOf<(Result<String>) -> Unit>()
        var summary: BurstSummary<String>? = null
        var finishedCalls = 0

        fun run(count: Int, intervalMs: Long = 0) = BurstRun<String>("b1", count, intervalMs, { now },
            { delay, block -> queue += (now + delay) to block }, { !busy },
            { id, done -> requests += id; pending += done },
            {}, { summary = it; finishedCalls++ })

        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val due = queue.filter { it.first <= until }.minByOrNull { it.first } ?: break
                queue.remove(due); now = due.first; due.second()
            }
            now = until
        }
        fun answer(result: Result<String>) = pending.removeAt(0)(result)
    }

    @Test fun `shots run one at a time and each waits for the previous save`() {
        val f = Fake(); val run = f.run(3); run.start()
        assertEquals(listOf("burst-b1-01"), f.requests)
        f.answer(Result.success("a"))
        assertEquals(listOf("burst-b1-01", "burst-b1-02"), f.requests)
        f.answer(Result.success("b")); f.answer(Result.success("c"))
        val s = f.summary!!
        assertEquals(3, s.saved); assertEquals(0, s.notTaken); assertNull(s.stopReason)
        assertEquals("Burst 3/3 saved", s.describe())
        assertFalse(run.running)
    }

    @Test fun `interval delays the next start but never shortens a slow save`() {
        val f = Fake(); f.run(3, intervalMs = 500).start()
        f.advance(100); f.answer(Result.success("a"))
        assertEquals(1, f.requests.size)
        f.advance(399); assertEquals(1, f.requests.size)
        f.advance(1); assertEquals(2, f.requests.size)
        f.advance(900); f.answer(Result.success("b"))
        assertEquals(3, f.requests.size)
        f.answer(Result.success("c"))
        assertEquals(listOf(500L, 900L), f.summary!!.startGapsMs)
    }

    @Test fun `a busy engine is waited for instead of counted as a failure`() {
        val f = Fake(); f.run(2).start()
        f.busy = true; f.answer(Result.success("a"))
        assertEquals(1, f.requests.size)
        f.advance(60); f.busy = false; f.advance(20)
        assertEquals(2, f.requests.size)
    }

    @Test fun `an engine that stays busy ends the burst with the rest not taken`() {
        val f = Fake(); f.run(3).start()
        f.busy = true; f.answer(Result.success("a"))
        f.advance(BurstRun.READY_TIMEOUT_MS + 100)
        val s = f.summary!!
        assertEquals(1, s.saved); assertEquals(2, s.notTaken); assertEquals("Camera stayed busy", s.stopReason)
    }

    @Test fun `one failure is reported and the burst goes on`() {
        val f = Fake(); f.run(3).start()
        f.answer(Result.success("a")); f.answer(Result.failure(IllegalStateException("Capture timed out"))); f.answer(Result.success("c"))
        val s = f.summary!!
        assertEquals(2, s.saved); assertEquals(listOf(1), s.failedShots.map { it.index })
        assertEquals("Burst 2/3 saved · #2 failed: Capture timed out", s.describe())
    }

    @Test fun `two failures in a row stop the burst`() {
        val f = Fake(); f.run(5).start()
        f.answer(Result.failure(IllegalStateException("x"))); f.answer(Result.failure(IllegalStateException("y")))
        val s = f.summary!!
        assertEquals(2, f.requests.size); assertEquals(3, s.notTaken); assertEquals("Two shots in a row failed", s.stopReason)
    }

    @Test fun `cancel waits for the shot in flight and reports once`() {
        val f = Fake(); val run = f.run(4); run.start()
        f.answer(Result.success("a"))
        run.cancel("Stopped"); run.cancel("Camera closed")
        assertNull(f.summary)
        f.answer(Result.success("b"))
        val s = f.summary!!
        assertEquals(2, s.saved); assertEquals(2, s.notTaken); assertEquals("Stopped", s.stopReason)
        assertEquals(2, f.requests.size); assertEquals(1, f.finishedCalls)
        assertEquals("Burst 2/4 saved · 2 not taken (Stopped)", s.describe())
    }

    @Test fun `cancel during an interval wait finishes at once`() {
        val f = Fake(); val run = f.run(3, intervalMs = 1000); run.start()
        f.answer(Result.success("a"))
        run.cancel("Camera closed")
        assertEquals(1, f.summary!!.saved)
        f.advance(2000)
        assertEquals(1, f.requests.size); assertEquals(1, f.finishedCalls)
    }

    @Test fun `a late duplicate answer from the engine is ignored`() {
        val f = Fake(); f.run(2).start()
        val first = f.pending[0]
        first(Result.success("a")); first(Result.failure(IllegalStateException("late")))
        assertEquals(2, f.requests.size)
        assertNull(f.summary)
        f.pending[1](Result.success("b"))
        assertEquals(2, f.summary!!.saved); assertTrue(f.summary!!.failedShots.isEmpty())
    }

    @Test fun `prepare runs before each shot and a cancel during it takes no shot`() {
        val f = Fake()
        val prepared = mutableListOf<Int>()
        var go: (() -> Unit)? = null
        val run = BurstRun<String>("b1", 3, 0, { f.now }, { _, block -> block() }, { true },
            { id, done -> f.requests += id; f.pending += done }, {}, { f.summary = it },
            name = { "bracket-$it" }, prepare = { index, next -> prepared += index; go = next })
        run.start()
        assertEquals(listOf(0), prepared); assertTrue(f.requests.isEmpty())
        go!!(); assertEquals(listOf("bracket-0"), f.requests)
        f.answer(Result.success("a"))
        assertEquals(listOf(0, 1), prepared)
        run.cancel("Camera closed")
        assertNull(f.summary)
        go!!()
        assertEquals(1, f.requests.size); assertEquals(1, f.summary!!.saved); assertEquals("Camera closed", f.summary!!.stopReason)
    }

    @Test(expected = IllegalArgumentException::class) fun `count above the limit is refused`() {
        Fake().run(BurstRun.MAX_COUNT + 1)
    }
}
