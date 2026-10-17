package dev.halcamera.camera

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import java.io.File

/** Captures one microphone and encodes AAC on its own thread, anchored to the hardware sample clock. */
internal class MicrophoneCapture(private val file: File) {
    private var input: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var failure: Throwable? = null
    private val ready = java.util.concurrent.CountDownLatch(1)
    @Volatile var originNs = 0L
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            val format = AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            val recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(8192,AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)*2)).build()
            input = recorder
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { encoder = it }
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,RATE,1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE,128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,2048)
            },null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(file.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
            running = true
            thread = Thread({ encode(recorder,codec) },"HAL.MultiAudio").also { it.start() }
        } catch (error: Throwable) { release(); throw error }
    }

    fun awaitReady() {
        check(ready.await(3,java.util.concurrent.TimeUnit.SECONDS)) { "Microphone clock initialization timed out" }
        failure?.let { throw IllegalStateException("Microphone recording failed",it) }
        check(originNs > 0) { "Microphone hardware timestamp unavailable" }
    }

    private fun encode(recorder: AudioRecord, codec: MediaCodec) {
        var muxing = false
        try {
            val writer = checkNotNull(muxer)
            val pcm = ShortArray(1024)
            val stamp = AudioTimestamp()
            val info = MediaCodec.BufferInfo()
            var frames = 0L
            var track = -1
            var eos = false
            fun drain(waitUs: Long): Boolean {
                while (true) {
                    val index = codec.dequeueOutputBuffer(info,waitUs)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!muxing); track = writer.addTrack(codec.outputFormat); writer.start(); muxing = true
                    } else if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(muxing)
                                writer.writeSampleData(track,checkNotNull(codec.getOutputBuffer(index)),info)
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                        } finally { codec.releaseOutputBuffer(index,false) }
                    } else return false
                }
            }
            while (running) {
                val count = recorder.read(pcm,0,pcm.size,AudioRecord.READ_NON_BLOCKING)
                if (count == 0) { Thread.sleep(3); continue }
                check(count > 0) { "Microphone read failed: $count" }
                if (originNs == 0L && recorder.getTimestamp(stamp,AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    originNs = SampleClock(RATE).originNs(stamp.framePosition,stamp.nanoTime)
                }
                val inputDeadline = System.nanoTime()+2_000_000_000L
                var index = codec.dequeueInputBuffer(10_000)
                while (index < 0) {
                    check(System.nanoTime() < inputDeadline) { "Audio encoder input timed out" }
                    drain(0); index = codec.dequeueInputBuffer(10_000)
                }
                val buffer = checkNotNull(codec.getInputBuffer(index)); buffer.clear()
                buffer.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer().put(pcm,0,count)
                codec.queueInputBuffer(index,0,count*2,frames*1_000_000/RATE,0)
                frames += count
                if (frames >= RATE / 2 && originNs > 0) ready.countDown()
                drain(0)
            }
            recorder.stop()
            check(originNs > 0) { "Microphone hardware timestamp unavailable; cannot synchronize audio" }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!eos && System.nanoTime() < deadline) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    codec.queueInputBuffer(index,0,0,frames*1_000_000/RATE,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eos = true
                } else drain(0)
            }
            check(eos) { "Audio encoder input timed out" }
            var ended = false
            while (!ended && System.nanoTime() < deadline) ended = drain(10_000)
            check(ended) { "Audio encoder finalization timed out" }
            writer.stop(); muxing = false
        } catch (error: Throwable) { failure = error }
        finally { ready.countDown(); if (muxing) runCatching { muxer?.stop() }; release() }
    }

    /** Called on media IO, after all video inputs have stopped. */
    fun stop() {
        running = false
        thread?.join(9000)
        check(thread?.isAlive != true) { "Microphone shutdown timed out" }
        failure?.let { throw IllegalStateException("Microphone recording failed",it) }
    }

    fun close() { running = false; if (thread == null) release() else runCatching { stop() } }
    private fun release() {
        runCatching { input?.release() }; input = null
        runCatching { encoder?.stop() }; runCatching { encoder?.release() }; encoder = null
        runCatching { muxer?.release() }; muxer = null
    }
    companion object { private const val RATE = 48_000 }
}
