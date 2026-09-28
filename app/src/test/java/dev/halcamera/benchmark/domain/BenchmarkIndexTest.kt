package dev.halcamera.benchmark.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BenchmarkIndexTest {
    private val contract = "camera2-standard-v1|metrics-0.3|nearest_rank|elapsedRealtimeNanos"

    @Test fun keyIsContractAndEndpoint() {
        assertEquals("$contract|0", BenchmarkIndex.key(contract, "0"))
    }

    @Test fun addAndRemoveBaselines() {
        val i = BenchmarkIndex().withAdded(contract, "0", "a").withAdded(contract, "0", "b")
        assertEquals(listOf("a", "b"), i.baselines(contract, "0"))
        assertEquals(emptyList<String>(), i.baselines(contract, "1"))
        assertEquals(emptyList<String>(), i.baselines("other-profile|metrics-0.3|nearest_rank|elapsedRealtimeNanos", "0"))
        assertEquals(listOf("b"), i.withRemoved(contract, "0", "a").baselines(contract, "0"))
        // Removing the last member removes the key, so an empty set and no set are the same index.
        assertEquals(BenchmarkIndex(), i.withRemoved(contract, "0", "a").withRemoved(contract, "0", "b"))
        assertSame(i, i.withAdded(contract, "0", "a"))
    }

    @Test fun retainingDropsDeletedRunsAndEmptySets() {
        val i = BenchmarkIndex().withAdded(contract, "0", "a").withAdded(contract, "1", "b").withAdded(contract, "1", "c")
        val kept = i.retaining(setOf("b"))
        assertEquals(mapOf("$contract|1" to listOf("b")), kept.baselines)
        assertEquals(setOf("a", "b", "c"), i.allRunIds)
    }

    @Test fun jsonRoundTrip() {
        val i = BenchmarkIndex().withAdded(contract, "0", "a").withAdded(contract, "0", "b")
        assertEquals(i, BenchmarkIndex.fromJsonMap(i.toJsonMap()))
        assertEquals(2, i.toJsonMap()["schema_version"])
        assertEquals(BenchmarkIndex(), BenchmarkIndex.fromJsonMap(null))
    }

    @Test fun aSchemaOneFileReadsAsSetsOfOne() {
        // Until #165 a key held a single run id.
        val old = mapOf("schema_version" to 1, "baselines" to mapOf("$contract|0" to "a", "$contract|1" to 7))
        assertEquals(BenchmarkIndex(mapOf("$contract|0" to listOf("a"))), BenchmarkIndex.fromJsonMap(old))
    }
}
