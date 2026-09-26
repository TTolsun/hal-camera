package dev.halcamera.benchmark.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Settings limit: newest runs stay, baselines never leave, unlimited deletes nothing. */
class RunRetentionTest {

    private val ids = listOf("05", "04", "03", "02", "01") // newest first, as the store lists them

    @Test fun underTheLimitNothingIsDeleted() {
        assertTrue(RunRetention.toDelete(ids, emptySet(), 5).isEmpty())
        assertTrue(RunRetention.toDelete(ids, emptySet(), 10).isEmpty())
    }

    @Test fun unlimitedKeepsEverything() {
        assertTrue(RunRetention.toDelete(ids, emptySet(), RunRetention.UNLIMITED).isEmpty())
    }

    @Test fun overTheLimitTheOldestGoFirst() {
        assertEquals(listOf("01", "02"), RunRetention.toDelete(ids, emptySet(), 3))
    }

    @Test fun aBaselineIsNeverDeleted() {
        // "01" is a baseline: deleting it would silently change what every later run is measured against.
        assertEquals(listOf("02"), RunRetention.toDelete(ids, setOf("01"), 3))
    }

    @Test fun everySliderStopIsTheSameDistanceApart() {
        // A slider promises that equal travel means equal change; the numeric stops must honour that.
        val numeric = RunRetention.OPTIONS.filterNot { it == RunRetention.UNLIMITED }
        assertEquals(RunRetention.STEP, numeric.first())
        assertEquals(RunRetention.MAX_LIMIT, numeric.last())
        assertTrue(numeric.zipWithNext().all { (a, b) -> b - a == RunRetention.STEP })
        // Unlimited is not a number, so it is the one stop past the end rather than a point on the scale.
        assertEquals(RunRetention.UNLIMITED, RunRetention.OPTIONS.last())
        assertEquals("무제한", RunRetention.tickLabel(RunRetention.UNLIMITED))
        assertEquals("10개", RunRetention.valueLabel(10))
        assertEquals("무제한", RunRetention.valueLabel(RunRetention.UNLIMITED))
        assertEquals("현재 11개 · 1개 삭제", RunRetention.impactLine(11, 1))
        assertEquals("현재 8개", RunRetention.impactLine(8, 0))
    }

    @Test fun aProtectedRunStillCountsTowardTheLimit() {
        // The cap is how much history the developer wants around, not how many deletable files exist.
        assertEquals(listOf("01", "02", "03"), RunRetention.toDelete(ids, setOf("05"), 2))
    }
}
