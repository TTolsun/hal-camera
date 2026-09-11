package dev.halcamera

import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.database.ContentObserver
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import dev.halcamera.ui.Look
import java.util.concurrent.Executors

/** Only media in the app's album is queried; no broad media read permission is needed on Android 10+. */
class GalleryActivity : ComponentActivity() {
    private data class Item(val uri: Uri, val name: String, val video: Boolean, val added: Long)
    private val io = Executors.newSingleThreadExecutor()
    private val items = mutableListOf<Item>()
    private val thumbnails = android.util.LruCache<String, Bitmap>(24)
    private lateinit var adapter: BaseAdapter
    private lateinit var empty: TextView
    private val main = Handler(Looper.getMainLooper())
    private val refresh = Runnable { loadMedia() }
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { main.removeCallbacks(refresh); main.postDelayed(refresh, 200) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Look.expertTile); setPadding(16.dp, 16.dp, 16.dp, 16.dp) }
        val header = Look.row(this)
        header.addView(Look.text(this, "갤러리", 22, Look.onDark, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Look.ghostButton(this, "닫기", dark = true) { finish() }, LinearLayout.LayoutParams(-2, 48.dp))
        root.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16.dp })
        empty = TextView(this).apply { text = "사진과 동영상을 불러오고 있습니다…"; setTextColor(Look.onDarkMuted); gravity = Gravity.CENTER; setPadding(16, 24, 16, 24) }
        root.addView(empty)
        val grid = GridView(this).apply { numColumns = 2; horizontalSpacing = 8.dp; verticalSpacing = 8.dp; stretchMode = GridView.STRETCH_COLUMN_WIDTH }
        adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val item = items[position]
                val tile = (convertView as? LinearLayout) ?: LinearLayout(this@GalleryActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, LinearLayout.LayoutParams(-1, 170.dp))
                    // GridView positions the next row using its last child's height. Reserve equal
                    // label space for images and videos so a three-line video label cannot overlap it.
                    addView(TextView(context).apply { setTextColor(Look.onDarkMuted); textSize = 12f; setLines(3); ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(4.dp, 8.dp, 4.dp, 8.dp) })
                }
                val image = tile.getChildAt(0) as ImageView
                val label = tile.getChildAt(1) as TextView
                label.text = (if (item.video) "▶ VIDEO\n" else "") + item.name
                tile.contentDescription = label.text
                image.tag = item.uri
                image.setImageBitmap(thumbnails.get(item.uri.toString()))
                if (thumbnails.get(item.uri.toString()) == null) io.execute {
                    val bitmap = runCatching { thumbnail(item) }.getOrNull()
                    if (bitmap != null) runOnUiThread {
                        if (!isDestroyed) {
                            thumbnails.put(item.uri.toString(), bitmap)
                            if (image.tag == item.uri) image.setImageBitmap(bitmap)
                        }
                    }
                }
                return tile
            }
        }
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ ->
            val item = items[position]
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, if (item.video) "video/mp4" else "image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: android.content.ActivityNotFoundException) { Toast.makeText(this, "이 파일을 열 수 있는 갤러리 앱이 없습니다", Toast.LENGTH_LONG).show() }
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                root.setPadding(16.dp + bars.left, 16.dp + bars.top, 16.dp + bars.right, 16.dp + bars.bottom)
            }
            insets
        }
        root.requestApplyInsets()
    }

    override fun onResume() {
        super.onResume()
        contentResolver.registerContentObserver(Uri.parse("content://media"), true, observer)
        loadMedia()
    }

    override fun onPause() {
        contentResolver.unregisterContentObserver(observer)
        main.removeCallbacks(refresh)
        super.onPause()
    }

    private fun loadMedia() {
        io.execute {
            val result = runCatching { (query(false) + query(true)).sortedByDescending { it.added } }
            runOnUiThread {
                if (!isDestroyed) result.fold({ media ->
                    items.clear(); items.addAll(media); adapter.notifyDataSetChanged()
                    empty.text = "아직 저장한 사진이나 동영상이 없습니다.\n촬영하면 YUV · JPEG 사진 두 장이 표시됩니다."
                    empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                }, { empty.text = "갤러리를 불러오지 못했습니다: ${it.message}"; empty.visibility = View.VISIBLE })
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun query(video: Boolean): List<Item> {
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED)
        val column = if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val selection = if (Build.VERSION.SDK_INT >= 29) "$column = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0" else "$column LIKE ?"
        val path = if (Build.VERSION.SDK_INT >= 29) "DCIM/HALCamera/" else "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/HALCamera/%"
        return buildList {
            contentResolver.query(collection, projection, selection, arrayOf(path), "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
                while (cursor.moveToNext()) add(Item(ContentUris.withAppendedId(collection, cursor.getLong(0)), cursor.getString(1), video, cursor.getLong(2)))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun thumbnail(item: Item): Bitmap? = if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(item.uri, Size(400, 400), null)
        else if (item.video) MediaStore.Video.Thumbnails.getThumbnail(contentResolver, ContentUris.parseId(item.uri), MediaStore.Video.Thumbnails.MINI_KIND, null)
        else MediaStore.Images.Thumbnails.getThumbnail(contentResolver, ContentUris.parseId(item.uri), MediaStore.Images.Thumbnails.MINI_KIND, null)

    override fun onDestroy() { main.removeCallbacks(refresh); io.shutdown(); thumbnails.evictAll(); super.onDestroy() }
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
