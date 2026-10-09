package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class DualPreviewPlanTest {
    private val sizes = listOf(LiveSize(4000, 3000), LiveSize(1920, 1080), LiveSize(1440, 1080), LiveSize(1280, 720), LiveSize(640, 480))
    private fun lens(id: String, role: LensRole, sizes: List<LiveSize> = this.sizes) = PhysicalLens(id, role, null, sizes)
    private fun camera(vararg physical: PhysicalLens, logical: Boolean = true, facing: Int = CameraLabel.FACING_BACK) =
        LogicalMultiCamera("0", facing, logical, 1, physical.toList())

    @Test fun `wide and tele are paired first, then wide and ultra wide`() {
        val all = camera(lens("2", LensRole.ULTRA_WIDE), lens("3", LensRole.MAIN), lens("4", LensRole.TELE))
        assertEquals("3" to "4", DualPreviewPlanner.defaultPair(all))
        val noTele = camera(lens("2", LensRole.ULTRA_WIDE), lens("3", LensRole.MAIN))
        assertEquals("3" to "2", DualPreviewPlanner.defaultPair(noTele))
    }

    @Test fun `without a recognised wide lens the first two listed are paired`() {
        assertEquals("5" to "6", DualPreviewPlanner.defaultPair(camera(lens("5", LensRole.UNKNOWN), lens("6", LensRole.TELE))))
    }

    @Test fun `only rear logical cameras with two physical lenses are offered`() {
        val rear = camera(lens("2", LensRole.MAIN), lens("3", LensRole.TELE))
        val front = rear.copy(logicalId = "1", facing = CameraLabel.FACING_FRONT)
        val single = rear.copy(logicalId = "4", physical = rear.physical.take(1))
        val plain = rear.copy(logicalId = "5", logicalMultiCamera = false)
        assertEquals(listOf("0"), DualPreviewPlanner.candidates(listOf(front, single, plain, rear)).map { it.logicalId })
    }

    @Test fun `common sizes stay within 1080p and put 4 by 3 first`() {
        val other = listOf(LiveSize(4000, 3000), LiveSize(1920, 1080), LiveSize(1440, 1080), LiveSize(640, 480))
        assertEquals(listOf(LiveSize(1440, 1080), LiveSize(640, 480), LiveSize(1920, 1080)),
            DualPreviewPlanner.commonSizes(sizes, other))
    }

    @Test fun `a pair with no shared size is refused with its reason`() {
        val c = camera(lens("2", LensRole.MAIN, listOf(LiveSize(1280, 720))), lens("3", LensRole.TELE, listOf(LiveSize(640, 480))))
        assertEquals(DualPreviewPlanner.Result.Refused(DualPreviewRefusal.NO_COMMON_SIZE), DualPreviewPlanner.plan(c, "2", "3", 34))
    }

    @Test fun `the same lens twice, an old API, or a non logical camera is refused`() {
        val c = camera(lens("2", LensRole.MAIN), lens("3", LensRole.TELE))
        assertEquals(DualPreviewPlanner.Result.Refused(DualPreviewRefusal.SAME_LENS), DualPreviewPlanner.plan(c, "2", "2", 34))
        assertEquals(DualPreviewPlanner.Result.Refused(DualPreviewRefusal.API_TOO_OLD), DualPreviewPlanner.plan(c, "2", "3", 27))
        assertEquals(DualPreviewPlanner.Result.Refused(DualPreviewRefusal.NOT_LOGICAL),
            DualPreviewPlanner.plan(c.copy(logicalMultiCamera = false), "2", "3", 34))
    }

    @Test fun `a ready plan keeps the chosen order`() {
        val c = camera(lens("2", LensRole.MAIN), lens("3", LensRole.TELE))
        val plan = (DualPreviewPlanner.plan(c, "3", "2", 34) as DualPreviewPlanner.Result.Ready).plan
        assertEquals("3", plan.first.id)
        assertEquals("2", plan.second.id)
        assertEquals(LiveSize(1440, 1080), plan.sizes.first())
    }

    @Test fun `frames and physical metadata are counted apart`() {
        val stats = PhysicalOutputStats("2")
        stats.frame(1000); stats.frame(1100); stats.frame(1200)
        stats.result(5_000L); stats.result(null)
        assertEquals(3L, stats.delivered)
        assertEquals(1L, stats.metadata)
        assertEquals(1L, stats.missing)
        assertEquals(5_000L, stats.lastSensorTimestampNs)
        assertEquals(10.0, stats.fps()!!, 1e-9)
    }

    @Test fun `skew needs both timestamps and the sync type is never upgraded`() {
        assertEquals(-200L, physicalTimestampSkewNs(1_000L, 1_200L))
        assertNull(physicalTimestampSkewNs(1_000L, null))
        assertEquals("APPROXIMATE", DualPreviewPlanner.syncLabel(0))
        assertEquals("Not reported", DualPreviewPlanner.syncLabel(null))
    }
}
