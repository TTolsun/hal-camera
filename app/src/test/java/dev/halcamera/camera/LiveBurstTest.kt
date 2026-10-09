package dev.halcamera.camera

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class LiveBurstTest {
    private fun shot(index: Int, error: String? = null) = BurstShot(index, "burst-x-0${index + 1}", index * 500L,
        if (error == null) Result.success("ok") else Result.failure(IllegalStateException(error)))

    @Test fun `letting go shows only what was saved, with no target count`() {
        val summary = BurstSummary("x", BurstRun.MAX_COUNT, 0, listOf(shot(0), shot(1), shot(2)), LiveBurst.RELEASED)
        assertEquals("Burst · 3 saved", LiveBurst.describe(summary))
    }

    @Test fun `failed shots and an early end are named`() {
        val summary = BurstSummary("x", BurstRun.MAX_COUNT, 0, listOf(shot(0), shot(1, "Capture timed out")), "Camera closed")
        assertEquals("Burst · 1 saved · #2 failed: Capture timed out (Camera closed)", LiveBurst.describe(summary))
    }

    @Test fun `a hold that reaches the limit says so`() {
        val summary = BurstSummary("x", 2, 0, listOf(shot(0), shot(1)), null)
        assertEquals("Burst · 2 saved (limit 2)", LiveBurst.describe(summary))
    }
}
