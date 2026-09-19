package dev.halcamera.ctsvendor

import android.os.SystemClock
import org.junit.runner.Description
import org.junit.runner.Request
import org.junit.runner.manipulation.Filter
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import org.junit.runner.notification.RunNotifier
import org.junit.runner.notification.StoppedByUserException

enum class VendoredVerdict { PASS, FAIL, SKIP }

/** What one vendored test method reported. It iterates every camera itself, so there is one verdict per run. */
data class VendoredResult(
    val test: VendoredTest,
    val verdict: VendoredVerdict,
    val durationMs: Long,
    /** One entry per JUnit failure: the message, then the first frames inside the CTS code. */
    val failures: List<String>,
    val cancelled: Boolean,
    /**
     * Why a SKIP checked nothing: the test's own "… skipping" log lines, one per distinct message. Empty for
     * PASS and FAIL, and for a SKIP whose reasons the log did not hold.
     */
    val skipReasons: List<String> = emptyList()
)

/**
 * Drives one vendored test method with JUnit's own runner, on the calling thread, and reports through
 * [Listener]. Parameterized creates one instance per adoptShellPerm row; the patched CameraParameterizedTestCase
 * declares only the `false` row, so the method runs once and checks every non-system camera, as under cts-tradefed.
 *
 * [clock] is elapsed milliseconds; the default is the device clock and tests inject their own.
 */
class VendoredRun(
    private val test: VendoredTest,
    private val listener: Listener,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    /** The cameras the test opened; the default reads the hook the patched openDevice feeds. */
    private val openedCameras: () -> Set<String> = { VendoredCts.openedCameras() },
    /** The test's log lines written since the run began; the default reads this process's logcat. */
    private val skipLog: (sinceEpochMs: Long) -> List<String> = { since -> SkipLog.read(test.simpleClass, since) }
) {
    interface Listener {
        fun onStarted(displayName: String)
        fun onFailure(displayName: String, message: String)
        fun onFinished(result: VendoredResult)
    }

    private val notifier = RunNotifier()
    @Volatile private var stopRequested = false

    /** Blocks until the run ends. [Listener.onFinished] is delivered on every path, including a crash of the runner itself. */
    fun run() {
        VendoredCts.clearStop()
        VendoredCts.clearOpenedCameras()
        val beganAtEpochMs = System.currentTimeMillis()
        val failures = ArrayList<String>()
        var started = 0
        var skipped = false
        notifier.addListener(object : RunListener() {
            override fun testStarted(description: Description) { started++; listener.onStarted(shortName(description)) }
            override fun testFailure(failure: Failure) {
                val message = describe(failure)
                failures += message
                listener.onFailure(shortName(failure.description), message)
            }
            override fun testAssumptionFailure(failure: Failure) { skipped = true }
            override fun testIgnored(description: Description) { skipped = true }
        })
        val startedAt = clock()
        var stopped = false
        try {
            val request = Request.aClass(Class.forName(test.className)).filterWith(MethodFilter(test.method))
            request.runner.run(notifier)
        } catch (e: StoppedByUserException) {
            stopped = true
        } catch (e: Throwable) {
            // A failure to load or drive the class is a result, not a reason to take the process down.
            failures += describe(Failure(Description.createSuiteDescription(test.source), e))
        }
        // A body that opened no camera skipped every camera with `continue`; JUnit calls that a pass, we do not.
        val checkedNothing = started > 0 && failures.isEmpty() && !stopped && !stopRequested && openedCameras().isEmpty()
        val verdict = when {
            failures.isNotEmpty() -> VendoredVerdict.FAIL
            started == 0 || skipped || checkedNothing -> VendoredVerdict.SKIP
            else -> VendoredVerdict.PASS
        }
        val reasons = if (verdict == VendoredVerdict.SKIP) runCatching { SkipLog.reasons(skipLog(beganAtEpochMs)) }.getOrDefault(emptyList()) else emptyList()
        listener.onFinished(VendoredResult(test, verdict, clock() - startedAt, failures, stopped || stopRequested, reasons))
    }

    /**
     * JUnit cannot interrupt a test body: [RunNotifier.pleaseStop] takes effect at the next test boundary.
     * Closing the camera under the running test makes the body fail within its own timeouts, and the stop
     * flag makes the next preview setup fail when no camera is open at this moment.
     */
    fun stop() {
        stopRequested = true
        VendoredCts.requestStop()
        notifier.pleaseStop()
        VendoredCts.closeRunningCamera()
    }

    /** "testBasicRecording[0]" rather than JUnit's "testBasicRecording[0](android.hardware.camera2.cts.RecordingTest)". */
    private fun shortName(description: Description): String = description.methodName ?: description.displayName

    private fun describe(failure: Failure): String {
        val error = failure.exception
        val head = error.javaClass.simpleName + (error.message?.let { ": $it" } ?: "")
        val frames = error.stackTrace
            .filter { it.className.startsWith("android.hardware.camera2.cts") || it.className.startsWith("android.hardware.cts") }
            .take(3)
            .map { "  at ${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
        return (listOf(head) + frames).joinToString("\n")
    }
}

/**
 * The reasons a test gave for skipping, read back from this process's own logcat: an app may read its own
 * log lines without READ_LOGS. The CTS tests announce every skipped camera with Log.i/Log.v and a message
 * ending in "skipping" or "Skip the test".
 */
object SkipLog {
    /** Lines of [tag] logged at or after [sinceEpochMs], message text only. */
    fun read(tag: String, sinceEpochMs: Long): List<String> {
        val process = ProcessBuilder("logcat", "-d", "-v", "epoch", "--pid=" + android.os.Process.myPid(), "-s", "$tag:V")
            .redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().use { it.readLines() }
        process.waitFor()
        return parse(lines, sinceEpochMs)
    }

    /** `-v epoch` lines look like " 1789654089.169  1234  5678 I StillCaptureTest: Camera 0 does not support HEIC, skipping". */
    fun parse(lines: List<String>, sinceEpochMs: Long): List<String> = lines.mapNotNull { line ->
        val m = LINE.matchEntire(line.trim()) ?: return@mapNotNull null
        val at = (m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null) * 1000
        if (at + 1000 < sinceEpochMs) null else m.groupValues[2]
    }

    /** The distinct skip messages, in first-seen order, with the "Camera 0 " subject kept so the reader knows which camera. */
    fun reasons(messages: List<String>): List<String> = messages
        .filter { SKIP.containsMatchIn(it) }
        .map { it.trim().trimEnd('.') }
        .distinct()

    private val LINE = Regex("""^(\d+\.\d+)\s+\d+\s+\d+\s+[VDIWEF]\s+[^:]+:\s?(.*)$""")
    private val SKIP = Regex("""skipping|skip the test|skip test""", RegexOption.IGNORE_CASE)
}

/** Keeps every Parameterized row of one method; a suite passes when any child does, as JUnit expects. */
internal class MethodFilter(private val method: String) : Filter() {
    override fun shouldRun(description: Description): Boolean =
        if (description.isTest) description.methodName?.substringBefore('[') == method
        else description.children.any { shouldRun(it) }

    override fun describe(): String = "method $method"
}
