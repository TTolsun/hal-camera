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
    private fun create(name: String, video: Boolean, mime: String = if (video) "video/mp4" else "image/jpeg"): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
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
        return savePhotos(name, yuvJpeg, cameraJpeg)
    }

    /** Legacy snapshot adapter. Photo-mode captures use saveCapture with their capture metadata. */
    fun savePhotos(name: String, yuvJpeg: ByteArray?, cameraJpeg: ByteArray?): List<Uri> =
        saveFiles(name, yuvJpeg, cameraJpeg, null, null).map { it.uri }

    fun saveCapture(name: String, yuvJpeg: ByteArray?, cameraJpeg: ByteArray?,
                    original: OriginalYuv?, captureMetadata: Map<String, Any?>, dng: DngOutput? = null): List<PhotoArtifact> =
        saveFiles(name, yuvJpeg, cameraJpeg, original, captureMetadata, dng)

    @Suppress("DEPRECATION")
    private fun createData(name: String, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/HALCamera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HALCamera")
                check(directory.isDirectory || directory.mkdirs()) { "Cannot create capture directory" }
                put(MediaStore.MediaColumns.DATA, File(directory, name).absolutePath)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Downloads.EXTERNAL_CONTENT_URI
            else MediaStore.Files.getContentUri("external")
        return resolver.insert(collection, values) ?: error("Cannot create capture file")
    }

    private fun saveFiles(name: String, yuvJpeg: ByteArray?, cameraJpeg: ByteArray?,
                          original: OriginalYuv?, captureMetadata: Map<String, Any?>?, dng: DngOutput? = null): List<PhotoArtifact> {
        require(yuvJpeg != null || cameraJpeg != null || original != null || dng != null)
        require(yuvJpeg == null || original == null) { "Select one YUV save format" }
        val entries = mutableListOf<PhotoArtifact>()
        val outputs = mutableListOf<Map<String, Any?>>()
        fun saveStream(filename: String, mime: String, metadata: Map<String, Any?>, writer: (OutputStream) -> Unit) {
            val uri = if (mime.startsWith("image/")) create(filename, false, mime) else createData(filename, mime)
            entries += PhotoArtifact(filename, mime, uri)
            var written = 0L
            write(uri) { target -> writer(object : java.io.FilterOutputStream(target) {
                override fun write(b: Int) { out.write(b); written++ }
                override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); written += len }
            }) }
            entries[entries.lastIndex] = entries.last().copy(bytes = written)
            outputs += metadata + mapOf("file" to filename, "mime" to mime, "byteLength" to written)
        }
        fun save(filename: String, mime: String, bytes: ByteArray, metadata: Map<String, Any?> = emptyMap()) =
            saveStream(filename, mime, metadata) { it.write(bytes) }
        try {
            if (original != null) save("${name}_YUV.nv21", "application/octet-stream", original.bytes, original.metadata)
            if (yuvJpeg != null) save("${name}_YUV.jpg", "image/jpeg", yuvJpeg,
                mapOf("source" to "YUV_420_888", "format" to "JPEG"))
            if (cameraJpeg != null) save("${name}_JPEG.jpg", "image/jpeg", cameraJpeg,
                mapOf("source" to "camera", "format" to "JPEG"))
            if (dng != null) saveStream("${name}_RAW.dng", DNG_MIME, dng.metadata(), dng::write)
            if (captureMetadata != null) {
                val json = org.json.JSONObject(mapOf("schema" to 1, "capture" to captureMetadata, "outputs" to outputs))
                save("${name}_metadata.json", "application/json", json.toString(2).toByteArray(Charsets.UTF_8))
            }
            entries.forEach { publish(it.uri) }
            return entries
        } catch (e: Exception) {
            entries.forEach { runCatching { resolver.delete(it.uri, null, null) } }
            throw e
        }
    }
    companion object {
        const val DNG_MIME = "image/x-adobe-dng"
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
