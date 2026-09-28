package dev.halcamera.camera

import android.net.Uri

/** Saved still outputs (YUV + JPEG by default), returned after a capture request completes. */
data class PhotoResult(val requestId: String?, val name: String, val sensorTimestamp: Long, val uris: List<Uri>)
