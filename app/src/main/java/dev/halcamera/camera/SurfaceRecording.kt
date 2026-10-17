package dev.halcamera.camera

import android.view.Surface
import java.io.File

/** Encoder ownership moves to media IO only after its EGL input is detached. */
internal interface SurfaceRecording {
    val surface: Surface
    fun start(audio: Boolean = false)
    fun finish(save: Boolean, library: MediaLibrary, process: ((File, Long) -> Unit)? = null): String
    fun discard()
}
