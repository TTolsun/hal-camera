package dev.halcamera.camera

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File

/** Retains the first encoded frame's capture time, even if startup frames are discarded. */
internal class TimestampedVideoRecording(context: Context, private val name: String, size: LiveSize) : SurfaceRecording {
    private val file = File(context.cacheDir,name)
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private var muxer: MediaMuxer? = null
    override val surface: Surface
    private var worker: Thread? = null
    @Volatile private var stopping = false
    @Volatile private var failure: Throwable? = null
    private val timeline = EncodedVideoTimeline()
    private var released = false

    init {
        try {
            codec.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,size.width,size.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE,(size.width*size.height*6).coerceAtLeast(2_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE,30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES,0)
            },null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
        } catch (error: Throwable) { codec.release(); file.delete(); throw error }
    }

    override fun start(audio: Boolean) {
        check(!audio) { "Multi uses a shared microphone" }
        codec.start()
        worker = Thread({ drain() },"HAL.MultiVideo").also { it.start() }
    }

    private fun drain() {
        var muxing = false
        try {
            val writer = MediaMuxer(file.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            val info = MediaCodec.BufferInfo()
            var track = -1
            var deadline = Long.MAX_VALUE
            while (true) {
                if (stopping && deadline == Long.MAX_VALUE) {
                    codec.signalEndOfInputStream()
                    deadline = System.nanoTime()+5_000_000_000L
                }
                check(System.nanoTime() < deadline) { "Video encoder finalization timed out" }
                val index = codec.dequeueOutputBuffer(info,10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(!muxing); track = writer.addTrack(codec.outputFormat); writer.start(); muxing = true
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(muxing)
                            info.presentationTimeUs = timeline.timeUs(info.presentationTimeUs)
                            writer.writeSampleData(track,checkNotNull(codec.getOutputBuffer(index)),info)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    } finally { codec.releaseOutputBuffer(index,false) }
                }
            }
            check(timeline.originUs >= 0) { "Video produced no frames" }
            writer.stop(); muxing = false
        } catch (error: Throwable) { failure = error }
        finally {
            if (muxing) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }; muxer = null
        }
    }

    override fun finish(save: Boolean, library: MediaLibrary, process: ((File, Long) -> Unit)?): String = try {
        stop()
        check(save) { "Recording cancelled" }
        failure?.let { throw IllegalStateException("Video encoding failed",it) }
        process?.invoke(file,timeline.originUs*1_000)
        library.saveVideo(file,name).toString()
    } finally { discard() }

    private fun stop() {
        stopping = true
        worker?.join(7_000)
        check(worker?.isAlive != true) { "Video shutdown timed out" }
    }

    override fun discard() {
        if (released) return
        released = true
        runCatching { stop() }
        runCatching { codec.stop() }; runCatching { codec.release() }
        surface.release(); file.delete()
    }
}
