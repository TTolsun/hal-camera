package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class PipCaptureBatchTest {
    @Test fun outOfOrderSourcesPreserveMainThenSecondaryAndCompleteOnce() {
        val callbacks = mutableListOf<(Result<String>) -> Unit>()
        var completions = 0
        capturePipBatch(List(2) { { done: (Result<String>) -> Unit -> callbacks += done } },
            discard = { _, _ -> fail("Successful photos must survive") }) {
            assertEquals(listOf("main", "secondary"), it.getOrThrow()); completions++
        }
        callbacks[1](Result.success("secondary")); assertEquals(0, completions)
        callbacks[0](Result.success("main")); callbacks[0](Result.success("duplicate"))
        assertEquals(1, completions)
    }

    @Test fun failedSourceWaitsForLateSavedSourceAndCleanup() {
        val callbacks = mutableListOf<(Result<String>) -> Unit>()
        var cleanup: (() -> Unit)? = null
        var complete = false
        capturePipBatch(List(2) { { done: (Result<String>) -> Unit -> callbacks += done } },
            discard = { saved, done -> assertEquals(listOf("late"), saved); cleanup = done }) { assertTrue(it.isFailure); complete = true }
        callbacks[0](Result.failure(IllegalStateException("main")))
        assertFalse(complete)
        callbacks[1](Result.success("late")); assertFalse(complete)
        cleanup!!.invoke(); assertTrue(complete)
    }

    @Test fun submitFailureStillDrainsOtherSource() {
        var secondary: ((Result<String>) -> Unit)? = null
        var complete = false
        capturePipBatch<String>(listOf({ throw IllegalStateException("submit") }, { secondary = it }),
            discard = { saved, done -> assertEquals(listOf("secondary"), saved); done() }) { assertTrue(it.isFailure); complete = true }
        assertFalse(complete)
        secondary!!.invoke(Result.success("secondary")); assertTrue(complete)
    }
}
