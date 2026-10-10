package dev.halcamera.camera

import android.content.Context
import android.media.MediaRecorder
import android.view.Surface
import java.io.File

/** Encoder ownership can move to media IO once its EGL input has been detached. */
internal class CompositorRecording(context: Context, val name: String, private val size: LiveSize, audio: Boolean,
    private val onError: (Throwable) -> Unit) {
    private val file = File(context.cacheDir, name)
    @Suppress("DEPRECATION") private val recorder = MediaRecorder()
    val surface: Surface = android.media.MediaCodec.createPersistentInputSurface()
    private var withAudio = audio

    init {
        configure(audio)
    }

    private fun configure(audio: Boolean) {
        try {
            if (audio) recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (audio) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioEncodingBitRate(128_000); recorder.setAudioSamplingRate(48_000)
            }
            recorder.setVideoSize(size.width,size.height); recorder.setVideoFrameRate(30)
            recorder.setVideoEncodingBitRate((size.width * size.height * 6).coerceAtLeast(2_000_000))
            recorder.setOutputFile(file.absolutePath)
            recorder.setOnErrorListener { _, _, _ -> onError(IllegalStateException("Video encoder failed")) }
            recorder.setInputSurface(surface)
            recorder.prepare()
        } catch (e: Exception) { discard(); throw e }
    }

    fun start(audio: Boolean = withAudio) {
        if (audio != withAudio) { recorder.reset(); configure(audio); withAudio = audio }
        recorder.start()
    }
    fun finish(save: Boolean, library: MediaLibrary): String = try {
        recorder.stop()
        check(save) { "Recording cancelled" }
        library.saveVideo(file, name).toString()
    } finally { discard() }

    fun discard() {
        runCatching { recorder.reset() }; runCatching { recorder.release() }
        surface.release()
        file.delete()
    }
}
