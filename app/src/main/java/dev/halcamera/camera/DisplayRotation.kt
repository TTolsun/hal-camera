package dev.halcamera.camera

import android.view.Surface

/** One mapping between Surface.ROTATION_* and clockwise display degrees. */
internal object DisplayRotation {
    fun degrees(rotation: Int?): Int = when (rotation) {
        Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
    }

    fun surfaceRotation(degrees: Int): Int = when (degrees) {
        90 -> Surface.ROTATION_90; 180 -> Surface.ROTATION_180; 270 -> Surface.ROTATION_270; else -> Surface.ROTATION_0
    }
}
