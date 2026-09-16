package dev.halcamera.cts.combination

import dev.halcamera.cts.Dim
import dev.halcamera.cts.OpenCycle
import dev.halcamera.cts.Verdict

/**
 * The decisions of the StillPreviewCombination case, modelled on CTS
 * `StillCaptureTest#testStillPreviewCombination`: for every JPEG still size and every preview size the camera
 * supports, a session with both outputs is configured, the preview streams to its first frame, one still is
 * captured, and the JPEG must come back at the requested size and decode.
 *
 * Two CTS decisions are kept: the still loop is outside the preview loop, and a QCIF preview is not combined
 * with a still above Full HD. One is dropped: CTS waits for AE/AF to converge before the still; here the still
 * is requested right after the first preview frame, so the run stays bounded. Pure Kotlin;
 * [StillPreviewCombinationRunner] captures on the device.
 */
object StillPreviewCombinationRules {
    const val SOURCE = "custom#StillPreviewCombination"
    /** android.graphics.ImageFormat.JPEG, kept as a number so the rules stay off Android. */
    const val JPEG = 256
    val QCIF = Dim(176, 144)
    val FULL_HD = Dim(1920, 1080)

    data class Combo(val still: Dim, val preview: Dim)

    /** What the runner measured for one combination, in ms; a null phase was never reached. */
    data class Capture(
        val configureMs: Double? = null,
        val firstPreviewMs: Double? = null,
        val captureMs: Double? = null,
        val resultReceived: Boolean = false,
        val image: ImageInfo? = null,
        val error: String? = null
    )

    /** The JPEG as the ImageReader delivered it plus what BitmapFactory read from its bytes. */
    data class ImageInfo(val width: Int, val height: Int, val format: Int, val bytes: Int, val decodedSize: Dim?)

    /** previewStillCombinationTestByCamera's loops, with the QCIF + above-Full-HD skip. Both lists are largest first. */
    fun plan(stillSizes: List<Dim>, previewSizes: List<Dim>): List<Combo> =
        stillSizes.flatMap { still ->
            previewSizes.filterNot { preview -> preview == QCIF && (still.width > FULL_HD.width || still.height > FULL_HD.height) }
                .map { preview -> Combo(still, preview) }
        }

    fun stepId(combo: Combo): String = "${combo.still}/${combo.preview}"

    /** Why the camera is skipped before anything is opened; null means run. */
    fun cameraSkipReason(cameraId: String, hasColorOutput: Boolean, stillSizes: List<Dim>, previewSizes: List<Dim>): String? = when {
        !hasColorOutput -> "Camera $cameraId does not support color outputs, skipping"
        stillSizes.isEmpty() -> "Camera $cameraId reports no JPEG output size"
        previewSizes.isEmpty() -> "Camera $cameraId reports no SurfaceHolder preview size within the preview bound"
        else -> null
    }

    /** CameraTestUtils.validateImage for a JPEG: the format, the size, and a blob that decodes to the same size. */
    fun imageFailures(expected: Dim, image: ImageInfo?): List<String> {
        if (image == null) return listOf("Unable to get the image after capture")
        val out = ArrayList<String>()
        if (image.format != JPEG) out += "Image format ${image.format} is not JPEG"
        if (image.width != expected.width || image.height != expected.height) out += "Image size ${image.width}x${image.height} doesn't match the requested size $expected"
        if (image.bytes <= 0) out += "JPEG buffer is empty"
        val decoded = image.decodedSize
        if (decoded == null) out += "JPEG cannot be decoded"
        else if (decoded != expected) out += "Decoded JPEG size $decoded doesn't match the requested size $expected"
        return out
    }

    fun judge(combo: Combo, capture: Capture): Pair<Verdict, List<String>> {
        val failures = ArrayList<String>()
        capture.error?.let { failures += it }
        if (capture.error == null) {
            if (!capture.resultReceived) failures += "Timeout waiting for the still capture result"
            failures += imageFailures(combo.still, capture.image)
        }
        return (if (failures.isEmpty()) Verdict.PASS else Verdict.FAIL) to (listOfNotNull(summary(capture)) + failures)
    }

    /** The timings a row should still show, or null when nothing was measured. */
    fun summary(capture: Capture): String? {
        val parts = ArrayList<String>()
        capture.configureMs?.let { parts += "configure ${OpenCycle.ms(it)}" }
        capture.firstPreviewMs?.let { parts += "first preview ${OpenCycle.ms(it)}" }
        capture.captureMs?.let { parts += "still ${OpenCycle.ms(it)}" }
        capture.image?.let { parts += "jpeg ${it.bytes / 1024} KB" }
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }
}
