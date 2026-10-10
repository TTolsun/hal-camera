package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class ConcurrentPlanTest {
    private val preview = LiveSize(1280, 720)
    private val jpeg = LiveSize(1920, 1440)
    private fun camera(id: String, facing: Int) = ConcurrentCamera(id, facing, listOf(preview), listOf(jpeg))
    private val rear = camera("0", CameraLabel.FACING_BACK)
    private val front = camera("1", CameraLabel.FACING_FRONT)

    @Test fun `all independent pairs share discovery with advertised pairs first`() {
        val secondRear = camera("2", CameraLabel.FACING_BACK)
        val cameras = listOf(rear, front, secondRear)
        assertTrue(ConcurrentPlanner.plans(29, setOf(setOf("0", "1")), cameras).isEmpty())
        val rearPlans = ConcurrentPlanner.plans(30, setOf(setOf("0", "2")), cameras)
        assertEquals("Rear 0 + Rear 2", rearPlans.first().label)
        assertTrue(rearPlans.first().advertised)
        assertFalse(rearPlans.last().advertised)
        assertEquals(3, ConcurrentPlanner.plans(30, emptySet(), cameras).size)
        val plans = ConcurrentPlanner.plans(30, setOf(setOf("0", "1", "2"), setOf("0", "1")), cameras)
        assertEquals(listOf(listOf("0", "2"), listOf("0", "1"), listOf("2", "1")), plans.map { it.streams.map { s -> s.camera.id } })
    }

    @Test fun `three device lifecycle waits for every device without pair assumptions`() {
        val life = ConcurrentLifecycle(listOf("0", "1", "2"))
        listOf("0", "1", "2").forEach(life::opening)
        life.opened("0"); life.opened("1")
        assertFalse(life.canConfigure)
        life.opened("2"); assertTrue(life.canConfigure)
        life.streaming("0"); life.streaming("1"); assertFalse(life.ready)
        life.streaming("2"); assertTrue(life.ready)
        life.stop(); life.closed("0"); life.closed("1"); assertFalse(life.closed)
        life.closed("2"); assertTrue(life.closed)
    }

    @Test fun `device limit includes every supported subset and prefers largest supported group`() {
        val cameras = listOf(rear, front, camera("2", CameraLabel.FACING_BACK), camera("3", CameraLabel.FACING_FRONT))
        val combinations = setOf(setOf("0", "1", "2", "3"))
        val all = ConcurrentPlanner.plans(30, combinations, cameras, Int.MAX_VALUE)
        assertEquals(11, all.size) // six pairs, four triples, one quadruple
        assertEquals(4, all.first().streams.size)
        assertEquals(10, ConcurrentPlanner.plans(30, combinations, cameras, 3).size)
        assertEquals(6, ConcurrentPlanner.plans(30, combinations, cameras, 2).size)
        assertTrue(ConcurrentPlanner.plans(30, combinations, cameras, 1).isEmpty())
        assertEquals(all.size, all.map { it.streams.map { stream -> stream.camera.id }.toSet() }.distinct().size)
    }

    @Test fun `split fills the stage without gaps in device order`() {
        for (count in 1..6) {
            val split = concurrentFrames(count, 1080, 1703)
            assertEquals(1703, split.sumOf { it.height })
            assertEquals(0, split.first().top)
            split.forEach { assertEquals(1080, it.width); assertEquals(0, it.left) }
            split.zipWithNext().forEach { (a, b) -> assertEquals(a.top + a.height, b.top) }
        }
        assertTrue(concurrentFrames(0,1080,1703).isEmpty())
    }

    @Test fun `preview and jpeg must both have advertised bounded sizes`() {
        val oversized = rear.copy(previews = listOf(LiveSize(3840, 2160)))
        assertTrue(ConcurrentPlanner.plans(30, setOf(setOf("0", "1")), listOf(oversized, front)).isEmpty())
        val noJpeg = front.copy(photos = emptyList())
        assertTrue(ConcurrentPlanner.plans(30, setOf(setOf("0", "1")), listOf(rear, noJpeg)).isEmpty())
        val plan = ConcurrentPlanner.plans(30, setOf(setOf("0", "1")), listOf(rear, front)).single()
        assertEquals(preview, plan.streams[0].preview)
        assertEquals(jpeg, plan.streams[1].photo)
    }

    @Test fun `configuration waits for both opens and readiness waits for both streams`() {
        val life = ConcurrentLifecycle(listOf("0", "1"))
        life.opening("0"); life.opening("1"); life.opened("1")
        assertFalse(life.canConfigure)
        life.opened("0")
        assertTrue(life.canConfigure)
        life.streaming("1")
        assertFalse(life.ready)
        life.streaming("0")
        assertTrue(life.ready)
    }

    @Test fun `failure cannot release outputs while peer open callback is pending`() {
        val life = ConcurrentLifecycle(listOf("0", "1"))
        life.opening("0"); life.opening("1"); life.opened("0")
        life.stop(); life.closed("0")
        assertFalse(life.closed)
        life.opened("1")
        assertEquals(ConcurrentLifecycle.Phase.CLOSING, life.phases["1"])
        assertFalse(life.canConfigure)
        life.streaming("1")
        assertFalse(life.ready)
        life.closed("1")
        assertTrue(life.closed)
    }

    @Test fun `preflight refusal and synchronous open failure can close without callbacks`() {
        val preflight = ConcurrentLifecycle(listOf("0", "1"))
        preflight.stop()
        assertTrue(preflight.closed)
        val open = ConcurrentLifecycle(listOf("0", "1"))
        open.opening("0"); open.closed("0"); open.stop()
        assertTrue(open.closed)
    }

    @Test fun `partial success survives cancellation and duplicate callbacks`() {
        val group = ConcurrentCapture<String>("group", 42, listOf("0", "1"))
        group.settle("0", Result.success("photo0"))
        assertFalse(group.complete)
        group.cancel("Camera 1 disconnected")
        group.settle("0", Result.failure(IllegalStateException("late")))
        group.settle("1", Result.success("late photo"))
        assertTrue(group.complete)
        assertEquals("photo0", group.outcomes["0"]!!.getOrThrow())
        assertEquals("Camera 1 disconnected", group.outcomes["1"]!!.exceptionOrNull()!!.message)
        assertEquals(42L, group.requestedAtNs)
    }

    @Test fun `configuration failure closes both devices before releasing outputs`() {
        val life = ConcurrentLifecycle(listOf("0", "1"))
        life.opening("0"); life.opening("1"); life.opened("0"); life.opened("1")
        life.streaming("0")
        life.stop() // camera 1 onConfigureFailed
        assertFalse(life.ready)
        life.closed("1")
        assertFalse(life.closed)
        life.closed("0")
        assertTrue(life.closed)
    }

    @Test fun `recreated screen waits for old session and cancelled waiters never open`() {
        val lease = ConcurrentLease()
        val old = Any(); val cancelled = Any(); val next = Any()
        val opened = mutableListOf<String>()
        lease.acquire(old) { opened += "old" }
        lease.acquire(cancelled) { opened += "cancelled" }
        lease.acquire(next) { opened += "next" }
        assertEquals(listOf("old"), opened)
        lease.release(cancelled)
        lease.release(old)
        assertEquals(listOf("old", "next"), opened)
        lease.release(old) // a duplicate old callback cannot release the new session
        val third = Any()
        lease.acquire(third) { opened += "third" }
        assertEquals(listOf("old", "next"), opened)
        lease.release(next)
        assertEquals(listOf("old", "next", "third"), opened)
    }
}
