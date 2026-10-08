package dev.halcamera.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.ExifInterface
import android.util.Size
import java.io.OutputStream

/**
 * A LIVE RAW still as DNG (#177): the copied RAW_SENSOR samples with the characteristics and the capture result of
 * the same sensor timestamp, written by DngCreator on the media thread. Orientation goes in the DNG tag; the samples
 * are never rotated.
 */
internal class DngOutput(private val frame: RawFrame, private val characteristics: CameraCharacteristics,
                         private val result: CaptureResult, private val rotation: Int) {
    fun write(target: OutputStream) {
        DngCreator(characteristics, result).use { dng ->
            dng.setOrientation(exifOrientation(rotation))
            dng.writeByteBuffer(target, Size(frame.width, frame.height), frame.pixels.duplicate(), 0)
        }
    }

    fun metadata(): Map<String, Any?> = linkedMapOf(
        "source" to "RAW_SENSOR", "format" to "DNG", "writer" to "android.hardware.camera2.DngCreator",
        "width" to frame.width, "height" to frame.height, "bitsPerSample" to 16,
        "sensorTimestampNs" to result[CaptureResult.SENSOR_TIMESTAMP],
        "orientationDegrees" to rotation, "rotationAppliedDegrees" to 0,
        "cfaArrangement" to characteristics[CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT],
        "whiteLevel" to characteristics[CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL],
        "blackLevelPattern" to characteristics[CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN]?.let { pattern ->
            IntArray(4).also { pattern.copyTo(it, 0) }.toList()
        })

    companion object {
        fun exifOrientation(rotation: Int) = when ((rotation % 360 + 360) % 360) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
    }
}
