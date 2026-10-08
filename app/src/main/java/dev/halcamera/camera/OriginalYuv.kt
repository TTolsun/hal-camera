package dev.halcamera.camera

/** Lossless sample repacking, not a dump of padding or unused pixels outside the crop. */
class OriginalYuv private constructor(val bytes: ByteArray, val metadata: Map<String, Any?>) {
    companion object {
        const val MAX_BYTES = 16 * 1024 * 1024
        fun supports(width: Int, height: Int) = width > 0 && height > 0 &&
            width % 2 == 0 && height % 2 == 0 && width.toLong() * height <= MAX_BYTES * 2L / 3

        fun copy(planes: List<YuvPacking.Plane>, sourceWidth: Int, sourceHeight: Int,
                 left: Int, top: Int, width: Int, height: Int): OriginalYuv {
            require(supports(width, height)) { "Original YUV exceeds 16 MiB or has odd dimensions" }
            require(left >= 0 && top >= 0 && left.toLong() + width <= sourceWidth && top.toLong() + height <= sourceHeight)
            val bytes = YuvPacking.nv21(planes, left, top, width, height)
            val pixels = width * height
            return OriginalYuv(bytes, linkedMapOf(
                "schema" to 1, "format" to "NV21", "byteLength" to bytes.size,
                "width" to width, "height" to height, "bitsPerSample" to 8,
                "rotationAppliedDegrees" to 0,
                "packing" to "Cropped Y samples in row order followed by interleaved V,U samples; no padding, rotation, compression or color conversion.",
                "planes" to listOf(
                    mapOf("meaning" to "Y", "offset" to 0, "rowStride" to width, "pixelStride" to 1, "width" to width, "height" to height),
                    mapOf("meaning" to "U", "offset" to pixels + 1, "rowStride" to width, "pixelStride" to 2, "width" to width / 2, "height" to height / 2),
                    mapOf("meaning" to "V", "offset" to pixels, "rowStride" to width, "pixelStride" to 2, "width" to width / 2, "height" to height / 2)),
                "source" to mapOf("format" to "YUV_420_888", "width" to sourceWidth, "height" to sourceHeight,
                    "crop" to mapOf("left" to left, "top" to top, "width" to width, "height" to height),
                    "planes" to planes.mapIndexed { index, plane -> mapOf("meaning" to listOf("Y", "U", "V")[index],
                        "bufferPosition" to plane.buffer.position(), "rowStride" to plane.rowStride, "pixelStride" to plane.pixelStride) }),
                "colorInterpretation" to "YUV_420_888 sample values are preserved; no color matrix or range is assumed."
            ))
        }
    }
}
