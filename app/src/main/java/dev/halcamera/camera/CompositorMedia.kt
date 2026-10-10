package dev.halcamera.camera

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/** Compression and publication never run on the GL thread. Close drains queued media work. */
internal class CompositorMedia(context: Context) {
    private val library = MediaLibrary(context)
    private val work = Executors.newSingleThreadExecutor()

    fun photo(bytes: ByteBuffer, size: LiveSize, timestamp: Long, physicalIds: List<String>,
        timestamps: Map<String,Long>, serviceIds: List<String>, done: (Result<ConcurrentPhoto>) -> Unit) {
        work.execute { done(runCatching {
            val pixels = IntArray(size.width * size.height)
            for (y in 0 until size.height) for (x in 0 until size.width) {
                val at = ((size.height - 1 - y) * size.width + x) * 4
                pixels[y * size.width + x] = (255 shl 24) or ((bytes.get(at).toInt() and 255) shl 16) or
                    ((bytes.get(at+1).toInt() and 255) shl 8) or (bytes.get(at+2).toInt() and 255)
            }
            val bitmap = Bitmap.createBitmap(pixels,size.width,size.height,Bitmap.Config.ARGB_8888)
            val jpeg = try { ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)); it.toByteArray()
            } } finally { bitmap.recycle() }
            ConcurrentPhoto(jpeg,timestamp,null,size,physicalIds,timestamps,serviceIds)
        }) }
    }

    fun video(recording: CompositorRecording, save: Boolean, done: (Result<String>) -> Unit) {
        work.execute { done(runCatching { recording.finish(save,library) }) }
    }

    fun discard(recording: CompositorRecording) { work.execute { recording.discard() } }
    fun close(done: () -> Unit) { work.execute(done); work.shutdown() }
}
