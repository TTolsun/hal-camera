package dev.cameradoctor.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchmarkIndexTest {
    private val contract = "camera2-standard-v1|metrics-0.3|nearest_rank|elapsedRealtimeNanos"

    @Test fun keyIsContractAndEndpoint() {
        assertEquals("$contract|0", BenchmarkIndex.key(contract, "0"))
    }

    @Test fun setAndClearBaseline() {
        val i = BenchmarkIndex().withBaseline(contract, "0", "20260909-095100")
        assertEquals("20260909-095100", i.baseline(contract, "0"))
        assertNull(i.baseline(contract, "1"))
        assertNull(i.baseline("other-profile|metrics-0.3|nearest_rank|elapsedRealtimeNanos", "0"))
        assertNull(i.withBaseline(contract, "0", null).baseline(contract, "0"))
    }

    @Test fun retainingDropsDeletedRuns() {
        val i = BenchmarkIndex().withBaseline(contract, "0", "a").withBaseline(contract, "1", "b")
        val kept = i.retaining(setOf("b"))
        assertNull(kept.baseline(contract, "0"))
        assertEquals("b", kept.baseline(contract, "1"))
    }

    @Test fun jsonRoundTrip() {
        val i = BenchmarkIndex().withBaseline(contract, "0", "a")
        assertEquals(i, BenchmarkIndex.fromJsonMap(i.toJsonMap()))
        assertEquals(BenchmarkIndex(), BenchmarkIndex.fromJsonMap(null))
    }
}
