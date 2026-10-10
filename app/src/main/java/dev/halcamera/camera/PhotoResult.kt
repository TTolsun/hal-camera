package dev.halcamera.camera

import android.net.Uri

/** Saved still outputs (the selected JPEG source, plus DNG when RAW is on), returned after a capture request completes. */
data class PhotoResult(val requestId: String?, val name: String, val sensorTimestamp: Long, val uris: List<Uri>,
    val artifacts: List<PhotoArtifact> = emptyList())

data class PhotoArtifact(val name: String, val mime: String, val uri: Uri, val bytes: Long = 0)

