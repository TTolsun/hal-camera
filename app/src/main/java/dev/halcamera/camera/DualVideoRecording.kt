package dev.halcamera.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.view.Surface
import java.io.File

/** Owned by the session handler. Camera device closure precedes stop/release of encoder surfaces. */
internal class DualVideoRecording(private val context: Context, private val ids: List<String>, size: LiveSize,
                                  private val onError: () -> Unit) {
    private val library = MediaLibrary(context)
    private val name = library.name()
    private val files = ids.indices.map { File(context.cacheDir, "${name}_dual_$it.mp4") }
    private val recorders = mutableListOf<MediaRecorder>()
    var savedUris: List<android.net.Uri> = emptyList()
        private set
    private var started = false
    @Volatile private var encoderFailed = false
    val surfaces: List<Surface> get() = recorders.map { it.surface }

    /** A failed source must not publish an apparently successful pair of videos. */
    fun invalidate() { encoderFailed = true }

    init {
        try {
            ids.forEachIndexed { index, id ->
                @Suppress("DEPRECATION") val recorder = MediaRecorder()
                recorders += recorder
                recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                recorder.setVideoSize(size.width, size.height)
                recorder.setVideoFrameRate(30)
                recorder.setVideoEncodingBitRate((size.width * size.height * 6).coerceAtLeast(2_000_000))
                val manager = context.getSystemService(CameraManager::class.java)
                val rotation = manager.getCameraCharacteristics(id)[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90
                recorder.setOrientationHint(rotation)
                recorder.setOutputFile(files[index].absolutePath)
                recorder.setOnErrorListener { _, _, _ -> encoderFailed = true; onError() }
                recorder.prepare()
            }
        } catch (e: Exception) { discard(); throw e }
    }

    fun start() {
        try {
            recorders.forEach { it.start() }
            started = true
        } catch (e: Exception) { discard(); throw e }
    }

    /** Returns only after both complete files have been published, or neither is left behind. */
    fun finish(): Result<Int>? {
        if (!started) { discard(); return null }
        started = false
        return runCatching {
            var failure: Exception? = null
            recorders.forEach { recorder -> try { recorder.stop() } catch (e: Exception) { failure = e } }
            failure?.let { throw it }
            check(!encoderFailed) { "Dual video encoder failed" }
            recorders.forEach { it.release() }
            recorders.clear()
            library.saveVideoPair(name, ids.zip(files)).also { savedUris = it }.size
        }.also { discard() }
    }

    fun discard() {
        recorders.forEach { runCatching { it.reset() }; runCatching { it.release() } }
        recorders.clear()
        files.forEach { it.delete() }
    }
}
