package dev.halcamera.cts.recording

import android.media.MediaExtractor
import android.media.MediaFormat
import dev.halcamera.cts.Dim
import java.io.File

/** What CTS validateRecording reads from a recorded file through MediaExtractor: the video track's size, duration and sample times. */
object RecordingReader {
    fun read(file: File, framesProduced: Long): BasicRecordingRules.Recording {
        if (!file.exists()) return BasicRecordingRules.Recording(false, null, 0, emptyList(), framesProduced)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var size: Dim? = null
            var durationUs = 0L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.contains("video")) {
                    durationUs = format.getLong(MediaFormat.KEY_DURATION)
                    size = Dim(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT))
                    extractor.selectTrack(i)
                    break
                }
            }
            if (size == null) return BasicRecordingRules.Recording(true, null, 0, emptyList(), framesProduced)
            val times = ArrayList<Long>()
            while (true) {
                times += extractor.sampleTime
                if (!extractor.advance()) break
            }
            return BasicRecordingRules.Recording(true, size, durationUs, times, framesProduced)
        } finally {
            extractor.release()
        }
    }
}
