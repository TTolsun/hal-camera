package dev.halcamera.camera

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** One microphone recording is shared by every independent camera file. Never opens competing MIC sources. */
internal class MultiAudio(context: Context) {
    private val file = File.createTempFile("multi-audio-", ".m4a", context.cacheDir)
    private val recorder = MicrophoneCapture(file)

    fun start() {
        recorder.start()
        recorder.awaitReady()
    }

    fun stop() {
        recorder.stop()
    }

    /** Called on media IO after both encoders stop; each invocation owns its own extractor. */
    fun addTo(video: File, videoStartedNs: Long) {
        val output = File.createTempFile("multi-mux-", ".mp4", video.parentFile)
        val picture = MediaExtractor()
        val sound = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            picture.setDataSource(video.absolutePath); sound.setDataSource(file.absolutePath)
            val vi = (0 until picture.trackCount).first { picture.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val ai = (0 until sound.trackCount).first { sound.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            val videoFormat = picture.getTrackFormat(vi)
            val durationUs = videoFormat.getLong(MediaFormat.KEY_DURATION)
            val writer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            val vt = writer.addTrack(videoFormat); val at = writer.addTrack(sound.getTrackFormat(ai))
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) writer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION))
            writer.start()
            copy(picture, vi, writer, vt) { it.takeIf { pts -> pts < durationUs } }
            check(videoStartedNs > 0) { "Video produced no timestamped frames" }
            val timeline = RecordingTimeline(recorder.originNs,videoStartedNs,durationUs)
            check(copy(sound, ai, writer, at,timeline::audioTimeUs) > 0) { "Microphone produced no audio samples" }
            writer.stop(); writer.release(); muxer = null
            output.inputStream().use { input -> video.outputStream().use(input::copyTo) }
        } finally {
            runCatching { muxer?.release() }; picture.release(); sound.release(); output.delete()
        }
    }

    private fun copy(source: MediaExtractor, track: Int, target: MediaMuxer, output: Int, time: (Long) -> Long?): Int {
        source.selectTrack(track)
        val format = source.getTrackFormat(track)
        val capacity = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 4 * 1024 * 1024
        val buffer = ByteBuffer.allocateDirect(capacity.coerceAtLeast(4 * 1024 * 1024))
        val info = MediaCodec.BufferInfo()
        var count = 0
        while (source.sampleTime >= 0) {
            val pts = time(source.sampleTime)
            if (pts != null) {
                buffer.clear()
                val size = source.readSampleData(buffer, 0)
                if (size < 0) break
                check(source.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted recording is unsupported" }
                val flags = if (source.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, pts, flags)
                target.writeSampleData(output, buffer, info); count++
            }
            if (!source.advance()) break
        }
        return count
    }

    fun close() { recorder.close(); file.delete() }
}
