package dev.halcamera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exposure bracketing (#178): which EVs a bracket asks for, and when the control is available at all. */
class BracketPlanTest {
    private val rear = LiveControlSupport(evRange = -6..6, evStep = 1.0 / 3, aeLock = true, afLock = true,
        flash = true, autoFlash = true, alwaysFlash = true)

    @Test fun `requested EV first, then two EV darker and brighter in the camera's steps`() {
        assertEquals(listOf(0, -6, 6), BracketPlan.evIndices(0, -6..6, 1.0 / 3))
        assertEquals(listOf(1, -3, 5), BracketPlan.evIndices(1, -4..8, 0.5))
    }

    @Test fun `a range that cuts a step short keeps three shots at its end`() {
        assertEquals(listOf(4, -2, 6), BracketPlan.evIndices(4, -6..6, 1.0 / 3))
    }

    @Test fun `request id tags are ASCII EV values`() {
        assertEquals("ev+0.0", BracketPlan.tag(0, 1.0 / 3))
        assertEquals("ev-2.0", BracketPlan.tag(-6, 1.0 / 3))
        assertEquals("ev+2.0", BracketPlan.tag(4, 0.5))
    }

    @Test fun `bracketing needs photo mode, an EV range and auto exposure`() {
        val on = LiveControls(bracket = true)
        assertTrue(on.coerce(rear, video = false).bracket)
        assertFalse(on.coerce(rear, video = true).bracket)
        assertFalse(on.coerce(LiveControlSupport.NONE, video = false).bracket)
        assertFalse(on.copy(manual = ManualControls(exposure = ManualExposure(iso = 100, timeNs = 10_000_000L))).coerce(rear, false).bracket)
    }

    @Test fun `the compact summary reports saves and the failed shot`() {
        val shots = listOf(BurstShot(0, "a", 0, Result.success(1)), BurstShot(1, "b", 600, Result.failure(IllegalStateException("Capture timed out"))),
            BurstShot(2, "c", 1200, Result.success(1)))
        assertEquals("AEB · 2/3 saved · #2 failed: Capture timed out",
            LiveBurst.describeBracket(BurstSummary("x", 3, 0, shots, null)))
    }
}
