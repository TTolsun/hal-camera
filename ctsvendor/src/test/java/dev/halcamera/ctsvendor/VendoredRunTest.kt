package dev.halcamera.ctsvendor

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class VendoredRunTest {
    private val clock = AtomicLong(1000)

    private class Recorder : VendoredRun.Listener {
        val started = ArrayList<String>()
        val failures = ArrayList<String>()
        @Volatile var result: VendoredResult? = null
        override fun onStarted(displayName: String) { started += displayName }
        override fun onFailure(displayName: String, message: String) { failures += "$displayName: $message" }
        override fun onFinished(result: VendoredResult) { this.result = result }
    }

    private fun fixture(method: String) = VendoredTest(DriverFixture::class.java.name, method)

    private fun run(test: VendoredTest): Pair<Recorder, VendoredResult> {
        DriverFixture.armed = true
        val recorder = Recorder()
        VendoredRun(test, recorder) { clock.addAndGet(500) }.run()
        return recorder to recorder.result!!
    }

    @After fun disarm() { DriverFixture.armed = false; DriverFixture.release = CountDownLatch(1) }

    @Test
    fun `runs only the named method across its parameterized rows and reports PASS with the elapsed time`() {
        val (recorder, result) = run(fixture("testPasses"))
        assertEquals(listOf("testPasses[0]"), recorder.started)
        assertEquals(VendoredVerdict.PASS, result.verdict)
        assertEquals(500L, result.durationMs)
        assertFalse(result.cancelled)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun `a failing assertion is FAIL and carries the CTS message`() {
        val (recorder, result) = run(fixture("testFails"))
        assertEquals(VendoredVerdict.FAIL, result.verdict)
        assertEquals(listOf("AssertionError: Camera 0: fixture failure"), result.failures)
        assertEquals(listOf("testFails[0]: AssertionError: Camera 0: fixture failure"), recorder.failures)
    }

    @Test
    fun `an assumption failure is SKIP`() {
        val (_, result) = run(fixture("testSkips"))
        assertEquals(VendoredVerdict.SKIP, result.verdict)
    }

    @Test
    fun `a class that cannot load is a FAIL result, not a crash, and onFinished still arrives`() {
        val (_, result) = run(VendoredTest("dev.halcamera.ctsvendor.NoSuchClass", "testAnything"))
        assertEquals(VendoredVerdict.FAIL, result.verdict)
        assertEquals(1, result.failures.size)
        assertTrue(result.failures[0], result.failures[0].startsWith("ClassNotFoundException"))
    }

    @Test
    fun `stop marks the result cancelled and raises the host flag until the next run clears it`() {
        DriverFixture.armed = true
        val recorder = Recorder()
        val run = VendoredRun(fixture("testWaits"), recorder) { clock.addAndGet(500) }
        val worker = Thread { run.run() }
        worker.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (recorder.started.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(listOf("testWaits[0]"), recorder.started)

        run.stop()
        assertTrue(VendoredCts.stopRequested)
        DriverFixture.release.countDown()
        worker.join(5000)
        val result = recorder.result!!
        assertTrue(result.cancelled)
        assertEquals(VendoredVerdict.PASS, result.verdict)

        run(fixture("testPasses"))
        assertFalse(VendoredCts.stopRequested)
    }
}
