package dev.halcamera.camera

/** Graph correlation retains the sensor timestamp; the encoder needs the monotonic audio clock. */
internal class RecordingTimebase(private val sensorIsRealtime: Boolean, private val realtimeMinusMonotonicNs: Long) {
    fun encoderTimestampNs(sensorTimestampNs: Long): Long =
        if (sensorIsRealtime) sensorTimestampNs - realtimeMinusMonotonicNs else sensorTimestampNs
}
