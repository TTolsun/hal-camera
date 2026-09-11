package dev.halcamera.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Publishes only complete files. A failed pair is removed instead of leaving half a capture. */
class MediaLibrary(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    fun name() = "HAL_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + "_" + UUID.randomUUID().toString().take(6)

    @Suppress("DEPRECATION")
    private fun create(name: String, video: Boolean): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/HALCamera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "HALCamera")
                check(directory.isDirectory || directory.mkdirs()) { "Cannot create gallery directory" }
                put(MediaStore.MediaColumns.DATA, File(directory, name).absolutePath)
            }
        }
        return resolver.insert(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Cannot create media entry")
    }

    private fun write(uri: Uri, writer: (OutputStream) -> Unit) {
        (resolver.openOutputStream(uri) ?: error("Cannot open media entry")).use(writer)
    }

    private fun publish(uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29) check(resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null) == 1) { "Cannot publish media entry" }
    }

    fun savePair(name: String, yuvJpeg: ByteArray, cameraJpeg: ByteArray): List<Uri> {
        val entries = mutableListOf<Uri>()
        try {
            val yuv = create("${name}_YUV.jpg", false).also { entries += it }
            val jpeg = create("${name}_JPEG.jpg", false).also { entries += it }
            write(yuv) { it.write(yuvJpeg) }
            write(jpeg) { it.write(cameraJpeg) }
            entries.forEach(::publish)
            return entries
        } catch (e: Exception) {
            entries.forEach { runCatching { resolver.delete(it, null, null) } }
            throw e
        }
    }

    fun saveVideo(file: File): Uri {
        val uri = create("${name()}.mp4", true)
        try {
            write(uri) { target -> file.inputStream().use { it.copyTo(target) } }
            publish(uri)
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }
}
