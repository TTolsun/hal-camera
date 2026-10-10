package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class PipMediaTest {
    private class Driver {
        lateinit var capture: (Result<String>) -> Unit
        lateinit var start: (Result<Unit>) -> Unit
        lateinit var stop: (Result<String>) -> Unit
        val events = mutableListOf<String>()
        val media = PipMedia<String, String>({ it() }, { true },
            { _, done -> capture = done }, { _, done -> start = done },
            { done -> events += "stop"; stop = done }, { events += "recording:$it" }, { events += it })
        fun record() = media.start(false, { events += "started" }, { events += if (it.isSuccess) "saved" else "failed" })
    }
    @Test fun closeDuringStartWaitsForStopAndCompletesOnce() {
        val d = Driver(); d.record()
        d.media.close { d.events += "closed" }
        assertTrue(d.events.isEmpty())
        d.start(Result.success(Unit))
        assertEquals(listOf("stop"), d.events)
        d.stop(Result.success("uri"))
        assertEquals(listOf("stop", "recording:false", "saved", "closed"), d.events)
    }
    @Test fun closeDuringStopDoesNotIssueAnotherStop() {
        val d = Driver(); d.record(); d.start(Result.success(Unit))
        d.media.stop(); d.media.close { d.events += "closed" }
        assertEquals(1, d.events.count { it == "stop" })
        d.stop(Result.success("uri"))
        assertEquals(1, d.events.count { it == "saved" })
        assertEquals("closed", d.events.last())
    }
    @Test fun bothCloseWaitersRunAfterRecordingCompletes() {
        val d = Driver(); d.record(); d.start(Result.success(Unit))
        d.media.close { d.events += "close1" }; d.media.close { d.events += "close2" }
        assertFalse(d.events.contains("close1"))
        d.stop(Result.failure(IllegalStateException("encoder")))
        assertEquals(listOf("failed", "close1", "close2"), d.events.takeLast(3))
    }
    @Test fun startFailureCompletesRequestAndCloseWithoutStopping() {
        val d = Driver(); d.record(); d.media.close { d.events += "closed" }
        d.start(Result.failure(IllegalStateException("encoder")))
        assertEquals(listOf("failed", "closed"), d.events)
    }
    @Test fun closeWaitsForPhotoAndRejectsNewWork() {
        val d = Driver()
        d.media.capture("photo") { d.events += "photo" }
        d.media.close { d.events += "closed" }
        d.media.capture("late") { assertTrue(it.isFailure) }
        assertTrue(d.events.isEmpty())
        d.capture(Result.success("uri"))
        assertEquals(listOf("photo", "closed"), d.events)
    }
    @Test fun failedPhotoReleasesBusyStateForNextCapture() {
        val d = Driver(); d.media.capture(null) { assertTrue(it.isFailure) }
        d.capture(Result.failure(IllegalStateException("storage")))
        assertFalse(d.media.busy)
        d.media.capture(null) { d.events += "photo" }; d.capture(Result.success("uri"))
        assertEquals(listOf("photo"), d.events)
    }
}
