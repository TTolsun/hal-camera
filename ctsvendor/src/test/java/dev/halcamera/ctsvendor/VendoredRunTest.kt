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

    /** The fixture opens no camera, so a run that should read as PASS pretends camera 0 was opened. */
    private fun run(test: VendoredTest, opened: Set<String> = setOf("0"), log: List<String> = emptyList()): Pair<Recorder, VendoredResult> {
        DriverFixture.armed = true
        val recorder = Recorder()
        VendoredRun(test, recorder, { clock.addAndGet(500) }, { opened }, { log }).run()
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
    fun `a pass that opened no camera is SKIP and carries the test's own skip lines as reasons`() {
        val log = listOf(
            "Testing HEIC_ULTRAHDR for Camera 0",
            "Camera 0 does not support HEIC_ULTRAHDR, skipping",
            "Camera 1 does not support HEIC_ULTRAHDR, skipping",
            "Camera 0 does not support HEIC_ULTRAHDR, skipping"
        )
        val (_, result) = run(fixture("testPasses"), opened = emptySet(), log = log)
        assertEquals(VendoredVerdict.SKIP, result.verdict)
        assertEquals(listOf("Camera 0 does not support HEIC_ULTRAHDR, skipping", "Camera 1 does not support HEIC_ULTRAHDR, skipping"), result.skipReasons)
        assertFalse(result.cancelled)

        val (_, checked) = run(fixture("testPasses"), opened = setOf("1"), log = log)
        assertEquals(VendoredVerdict.PASS, checked.verdict)
        assertTrue(checked.skipReasons.isEmpty())

        val (_, failed) = run(fixture("testFails"), opened = emptySet(), log = log)
        assertEquals(VendoredVerdict.FAIL, failed.verdict)
        assertTrue(failed.skipReasons.isEmpty())
    }

    @Test
    fun `skip log parsing keeps only lines since the run began and only skip messages`() {
        val lines = listOf(
            " 1789654000.100  1234  5678 I StillCaptureTest: Camera 0 does not support HEIC, skipping",
            " 1789654100.000  1234  5678 I StillCaptureTest: Testing HEIC exif for Camera 0",
            " 1789654100.200  1234  5678 I StillCaptureTest: Camera 0 does not support HEIC, skipping",
            " 1789654100.300  1234  5678 V BurstCaptureTest: Device doesn't support STILL_CAPTURE bokeh. Skip the test",
            "--------- beginning of main",
            " 1789654100.400  1234  5678 I StillCaptureTest: AE/AWB lock is not supported in camera 2. Skip the test."
        )
        val since = 1789654100_000L
        val messages = SkipLog.parse(lines, since)
        assertEquals(4, messages.size)
        assertEquals(
            listOf("Camera 0 does not support HEIC, skipping", "Device doesn't support STILL_CAPTURE bokeh. Skip the test", "AE/AWB lock is not supported in camera 2. Skip the test"),
            SkipLog.reasons(messages)
        )
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
        val run = VendoredRun(fixture("testWaits"), recorder, { clock.addAndGet(500) }, { setOf("0") }, { emptyList() })
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
