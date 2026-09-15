package dev.halcamera.cts

enum class Verdict { PASS, FAIL, SKIP }

/** One step of a CTS case on one camera, e.g. one CamcorderProfile of testBasicRecording. */
data class StepResult(val id: String, val verdict: Verdict, val details: List<String> = emptyList())

data class CameraCaseResult(val cameraId: String, val steps: List<StepResult>) {
    val passed: Int get() = steps.count { it.verdict == Verdict.PASS }
    val failed: Int get() = steps.count { it.verdict == Verdict.FAIL }
    val skipped: Int get() = steps.count { it.verdict == Verdict.SKIP }
}

/**
 * A CTS test method transcribed into the app. [source] is the CTS class#method the steps mirror; the official
 * verdict is still only what cts-tradefed writes to test_result.xml.
 */
data class CaseReport(val source: String, val cameras: List<CameraCaseResult>, val cancelled: Boolean = false) {
    val failed: Int get() = cameras.sumOf { it.failed }
    val verdict: Verdict get() = if (failed > 0) Verdict.FAIL else Verdict.PASS
}

data class Dim(val width: Int, val height: Int) {
    val area: Long get() = width.toLong() * height.toLong()
    override fun toString() = "${width}x$height"
}
