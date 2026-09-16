package dev.halcamera.benchmark.domain

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class RepeatStatisticsTest {
    @Test fun `exact separated five runs have two extreme partitions of 252`() {
        val a = (1..5).map(Int::toDouble)
        val b = (6..10).map(Int::toDouble)
        assertEquals(2.0 / 252, RepeatStatistics.pValue(a, b), 1e-12)
        assertEquals(RepeatStatistics.pValue(a, b), RepeatStatistics.pValue(b, a), 1e-12)
    }

    @Test fun `summaries describe runs and sample standard deviation`() {
        assertNull(RepeatStatistics.summary(emptyList()))
        assertNull(RepeatStatistics.summary(listOf(1.0))!!.sd)
        val s = RepeatStatistics.summary(listOf(4.0, 1.0, 3.0, 2.0))!!
        assertEquals(4, s.n)
        assertEquals(2.5, s.mean, 0.0)
        assertEquals(2.5, s.median, 0.0)
        assertEquals(sqrt(5.0 / 3), s.sd!!, 1e-12)
    }

    @Test fun `constant equal groups do not differ and zero variance is allowed`() {
        assertEquals(1.0, RepeatStatistics.pValue(List(5) { 0.0 }, List(5) { 0.0 }), 0.0)
        assertEquals(2.0 / 252, RepeatStatistics.pValue(List(5) { 0.0 }, List(5) { 1.0 }), 1e-12)
    }

    @Test fun `Monte Carlo is deterministic and never reports zero`() {
        val a = (1..20).map(Int::toDouble)
        val b = (101..120).map(Int::toDouble)
        val p = RepeatStatistics.pValue(a, b)
        assertEquals(1.0 / (RepeatStatistics.RESAMPLES + 1), p, 0.0)
        assertEquals(p, RepeatStatistics.pValue(a.reversed(), b.reversed()), 0.0)
        assertEquals(1.0, a.first(), 0.0)
    }

    @Test fun `Bonferroni adjusts for the tested family`() {
        assertEquals(0.03, RepeatStatistics.adjustedP(0.01, 3), 1e-12)
        assertEquals(1.0, RepeatStatistics.adjustedP(0.8, 3), 0.0)
    }

    @Test fun `invalid values and undersized samples fail explicitly`() {
        for (v in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 1e13)) {
            assertThrows(IllegalArgumentException::class.java) { RepeatStatistics.summary(listOf(v)) }
        }
        assertThrows(IllegalArgumentException::class.java) { RepeatStatistics.pValue(List(4) { 1.0 }, List(5) { 2.0 }) }
    }
}
