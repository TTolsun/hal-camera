package dev.halcamera.camera

import android.media.MediaCodec
import android.media.MediaMuxer

/** Moves one encoder's output into one muxer track; the muxer starts on the encoder's first output format. */
internal class EncoderTrack(private val codec: MediaCodec, private val muxer: MediaMuxer) {
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    var started = false
        private set

    /** Writes every ready buffer. True at end of stream; false once no output is ready within [waitUs]. */
    fun drain(waitUs: Long, retime: (Long) -> Long = { it }): Boolean {
        while (true) {
            val index = codec.dequeueOutputBuffer(info,waitUs)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                check(!started); track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
            } else if (index >= 0) {
                try {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        check(started)
                        info.presentationTimeUs = retime(info.presentationTimeUs)
                        muxer.writeSampleData(track,checkNotNull(codec.getOutputBuffer(index)),info)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                } finally { codec.releaseOutputBuffer(index,false) }
            } else return false
        }
    }

    fun stop() { muxer.stop(); started = false }

    /** Failure path: a started muxer must be stopped before release, and its error must not hide the first one. */
    fun abandon() { if (started) runCatching { stop() } }
}
