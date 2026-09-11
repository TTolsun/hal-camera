package dev.halcamera

import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.ContentUris
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import dev.halcamera.ui.GalleryImageView
import dev.halcamera.ui.IconButton
import dev.halcamera.ui.Look
import dev.halcamera.ui.showSelectionPopup
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A browsable app album. Android 10+ requires no broad photo-library permission. */
class GalleryActivity : ComponentActivity() {
    private data class Item(
        val uri: Uri, val name: String, val video: Boolean, val added: Long,
        val duration: Long, val bytes: Long, val width: Int, val height: Int,
    ) {
        val key: String get() = uri.toString()
    }

    private val queryIo = Executors.newSingleThreadExecutor()
    private val imageIo = Executors.newSingleThreadExecutor()
    private val thumbnailIo = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue(48))
    private val thumbnails = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pendingThumbnails = mutableSetOf<String>()
    private val failedThumbnails = mutableSetOf<String>()
    private val items = mutableListOf<Item>()
    private var visibleItems = emptyList<Item>()
    private val selected = linkedSetOf<String>()
    private val main = Handler(Looper.getMainLooper())
    private val refresh = Runnable { loadMedia() }
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            main.removeCallbacks(refresh)
            main.postDelayed(refresh, 200)
        }
    }
    private var resumed = false
    private var selectionMode = false
    private var filter = 0
    private var currentUri: String? = null
    private var currentPosition = 0
    private var gridPosition = 0
    private var gridTop = 0
    private var restoreGrid = true
    private var loadGeneration = 0
    private var imageGeneration = 0
    private var videoPosition = 0
    private var deleteBusy = false
    private var pendingDelete = arrayListOf<String>()
    private var failedDeletes = 0
    private var deleteBySystem = false
    private var awaitingDeletePermission = false
    private var infoVisible = false

    private lateinit var root: LinearLayout
    private lateinit var album: LinearLayout
    private lateinit var detail: LinearLayout
    private lateinit var grid: GridView
    private lateinit var adapter: BaseAdapter
    private lateinit var empty: TextView
    private lateinit var count: TextView
    private lateinit var filterButton: Button
    private lateinit var selectButton: Button
    private lateinit var selectionBar: LinearLayout
    private lateinit var shareSelection: Button
    private lateinit var deleteSelection: Button
    private lateinit var detailTitle: TextView
    private lateinit var detailCount: TextView
    private lateinit var detailInfo: TextView
    private lateinit var infoButton: IconButton
    private lateinit var photo: GalleryImageView
    private lateinit var video: VideoView
    private lateinit var videoController: MediaController
    private lateinit var play: IconButton
    private lateinit var loading: TextView
    private lateinit var previous: Button
    private lateinit var next: Button
    private lateinit var zoom: IconButton
    private lateinit var detailDelete: Button
    private lateinit var detailShare: Button

    private val deletePermission = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        awaitingDeletePermission = false
        if (result.resultCode == RESULT_OK) {
            if (deleteBySystem) finishDeletion() else deleteLegacyNext()
        } else {
            deleteBusy = false
            pendingDelete.clear()
            updateSelection()
            loadMedia()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            filter = it.getInt("filter").coerceIn(0, 2)
            selectionMode = it.getBoolean("selectionMode")
            selected.addAll(it.getStringArrayList("selected").orEmpty())
            currentUri = it.getString("currentUri")
            currentPosition = it.getInt("currentPosition")
            gridPosition = it.getInt("gridPosition")
            gridTop = it.getInt("gridTop")
            videoPosition = it.getInt("videoPosition")
            pendingDelete = it.getStringArrayList("pendingDelete") ?: arrayListOf()
            deleteBySystem = it.getBoolean("deleteBySystem")
            awaitingDeletePermission = it.getBoolean("awaitingDeletePermission")
            failedDeletes = it.getInt("failedDeletes")
            deleteBusy = pendingDelete.isNotEmpty()
        }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        buildAlbum()
        buildDetail()
        root.addView(album, LinearLayout.LayoutParams(-1, -1))
        root.addView(detail, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        root.requestApplyInsets()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    currentUri != null -> closeDetail()
                    selectionMode -> { selectionMode = false; selected.clear(); updateSelection() }
                    else -> finish()
                }
            }
        })
        if (deleteBusy && !deleteBySystem && !awaitingDeletePermission) deleteLegacyNext()
    }

    private fun buildAlbum() {
        album = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = Look.row(this).apply { setPadding(8.dp, 8.dp, 12.dp, 0) }
        header.addView(IconButton(this, R.drawable.ic_action_back, "카메라로 돌아가기") { onBackPressedDispatcher.onBackPressed() }, LinearLayout.LayoutParams(48.dp, 48.dp))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Look.text(context, "HALCamera", 24, Look.onDark, bold = true))
        }
        count = Look.text(this, "불러오는 중…", 13, Look.onDarkMuted)
        titles.addView(count)
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 8.dp })
        selectButton = button("선택") {
            selectionMode = !selectionMode
            if (!selectionMode) selected.clear()
            updateSelection()
        }
        header.addView(selectButton, LinearLayout.LayoutParams(-2, 48.dp))
        album.addView(header, LinearLayout.LayoutParams(-1, -2))
        filterButton = button("전체 ▾") {
            showSelectionPopup(filterButton, listOf("전체", "사진", "동영상"), filter) {
                rememberGrid()
                filter = it
                gridPosition = 0
                gridTop = 0
                restoreGrid = true
                selectionMode = false
                selected.clear()
                applyFilter()
            }
        }
        album.addView(filterButton, LinearLayout.LayoutParams(-2, 48.dp).apply { marginStart = 12.dp })
        val content = FrameLayout(this)
        grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = 2.dp
            verticalSpacing = 2.dp
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(2.dp, 0, 2.dp, 0)
            clipToPadding = false
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setOnItemClickListener { _, _, position, _ ->
                val item = visibleItems[position]
                if (selectionMode) toggleSelection(item) else openDetail(item)
            }
            setOnItemLongClickListener { _, _, position, _ ->
                if (!deleteBusy) { selectionMode = true; toggleSelection(visibleItems[position]) }
                true
            }
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) {
                    val columns = ((right - left) / 100.dp).coerceIn(3, 6)
                    if (numColumns != columns) numColumns = columns
                    this@GalleryActivity.adapter.notifyDataSetChanged()
                }
            }
        }
        adapter = object : BaseAdapter() {
            override fun getCount() = visibleItems.size
            override fun getItem(position: Int) = visibleItems[position]
            override fun getItemId(position: Int) = visibleItems[position].key.hashCode().toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val item = visibleItems[position]
                val tile = (convertView as? Tile) ?: Tile()
                val edge = ((grid.width - grid.paddingLeft - grid.paddingRight - (grid.numColumns - 1) * 2.dp) / grid.numColumns).coerceAtLeast(48.dp)
                tile.layoutParams = AbsListView.LayoutParams(-1, edge)
                tile.image.tag = item.key
                tile.image.setImageBitmap(thumbnails.get(item.key))
                tile.badge.text = when {
                    item.video -> "▶ ${duration(item.duration)}"
                    item.name.endsWith("_YUV.jpg", true) -> "YUV"
                    item.name.endsWith("_JPEG.jpg", true) -> "JPEG"
                    else -> ""
                }
                tile.badge.visibility = if (tile.badge.text.isEmpty()) View.GONE else View.VISIBLE
                tile.check.visibility = if (selectionMode) View.VISIBLE else View.GONE
                tile.check.text = if (item.key in selected) "✓" else "○"
                tile.check.setBackgroundColor(if (item.key in selected) Look.primary else Color.argb(180, 0, 0, 0))
                tile.image.alpha = if (item.key in selected) 0.6f else 1f
                tile.isSelected = item.key in selected
                tile.contentDescription = "${if (item.video) "동영상" else "사진"}, ${date(item.added)}, ${item.name}"
                ViewCompat.setStateDescription(tile, if (selectionMode) { if (item.key in selected) "선택됨" else "선택 안 됨" } else null)
                loadThumbnail(item)
                return tile
            }
        }
        grid.adapter = adapter
        content.addView(grid, FrameLayout.LayoutParams(-1, -1))
        empty = Look.text(this, "사진과 동영상을 불러오고 있습니다…", 15, Look.onDarkMuted).apply {
            gravity = Gravity.CENTER; setPadding(24.dp, 24.dp, 24.dp, 24.dp)
        }
        content.addView(empty, FrameLayout.LayoutParams(-1, -1))
        album.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        selectionBar = Look.row(this).apply { setPadding(16.dp, 4.dp, 16.dp, 8.dp); setBackgroundColor(Look.expertTile) }
        shareSelection = IconButton(this, R.drawable.ic_action_share, "선택한 항목 공유") { share(items.filter { it.key in selected }) }
        deleteSelection = IconButton(this, R.drawable.ic_action_delete, "선택한 항목 삭제") { confirmDelete(items.filter { it.key in selected }) }
        selectionBar.addView(shareSelection, LinearLayout.LayoutParams(0, 56.dp, 1f))
        selectionBar.addView(deleteSelection, LinearLayout.LayoutParams(0, 56.dp, 1f))
        album.addView(selectionBar)
        updateSelection()
    }

    private inner class Tile : FrameLayout(this@GalleryActivity) {
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Look.expertTile) }
        val badge = Look.text(context, "", 11, Look.onDark, bold = true).apply {
            setBackgroundColor(Color.argb(180, 0, 0, 0)); setPadding(4.dp, 2.dp, 4.dp, 2.dp)
        }
        val check = Look.text(context, "", 17, Look.onDark, bold = true).apply { gravity = Gravity.CENTER }
        init {
            addView(image, LayoutParams(-1, -1))
            addView(badge, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(4.dp, 4.dp, 4.dp, 4.dp) })
            addView(check, LayoutParams(28.dp, 28.dp, Gravity.TOP or Gravity.END).apply { setMargins(4.dp, 4.dp, 4.dp, 4.dp) })
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            listOf(image, badge, check).forEach { it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        }
    }

    private fun buildDetail() {
        detail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val header = Look.row(this).apply { setPadding(8.dp, 8.dp, 8.dp, 4.dp) }
        header.addView(IconButton(this, R.drawable.ic_action_back, "앨범으로 돌아가기") { closeDetail() }, LinearLayout.LayoutParams(48.dp, 48.dp))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        detailTitle = Look.text(this, "", 17, Look.onDark, bold = true)
        detailCount = Look.text(this, "", 13, Look.onDarkMuted)
        titles.addView(detailTitle)
        titles.addView(detailCount)
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 8.dp })
        infoButton = IconButton(this, R.drawable.ic_action_info, "사진·동영상 정보 보기") {
            infoVisible = !infoVisible
            detailInfo.visibility = if (infoVisible) View.VISIBLE else View.GONE
            updateInfoButton()
        }
        updateInfoButton()
        header.addView(infoButton, LinearLayout.LayoutParams(48.dp, 48.dp))
        detail.addView(header)
        val frame = FrameLayout(this)
        photo = GalleryImageView(this, ::page)
        frame.addView(photo, FrameLayout.LayoutParams(-1, -1))
        video = VideoView(this).apply { visibility = View.GONE }
        frame.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        videoController = MediaController(this)
        videoController.setAnchorView(video)
        video.setMediaController(videoController)
        play = IconButton(this, R.drawable.ic_action_play, "동영상 재생", filled = true) { startVideo() }
        frame.addView(play, FrameLayout.LayoutParams(64.dp, 64.dp, Gravity.CENTER))
        loading = Look.text(this, "", 15, Look.onDarkMuted).apply { gravity = Gravity.CENTER; setPadding(16.dp, 16.dp, 16.dp, 16.dp) }
        frame.addView(loading, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        detail.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        detailInfo = Look.text(this, "", 13, Look.onDarkMuted).apply {
            setPadding(20.dp, 8.dp, 20.dp, 8.dp); setTextIsSelectable(true); visibility = View.GONE
        }
        detail.addView(detailInfo, LinearLayout.LayoutParams(-1, -2))
        val navigation = Look.row(this).apply { setPadding(12.dp, 0, 12.dp, 0) }
        previous = IconButton(this, R.drawable.ic_action_back, "이전 사진 또는 동영상") { page(-1) }
        next = IconButton(this, R.drawable.ic_action_next, "다음 사진 또는 동영상") { page(1) }
        zoom = IconButton(this, R.drawable.ic_action_zoom_in, "사진 확대") { photo.toggleZoom() }
        photo.onZoomChanged = { enlarged ->
            zoom.setIcon(if (enlarged) R.drawable.ic_action_zoom_out else R.drawable.ic_action_zoom_in,
                if (enlarged) "사진을 원래 크기로" else "사진 확대")
            zoom.isSelected = enlarged
            ViewCompat.setStateDescription(zoom, if (enlarged) "확대됨" else "원래 크기")
        }
        navigation.addView(previous, LinearLayout.LayoutParams(0, 48.dp, 1f))
        navigation.addView(zoom, LinearLayout.LayoutParams(0, 48.dp, 1f))
        navigation.addView(next, LinearLayout.LayoutParams(0, 48.dp, 1f))
        detail.addView(navigation)
        val actions = Look.row(this).apply { setPadding(16.dp, 0, 16.dp, 8.dp); setBackgroundColor(Look.expertTile) }
        detailShare = IconButton(this, R.drawable.ic_action_share, "사진 또는 동영상 공유") { currentItem()?.let { share(listOf(it)) } }
        detailDelete = IconButton(this, R.drawable.ic_action_delete, "사진 또는 동영상 삭제") { currentItem()?.let { confirmDelete(listOf(it)) } }
        actions.addView(detailShare, LinearLayout.LayoutParams(0, 56.dp, 1f))
        actions.addView(detailDelete, LinearLayout.LayoutParams(0, 56.dp, 1f))
        detail.addView(actions)
    }

    private fun updateInfoButton() {
        infoButton.isSelected = infoVisible
        infoButton.setIcon(R.drawable.ic_action_info, if (infoVisible) "사진·동영상 정보 숨기기" else "사진·동영상 정보 보기")
        ViewCompat.setStateDescription(infoButton, if (infoVisible) "펼쳐짐" else "접힘")
    }

    private fun applyFilter() {
        visibleItems = items.filter { filter == 0 || (filter == 1 && !it.video) || (filter == 2 && it.video) }
        filterButton.text = listOf("전체 ▾", "사진 ▾", "동영상 ▾")[filter]
        filterButton.contentDescription = "미디어 종류, ${listOf("전체", "사진", "동영상")[filter]}"
        adapter.notifyDataSetChanged()
        empty.text = if (items.isEmpty()) "아직 사진이나 동영상이 없습니다.\n카메라에서 촬영하면 여기에 표시됩니다." else "${if (filter == 1) "사진" else "동영상"}이 없습니다."
        empty.visibility = if (visibleItems.isEmpty()) View.VISIBLE else View.GONE
        if (restoreGrid) {
            grid.setSelectionFromTop(gridPosition.coerceIn(0, (visibleItems.size - 1).coerceAtLeast(0)), gridTop)
            restoreGrid = false
        }
        updateSelection()
    }

    private fun toggleSelection(item: Item) {
        if (deleteBusy) return
        if (!selected.add(item.key)) selected.remove(item.key)
        updateSelection()
    }

    private fun updateSelection() {
        count.text = if (selectionMode) "${selected.size}개 선택됨" else "사진 ${items.count { !it.video }}장 · 동영상 ${items.count { it.video }}개"
        selectButton.text = if (selectionMode) "취소" else "선택"
        selectButton.contentDescription = if (selectionMode) "선택 취소" else "사진·동영상 선택"
        selectButton.isEnabled = !deleteBusy && (selectionMode || items.isNotEmpty())
        filterButton.isEnabled = !deleteBusy
        selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        shareSelection.isEnabled = selected.isNotEmpty() && !deleteBusy
        deleteSelection.isEnabled = selected.isNotEmpty() && !deleteBusy
        if (::detailDelete.isInitialized) { detailDelete.isEnabled = !deleteBusy; detailShare.isEnabled = !deleteBusy }
        adapter.notifyDataSetChanged()
    }

    private fun rememberGrid() {
        gridPosition = grid.firstVisiblePosition
        gridTop = grid.getChildAt(0)?.top ?: 0
    }

    private fun openDetail(item: Item) {
        if (deleteBusy) return
        if (currentUri == null) rememberGrid()
        if (currentUri != item.key) videoPosition = 0
        currentUri = item.key
        currentPosition = visibleItems.indexOfFirst { it.key == item.key }.coerceAtLeast(0)
        album.visibility = View.GONE
        detail.visibility = View.VISIBLE
        showCurrent()
    }

    private fun closeDetail() {
        stopVideo()
        imageGeneration++
        currentUri = null
        videoPosition = 0
        photo.setImageBitmap(null)
        detail.visibility = View.GONE
        album.visibility = View.VISIBLE
        grid.setSelectionFromTop(gridPosition, gridTop)
    }

    private fun currentItem() = visibleItems.firstOrNull { it.key == currentUri }

    private fun page(delta: Int) {
        val target = currentPosition + delta
        if (target in visibleItems.indices) { videoPosition = 0; openDetail(visibleItems[target]) }
    }

    private fun showCurrent() {
        val item = currentItem() ?: return
        stopVideo()
        val generation = ++imageGeneration
        detailTitle.text = date(item.added)
        detailCount.text = "${currentPosition + 1} / ${visibleItems.size}"
        detailInfo.text = buildString {
            append(item.name)
            append("\n${date(item.added)} ${DateFormat.getTimeFormat(this@GalleryActivity).format(Date(item.added))}")
            if (item.width > 0 && item.height > 0) append("\n${item.width} × ${item.height}")
            if (item.bytes > 0) append(" · ${Formatter.formatShortFileSize(this@GalleryActivity, item.bytes)}")
            if (item.video) append(" · ${duration(item.duration)}")
        }
        photo.contentDescription = "${item.name}, ${if (item.video) "동영상 미리보기" else "사진. 두 번 누르거나 두 손가락으로 확대할 수 있습니다."}"
        previous.isEnabled = currentPosition > 0
        next.isEnabled = currentPosition < visibleItems.lastIndex
        zoom.visibility = if (item.video) View.INVISIBLE else View.VISIBLE
        photo.visibility = View.VISIBLE
        photo.setImageBitmap(thumbnails.get(item.key))
        play.visibility = if (item.video) View.VISIBLE else View.GONE
        loading.text = if (item.video) "" else "불러오는 중…"
        loading.visibility = if (item.video) View.GONE else View.VISIBLE
        imageIo.execute {
            val result = runCatching { if (item.video) thumbnail(item) else decodePhoto(item) }
            main.post {
                if (!isDestroyed && currentUri == item.key && imageGeneration == generation) {
                    result.fold({ bitmap ->
                        photo.setImageBitmap(bitmap)
                        loading.visibility = if (bitmap != null || item.video) View.GONE else View.VISIBLE
                        if (bitmap == null && !item.video) loading.text = "사진을 불러오지 못했습니다."
                    }, { loading.text = "미디어를 불러오지 못했습니다."; loading.visibility = View.VISIBLE })
                }
            }
        }
    }

    private fun startVideo() {
        val item = currentItem()?.takeIf { it.video } ?: return
        if (!resumed) return
        val key = item.key
        play.visibility = View.GONE
        loading.text = "동영상을 불러오는 중…"
        loading.visibility = View.VISIBLE
        video.visibility = View.VISIBLE
        video.setOnPreparedListener {
            if (resumed && currentUri == key) {
                loading.visibility = View.GONE
                photo.visibility = View.GONE
                if (videoPosition > 0) video.seekTo(videoPosition)
                video.start()
                videoController.show(3000)
            } else stopVideo()
        }
        video.setOnCompletionListener {
            videoPosition = 0
            stopVideo()
            photo.visibility = View.VISIBLE
            play.setIcon(R.drawable.ic_action_play, "동영상 다시 재생")
            play.visibility = View.VISIBLE
        }
        video.setOnErrorListener { _, _, _ ->
            loading.text = "이 동영상을 재생하지 못했습니다."
            loading.visibility = View.VISIBLE
            play.visibility = View.VISIBLE
            true
        }
        video.setVideoURI(item.uri)
    }

    private fun stopVideo() {
        videoController.hide()
        video.stopPlayback()
        video.visibility = View.GONE
        play.setIcon(R.drawable.ic_action_play, "동영상 재생")
    }

    private fun share(media: List<Item>) {
        if (media.isEmpty()) return
        val intent = Intent(if (media.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (media.all { it.video }) "video/mp4" else if (media.none { it.video }) "image/jpeg" else "*/*"
            if (media.size == 1) putExtra(Intent.EXTRA_STREAM, media.single().uri)
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(media.map { it.uri }))
            clipData = ClipData.newUri(contentResolver, "HALCamera", media.first().uri).also { clip ->
                media.drop(1).forEach { clip.addItem(ClipData.Item(it.uri)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, "공유")) }.onFailure { toast("공유할 수 있는 앱이 없습니다.") }
    }

    private fun confirmDelete(media: List<Item>) {
        if (media.isEmpty() || deleteBusy) return
        if (Build.VERSION.SDK_INT >= 30) {
            pendingDelete = ArrayList(media.map { it.key })
            deleteBySystem = true
            deleteBusy = true
            updateSelection()
            runCatching {
                val request = MediaStore.createDeleteRequest(contentResolver, media.map { it.uri })
                awaitingDeletePermission = true
                deletePermission.launch(IntentSenderRequest.Builder(request.intentSender).build())
            }.onFailure {
                deleteBusy = false
                awaitingDeletePermission = false
                pendingDelete.clear()
                updateSelection()
                toast("삭제 요청을 열지 못했습니다.")
            }
        } else {
            AlertDialog.Builder(this).setTitle("${media.size}개를 삭제할까요?")
                .setMessage(media.take(4).joinToString("\n") { it.name } + if (media.size > 4) "\n외 ${media.size - 4}개" else "")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제") { _, _ ->
                    pendingDelete = ArrayList(media.map { it.key })
                    deleteBySystem = false
                    failedDeletes = 0
                    deleteBusy = true
                    updateSelection()
                    deleteLegacyNext()
                }.show()
        }
    }

    private fun deleteLegacyNext() {
        val key = pendingDelete.firstOrNull() ?: return finishDeletion()
        queryIo.execute {
            val result = runCatching { contentResolver.delete(Uri.parse(key), null, null) }
            main.post {
                if (isDestroyed) return@post
                val error = result.exceptionOrNull()
                if (Build.VERSION.SDK_INT == 29 && error is RecoverableSecurityException) {
                    runCatching {
                        awaitingDeletePermission = true
                        deletePermission.launch(IntentSenderRequest.Builder(error.userAction.actionIntent.intentSender).build())
                    }.onFailure {
                        awaitingDeletePermission = false
                        failedDeletes++
                        pendingDelete.remove(key)
                        deleteLegacyNext()
                    }
                } else {
                    if (error != null) failedDeletes++
                    pendingDelete.remove(key)
                    deleteLegacyNext()
                }
            }
        }
    }

    private fun finishDeletion() {
        deleteBusy = false
        pendingDelete.clear()
        selectionMode = false
        selected.clear()
        updateSelection()
        if (failedDeletes > 0) toast("${failedDeletes}개 파일을 삭제하지 못했습니다.")
        failedDeletes = 0
        loadMedia()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        contentResolver.registerContentObserver(Uri.parse("content://media"), true, observer)
        loadMedia()
    }

    override fun onPause() {
        resumed = false
        if (video.visibility == View.VISIBLE) {
            videoPosition = runCatching { video.currentPosition }.getOrDefault(0)
            stopVideo()
            photo.visibility = View.VISIBLE
            play.visibility = View.VISIBLE
        }
        contentResolver.unregisterContentObserver(observer)
        main.removeCallbacks(refresh)
        super.onPause()
    }

    private fun loadMedia() {
        if (isDestroyed || queryIo.isShutdown) return
        val generation = ++loadGeneration
        queryIo.execute {
            val result = runCatching { (query(false) + query(true)).sortedWith(compareByDescending<Item> { it.added }.thenByDescending { it.name }.thenBy { it.key }) }
            main.post {
                if (isDestroyed || generation != loadGeneration) return@post
                result.fold({ media ->
                    if (!restoreGrid && currentUri == null) rememberGrid()
                    items.clear()
                    items.addAll(media)
                    selected.retainAll(items.map { it.key }.toSet())
                    restoreGrid = true
                    applyFilter()
                    if (currentUri != null) {
                        val index = visibleItems.indexOfFirst { it.key == currentUri }
                        if (index >= 0) {
                            currentPosition = index
                            if (detail.visibility != View.VISIBLE) openDetail(visibleItems[index])
                            else { detailCount.text = "${index + 1} / ${visibleItems.size}"; next.isEnabled = index < visibleItems.lastIndex; previous.isEnabled = index > 0 }
                        } else if (visibleItems.isNotEmpty()) {
                            videoPosition = 0
                            openDetail(visibleItems[currentPosition.coerceAtMost(visibleItems.lastIndex)])
                        } else closeDetail()
                    }
                }, { empty.text = "앨범을 불러오지 못했습니다.\n잠시 후 다시 열어 주세요."; empty.visibility = View.VISIBLE })
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun query(video: Boolean): List<Item> {
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT)
        if (video) projection += MediaStore.Video.VideoColumns.DURATION
        val column = if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val selection = if (Build.VERSION.SDK_INT >= 29) "$column = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0" else "$column LIKE ?"
        val path = if (Build.VERSION.SDK_INT >= 29) "DCIM/HALCamera/" else "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/HALCamera/%"
        return buildList {
            contentResolver.query(collection, projection.toTypedArray(), selection, arrayOf(path), "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
                while (cursor.moveToNext()) add(Item(ContentUris.withAppendedId(collection, cursor.getLong(0)), cursor.getString(1).orEmpty(), video,
                    cursor.getLong(2) * 1000, if (video) cursor.getLong(6) else 0, cursor.getLong(3), cursor.getInt(4), cursor.getInt(5)))
            }
        }
    }

    private fun loadThumbnail(item: Item) {
        if (thumbnailIo.isShutdown || item.key in failedThumbnails || thumbnails.get(item.key) != null || !pendingThumbnails.add(item.key)) return
        try {
            thumbnailIo.execute {
                val bitmap = runCatching { thumbnail(item) }.getOrNull()
                main.post {
                    pendingThumbnails.remove(item.key)
                    if (isDestroyed) return@post
                    if (bitmap != null) thumbnails.put(item.key, bitmap) else failedThumbnails.add(item.key)
                    for (index in 0 until grid.childCount) {
                        val tile = grid.getChildAt(index) as? Tile ?: continue
                        if (tile.image.tag == item.key) tile.image.setImageBitmap(bitmap)
                        // Backfill visible requests rejected when rapid scrolling filled the bounded queue.
                        visibleItems.getOrNull(grid.firstVisiblePosition + index)?.let(::loadThumbnail)
                    }
                }
            }
        } catch (_: RejectedExecutionException) { pendingThumbnails.remove(item.key) }
    }

    @Suppress("DEPRECATION")
    private fun thumbnail(item: Item): Bitmap? = if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(item.uri, Size(400, 400), null)
    else if (item.video) MediaStore.Video.Thumbnails.getThumbnail(contentResolver, ContentUris.parseId(item.uri), MediaStore.Video.Thumbnails.MINI_KIND, null)
    else MediaStore.Images.Thumbnails.getThumbnail(contentResolver, ContentUris.parseId(item.uri), MediaStore.Images.Thumbnails.MINI_KIND, null)

    private fun decodePhoto(item: Item): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) return ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, item.uri)) { decoder, info, _ ->
            val sample = (maxOf(info.size.width, info.size.height) / 2048f).coerceAtLeast(1f)
            decoder.setTargetSize((info.size.width / sample).toInt().coerceAtLeast(1), (info.size.height / sample).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        val bitmap = contentResolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val orientation = contentResolver.openInputStream(item.uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
            }
        }
        return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (currentUri == null) rememberGrid()
        outState.putInt("filter", filter)
        outState.putBoolean("selectionMode", selectionMode)
        outState.putStringArrayList("selected", ArrayList(selected))
        outState.putString("currentUri", currentUri)
        outState.putInt("currentPosition", currentPosition)
        outState.putInt("gridPosition", gridPosition)
        outState.putInt("gridTop", gridTop)
        outState.putInt("videoPosition", if (video.visibility == View.VISIBLE) runCatching { video.currentPosition }.getOrDefault(videoPosition) else videoPosition)
        outState.putStringArrayList("pendingDelete", pendingDelete)
        outState.putBoolean("deleteBySystem", deleteBySystem)
        outState.putBoolean("awaitingDeletePermission", awaitingDeletePermission)
        outState.putInt("failedDeletes", failedDeletes)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        stopVideo()
        main.removeCallbacks(refresh)
        queryIo.shutdownNow()
        imageIo.shutdownNow()
        thumbnailIo.shutdownNow()
        thumbnails.evictAll()
        super.onDestroy()
    }

    private fun button(label: String, description: String = label, action: () -> Unit) = Button(this).apply {
        text = label
        contentDescription = description
        isAllCaps = false
        textSize = 15f
        setTextColor(Look.onDark)
        val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        background = attrs.getDrawable(0)
        attrs.recycle()
        minWidth = 48.dp
        minimumWidth = 48.dp
        minHeight = 48.dp
        minimumHeight = 48.dp
        setPadding(12.dp, 0, 12.dp, 0)
        setOnClickListener { action() }
    }

    private fun date(timestamp: Long) = DateFormat.getMediumDateFormat(this).format(Date(timestamp))
    private fun duration(milliseconds: Long): String {
        val seconds = (milliseconds / 1000).coerceAtLeast(0)
        return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private val Int.dp: Int get() = Look.dp(this@GalleryActivity, this)
}
