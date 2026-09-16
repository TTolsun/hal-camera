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
    val cancelled: Boolean
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
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
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
        val verdict = when {
            failures.isNotEmpty() -> VendoredVerdict.FAIL
            started == 0 || skipped -> VendoredVerdict.SKIP
            else -> VendoredVerdict.PASS
        }
        listener.onFinished(VendoredResult(test, verdict, clock() - startedAt, failures, stopped || stopRequested))
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

/** Keeps every Parameterized row of one method; a suite passes when any child does, as JUnit expects. */
internal class MethodFilter(private val method: String) : Filter() {
    override fun shouldRun(description: Description): Boolean =
        if (description.isTest) description.methodName?.substringBefore('[') == method
        else description.children.any { shouldRun(it) }

    override fun describe(): String = "method $method"
}
