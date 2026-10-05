package dev.halcamera.camera

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class SnapshotRequestTest {
    private class Capture {
        val results = mutableListOf<Result<String>>()
        val request = SnapshotRequest<String> { results += it }
        var queuedSave: (() -> Unit)? = null
        var files = 0
        var buffersClosed = 0
        fun image(error: Exception? = null) {
            try {
                if (!request.acceptImage()) return
                queuedSave = {
                    request.finishSave(runCatching {
                        if (error != null) throw error
                        files++
                        "photo.jpg"
                    })
                }
            } finally { buffersClosed++ }
        }
        fun timeout() = request.failCapture(IllegalStateException("No photo arrived within 5 seconds."))
        fun stop() = request.failCapture(IllegalStateException("Recording ended: photo capture was interrupted."))
    }

    @Test fun `a save delayed past the camera deadline reports success only after writing`() {
        val capture = Capture()
        capture.image()
        capture.timeout()
        assertTrue(capture.results.isEmpty())
        assertEquals(0, capture.files)
        capture.queuedSave!!()
        assertEquals(listOf("photo.jpg"), capture.results.map { it.getOrThrow() })
        assertEquals(1, capture.files)
    }

    @Test fun `stop during storage preserves the actual storage failure`() {
        val capture = Capture()
        val diskFull = IOException("No space left on device")
        capture.image(diskFull)
        capture.stop()
        capture.timeout()
        assertTrue(capture.results.isEmpty())
        capture.queuedSave!!()
        capture.stop()
        assertEquals(1, capture.results.size)
        assertSame(diskFull, capture.results.single().exceptionOrNull())
        assertEquals(0, capture.files)
        assertEquals(1, capture.buffersClosed)
    }

    @Test fun `stop before image reports one interruption and discards the late image`() {
        val capture = Capture()
        capture.stop()
        capture.timeout()
        capture.image()
        assertEquals(1, capture.results.size)
        assertTrue(capture.results.single().exceptionOrNull()!!.message!!.contains("interrupted"))
        assertNull(capture.queuedSave)
        assertEquals(1, capture.buffersClosed)
    }

    @Test fun `timeout rejects a late image without writing a file`() {
        val capture = Capture()
        capture.timeout()
        capture.image()
        assertEquals(1, capture.results.size)
        assertTrue(capture.results.single().exceptionOrNull()!!.message!!.contains("5 seconds"))
        assertNull(capture.queuedSave)
        assertEquals(1, capture.buffersClosed)
    }

    @Test fun `duplicate image and late camera error cannot replace a save result`() {
        val capture = Capture()
        capture.image()
        capture.image()
        capture.request.failCapture(IOException("late camera error"))
        capture.queuedSave!!()
        capture.request.finishSave(Result.failure(IOException("duplicate completion")))
        assertEquals(1, capture.files)
        assertEquals(2, capture.buffersClosed)
        assertEquals("photo.jpg", capture.results.single().getOrThrow())
    }

    @Test fun `copy or executor rejection completes once and the next request can save`() {
        val results = mutableListOf<Result<String>>()
        val request = SnapshotRequest<String> { results += it }
        assertTrue(request.acceptImage())
        val rejection = java.util.concurrent.RejectedExecutionException("closed")
        request.finishSave(Result.failure(rejection))
        request.failCapture(IOException("late timeout"))
        assertSame(rejection, results.single().exceptionOrNull())
        val next = SnapshotRequest<String> { results += it }
        assertTrue(next.acceptImage())
        next.finishSave(Result.success("next.jpg"))
        assertEquals("next.jpg", results.last().getOrThrow())
    }

    @Test fun `competing image and timeout have exactly one owner`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val answers = AtomicInteger()
                val start = CountDownLatch(1)
                val request = SnapshotRequest<String> { answers.incrementAndGet() }
                val image = pool.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    if (request.acceptImage()) request.finishSave(Result.success("photo.jpg"))
                }
                val timeout = pool.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    request.failCapture(IOException("timeout"))
                }
                start.countDown()
                image.get(5, TimeUnit.SECONDS)
                timeout.get(5, TimeUnit.SECONDS)
                assertEquals(1, answers.get())
            }
        } finally { pool.shutdownNow() }
    }
}
