package dev.halcamera.cts.sizes

import dev.halcamera.cts.Dim
import dev.halcamera.cts.OpenCycle
import dev.halcamera.cts.Verdict

/**
 * The decisions of the AllSizeOnOff case: every SurfaceHolder preview size a camera reports is opened once —
 * open → preview session at that size → first frame → close — and each size is its own PASS/FAIL row.
 *
 * Unlike testBasicRecording's preview list, nothing is bounded to 1080p: a size the camera advertises for a
 * SurfaceHolder must configure and stream, whatever the display can show. Pure Kotlin; [AllSizeOnOffRunner]
 * performs the passes.
 */
object AllSizeOnOffRules {
    const val SOURCE = "custom#AllSizeOnOff"

    /** The sizes to visit: each once, largest area first. */
    fun plan(sizes: List<Dim>): List<Dim> =
        sizes.distinct().sortedWith(compareByDescending<Dim> { it.area }.thenByDescending { it.width })

    fun stepId(size: Dim): String = size.toString()

    /** Why the camera is skipped before anything is opened; null means run. */
    fun cameraSkipReason(cameraId: String, hasColorOutput: Boolean, sizes: List<Dim>): String? = when {
        !hasColorOutput -> "Camera $cameraId does not support color outputs, skipping"
        sizes.isEmpty() -> "Camera $cameraId reports no SurfaceHolder output size"
        else -> null
    }

    fun judge(cycle: OpenCycle): Pair<Verdict, List<String>> {
        val failures = cycle.failures()
        return (if (failures.isEmpty()) Verdict.PASS else Verdict.FAIL) to (listOfNotNull(cycle.summary()) + failures)
    }
}
