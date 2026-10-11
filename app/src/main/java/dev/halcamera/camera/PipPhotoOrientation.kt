package dev.halcamera.camera

/** Display orientation also matters on portrait Live running on a natural-landscape device. */
internal fun pipPhotoOrientation(sensorDegrees: Int, front: Boolean, displayDegrees: Int): Int =
    (sensorDegrees + if (front) displayDegrees else -displayDegrees + 360) % 360
