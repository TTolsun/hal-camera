package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class MediaTransactionTest {
    @Test fun writeFailurePublishesNothingAndDeletesOwnedEntries() {
        val published = mutableListOf<String>(); val deleted = mutableListOf<String>()
        val result = runCatching { MediaTransaction<String>({ published += it }, { deleted += it }).run {
            own("jpeg"); own("dng"); error("disk full")
        } }
        assertTrue(result.isFailure)
        assertTrue(published.isEmpty())
        assertEquals(listOf("jpeg", "dng"), deleted)
    }
    @Test fun publishFailureRollsBackEvenWhenOneDeleteFails() {
        val deleted = mutableListOf<String>()
        val result = runCatching { MediaTransaction<String>({ if (it == "dng") error("publish failed") }, {
            deleted += it; if (it == "jpeg") error("delete failed")
        }).run { own("jpeg"); own("dng") } }
        assertEquals("publish failed", result.exceptionOrNull()?.message)
        assertEquals(listOf("jpeg", "dng"), deleted)
    }
    @Test fun successfulWritePublishesOnlyAfterAllWritesFinish() {
        val actions = mutableListOf<String>()
        val value = MediaTransaction<String>({ actions += "publish $it" }, { error("Unexpected deletion") }).run {
            own("jpeg"); actions += "jpeg written"
            own("json"); actions += "json written"
            42
        }
        assertEquals(42, value)
        assertEquals(listOf("jpeg written", "json written", "publish jpeg", "publish json"), actions)
    }
}
