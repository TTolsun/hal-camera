package dev.halcamera.camera

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import java.util.concurrent.Executors

/** Queries only completed HALCamera album entries, off the camera and UI threads. */
class RecentMediaThumbnail(context: Context, private val onLoaded: (Bitmap?, Boolean) -> Unit) {
    private data class Item(val uri: Uri, val name: String, val added: Long, val video: Boolean)
    private val resolver = context.applicationContext.contentResolver
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var active = false
    private var generation = 0
    private var registered = false
    private val refresh = Runnable { load() }
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            if (!active) return
            main.removeCallbacks(refresh)
            main.postDelayed(refresh, 250)
        }
    }

    fun start() {
        if (active || worker.isShutdown) return
        active = true
        registered = runCatching {
            resolver.registerContentObserver(Uri.parse("content://media"), true, observer)
        }.isSuccess
        load()
    }

    fun stop() {
        active = false
        generation++
        main.removeCallbacks(refresh)
        if (registered) resolver.unregisterContentObserver(observer)
        registered = false
    }

    fun close() {
        stop()
        worker.shutdown()
    }

    private fun load() {
        if (!active || worker.isShutdown) return
        val request = ++generation
        worker.execute {
            val result = runCatching {
                val latest = listOfNotNull(query(false), query(true))
                    .maxWithOrNull(compareBy<Item> { it.added }.thenBy { it.name })
                latest?.let { thumbnail(it) to it.video }
            }.getOrNull()
            main.post {
                if (active && generation == request) onLoaded(result?.first, result?.second ?: false)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun query(video: Boolean): Item? {
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val column = if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val selection = if (Build.VERSION.SDK_INT >= 29) "$column = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0 AND ${MediaStore.MediaColumns.SIZE} > 0"
            else "$column LIKE ? AND ${MediaStore.MediaColumns.SIZE} > 0"
        val path = if (Build.VERSION.SDK_INT >= 29) "DCIM/HALCamera/"
            else "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/HALCamera/%"
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED)
        return resolver.query(collection, projection, selection, arrayOf(path),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC")?.use { cursor ->
            if (cursor.moveToFirst()) Item(ContentUris.withAppendedId(collection, cursor.getLong(0)), cursor.getString(1).orEmpty(), cursor.getLong(2), video)
            else null
        }
    }

    @Suppress("DEPRECATION")
    private fun thumbnail(item: Item): Bitmap? = if (Build.VERSION.SDK_INT >= 29) resolver.loadThumbnail(item.uri, Size(192, 192), null)
        else if (item.video) MediaStore.Video.Thumbnails.getThumbnail(resolver, ContentUris.parseId(item.uri), MediaStore.Video.Thumbnails.MINI_KIND, null)
        else MediaStore.Images.Thumbnails.getThumbnail(resolver, ContentUris.parseId(item.uri), MediaStore.Images.Thumbnails.MINI_KIND, null)
}
