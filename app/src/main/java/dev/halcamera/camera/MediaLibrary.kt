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
    internal val resolver = context.applicationContext.contentResolver
    fun name() = "HAL_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + "_" + UUID.randomUUID().toString().take(6)

    @Suppress("DEPRECATION")
    internal fun create(name: String, video: Boolean, mime: String = if (video) "video/mp4" else "image/jpeg"): Uri {
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

    internal fun write(uri: Uri, writer: (OutputStream) -> Unit) {
        (resolver.openOutputStream(uri) ?: error("Cannot open media entry")).use(writer)
    }

    internal fun publish(uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29) check(resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null) == 1) { "Cannot publish media entry" }
    }

    fun savePair(name: String, yuvJpeg: ByteArray, cameraJpeg: ByteArray): List<Uri> {
        return savePhotos(name, yuvJpeg, cameraJpeg)
    }

    /** Both physical images are published together; an incomplete pair leaves no gallery entry. */
    fun saveDualPhotos(images: List<Pair<String, ByteArray>>): List<Uri> {
        require(images.size == 2 && images.map { it.first }.distinct().size == 2)
        val base = name()
        return transaction {
            images.mapIndexed { index, (physicalId, bytes) ->
                val id = physicalId.replace(Regex("[^A-Za-z0-9_-]"), "_")
                save("${base}_${if (index == 0) "A" else "B"}_cam${id}_YUV.jpg", "image/jpeg") { it.write(bytes) }.uri
            }
        }
    }

    /** Legacy snapshot adapter. Photo-mode captures use saveCapture with their capture metadata. */
    fun savePhotos(name: String, yuvJpeg: ByteArray?, cameraJpeg: ByteArray?): List<Uri> =
        saveFiles(name, yuvJpeg, cameraJpeg, null).map { it.uri }

    internal fun saveCapture(name: String, yuvJpeg: ByteArray?, cameraJpeg: ByteArray?,
                    captureMetadata: Map<String, Any?>, dng: DngOutput? = null): List<PhotoArtifact> =
        saveFiles(name, yuvJpeg, cameraJpeg, captureMetadata, dng)

    @Suppress("DEPRECATION")
    internal fun createData(name: String, mime: String): Uri {
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
                          captureMetadata: Map<String, Any?>?, dng: DngOutput? = null): List<PhotoArtifact> {
        require(yuvJpeg != null || cameraJpeg != null || dng != null)
        return transaction {
            val outputs = mutableListOf<Map<String, Any?>>()
            fun output(filename: String, mime: String, metadata: Map<String, Any?>, writer: (OutputStream) -> Unit): PhotoArtifact =
                save(filename, mime, writer).also {
                    outputs += metadata + mapOf("file" to filename, "mime" to mime, "byteLength" to it.bytes)
                }
            buildList {
                if (yuvJpeg != null) add(output("${name}_YUV.jpg", "image/jpeg",
                    mapOf("source" to "YUV_420_888", "format" to "JPEG")) { it.write(yuvJpeg) })
                if (cameraJpeg != null) add(output("${name}_JPEG.jpg", "image/jpeg",
                    mapOf("source" to "camera", "format" to "JPEG")) { it.write(cameraJpeg) })
                if (dng != null) add(output("${name}_RAW.dng", DNG_MIME, dng.metadata(), dng::write))
                if (captureMetadata != null) {
                    val json = org.json.JSONObject(mapOf("schema" to 1, "capture" to captureMetadata, "outputs" to outputs))
                    add(save("${name}_metadata.json", "application/json") { it.write(json.toString(2).toByteArray(Charsets.UTF_8)) })
                }
            }
        }
    }

    internal inner class Transaction(private val pending: MediaTransaction<Uri>) {
        fun save(name: String, mime: String, writer: (OutputStream) -> Unit): PhotoArtifact {
            val uri = pending.own(if (mime.startsWith("image/") || mime.startsWith("video/"))
                create(name, mime.startsWith("video/"), mime) else createData(name, mime))
            var written = 0L
            write(uri) { target -> writer(object : java.io.FilterOutputStream(target) {
                override fun write(b: Int) { out.write(b); written++ }
                override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); written += len }
            }) }
            return PhotoArtifact(name, mime, uri, written)
        }
    }

    internal fun <T> transaction(block: Transaction.() -> T): T =
        MediaTransaction<Uri>(::publish, ::delete).run { Transaction(this).block() }

    internal fun saveFile(name: String, mime: String, writer: (OutputStream) -> Unit): PhotoArtifact =
        transaction { save(name, mime, writer) }

    internal fun delete(uri: Uri) { resolver.delete(uri, null, null) }
    internal fun deleteAll(uris: List<Uri>) { uris.forEach { runCatching { delete(it) } } }

    companion object {
        const val DNG_MIME = "image/x-adobe-dng"
    }

    fun saveVideo(file: File): Uri = saveVideo(file, "${name()}.mp4")

    internal fun saveVideo(file: File, filename: String): Uri =
        saveFile(filename, "video/mp4") { target -> file.inputStream().use { it.copyTo(target) } }.uri

    /** Both videos share a capture name; rollback every entry if either copy or publish fails. */
    fun saveVideoPair(name: String, files: List<Pair<String, File>>): List<Uri> {
        require(files.size == 2)
        return transaction {
            files.mapIndexed { index, (physicalId, file) ->
                val id = physicalId.replace(Regex("[^A-Za-z0-9_-]"), "_")
                save("${name}_${if (index == 0) "A" else "B"}_cam${id}.mp4", "video/mp4") { target ->
                    file.inputStream().use { it.copyTo(target) }
                }.uri
            }
        }
    }
}
