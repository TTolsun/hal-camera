package dev.halcamera

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import dev.halcamera.camera.CameraProbeEntry
import dev.halcamera.camera.CameraProbeReader
import dev.halcamera.camera.CameraProbeSnapshot
import dev.halcamera.camera.CameraProbeText
import dev.halcamera.camera.ProbeFormat
import dev.halcamera.camera.ProbeRow
import dev.halcamera.camera.ProbeSection
import dev.halcamera.ui.IconButton
import dev.halcamera.ui.Look
import dev.halcamera.ui.showSelectionPopup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * PROBE: the static capability report of [CameraProbeReader], one camera at a time on screen and every camera
 * in the exported file. Nothing here opens a camera, so the screen can be reached from LIVE without stopping
 * the preview.
 *
 * Layout, top to bottom: the scrolling report, then a filter panel pinned above the keyboard. The panel holds
 * the hit list and the text box, so a hit can be tapped, read, and the next hit tapped without scrolling back
 * to a box at the top of a fifteen-page report. Typing never rebuilds the box, so the focus stays.
 */
class CameraProbeActivity : ComponentActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private var snapshot: CameraProbeSnapshot? = null
    private var error: String? = null
    private var cameraKey: String? = null
    private var busy = false
    private var listScrollY = 0
    private var lastRenderFull = false
    /** Folded section titles; the long ones start folded so STREAMS is a swipe away, not fifteen. */
    private val collapsed = HashSet<String>().apply { addAll(listOf("All characteristics", "Mandatory stream combinations", "Request · result keys", "Session keys")) }
    private var query = ""
    /** The sections of the current render, in screen order, for the filter. */
    private var shown: List<ProbeSection> = emptyList()
    /** A hit the user tapped; the next render scrolls to it once the unfolded section is laid out. */
    private var pendingTarget: Hit? = null
    /** The hit the user last jumped to: marked in the list and tinted in the report so the eye finds it. */
    private var activeHit: Hit? = null
    /** Per section title: its card, its text view when unfolded, and the character offset of every printed line. */
    private val sectionViews = HashMap<String, Placed>()
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var panel: LinearLayout
    private lateinit var hitList: LinearLayout
    private lateinit var hitHeader: TextView
    private lateinit var hitScroll: ScrollView
    private lateinit var filterBox: EditText

    /** One printed line that contains the query. [row] -1 is the section title; [line] -1 the key, else a value line. */
    private data class Hit(val section: String, val row: Int, val line: Int, val text: String)
    private class Placed(val card: View, val text: TextView?, val lines: List<List<Int>>)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraKey = savedInstanceState?.getString("camera") ?: intent.getStringExtra(EXTRA_CAMERA_ID)
        savedInstanceState?.getStringArrayList("collapsed")?.let { collapsed.clear(); collapsed += it }
        query = savedInstanceState?.getString("query").orEmpty()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Look.expertTile) }
        scroll = ScrollView(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        column.addView(Look.text(this, "Probe", 24, Look.onDark, bold = true), lp())
        column.addView(Look.text(this, "CameraCharacteristics · 정적 사양 조회", 14, Look.onDarkMuted), lp())
        column.addView(IconButton(this, R.drawable.ic_action_back, "돌아가기") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { topMargin = dp(10) })
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(body)
        scroll.addView(column)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(filterPanel(), LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            // The keyboard is an inset like the navigation bar: the panel has to sit above whichever is taller.
            val ime = if (Build.VERSION.SDK_INT >= 30) WindowInsetsCompat.Type.ime() else 0   // below 30 adjustResize does it
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or ime)
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        // Back peels the filter off first: after a jump the report is exactly where the user wants to be, so
        // leaving PROBE for the benchmark card on the first press would throw that position away.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    query.isNotEmpty() -> { hideKeyboard(); filterBox.setText("") }
                    else -> finish()
                }
            }
        })
        reload()
    }

    override fun onDestroy() { io.shutdown(); super.onDestroy() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("camera", cameraKey)
        outState.putStringArrayList("collapsed", ArrayList(collapsed))
        outState.putString("query", query)
        super.onSaveInstanceState(outState)
    }

    // ---- filter panel ----

    /**
     * Hit list above, text box below, both pinned to the bottom edge. The list is capped at a third of the
     * screen so the report stays visible behind it; it scrolls on its own when a query hits more than fits.
     */
    private fun filterPanel(): View {
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Look.expertTile)
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        hitHeader = Look.text(this, "", 12, Look.onDarkMuted, bold = true)
        hitList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hitScroll = CappedScrollView(this, maxFraction = 0.34f).apply {
            addView(hitList)
            background = Look.cardBackground(this@CameraProbeActivity, Look.expertTile2, Look.expertTile3)
        }
        val hits = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; tag = "hits" }
        hits.addView(hitHeader, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        hits.addView(hitScroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        panel.addView(hits)

        val bar = Look.row(this)
        filterBox = EditText(this).apply {
            setText(query)
            // Examples every Camera2 device answers: a format section, a size fragment (matches 1920x1080 as well as
            // 1440x1080, so a camera without exact 1080p still hits), and the mark for an absent value.
            hint = "예: JPEG, 1080, ✗"
            setHintTextColor(Look.onDarkMuted)
            setTextColor(Look.onDark)
            typeface = Look.mono
            textSize = 14f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = Look.cardBackground(this@CameraProbeActivity, Look.expertTile2, Look.expertTile3)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            contentDescription = "필터 단어. 그 단어가 들어간 줄만 위 목록에 나오고, 누르면 그 줄로 이동합니다"
            // Search on the keyboard just closes it: the list is already live.
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false }
            doAfterTextChanged { text ->
                val next = text?.toString()?.trim().orEmpty()
                removeCallbacks(applyFilter)
                if (next == query) return@doAfterTextChanged
                query = next
                // A pause, not a keystroke: a fast typist would otherwise rebuild twenty cards per letter.
                postDelayed(applyFilter, 200)
            }
        }
        bar.addView(filterBox, LinearLayout.LayoutParams(0, dp(48), 1f))
        bar.addView(IconButton(this, R.drawable.ic_action_close, "필터 지우기") { filterBox.setText("") }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        panel.addView(bar)
        return panel
    }

    private val applyFilter = Runnable { if (!busy) render() }

    /** Every printed line of [shown] that contains [query], in screen order; section titles count too. */
    private fun hits(): List<Hit> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<Hit>()
        shown.forEach { section ->
            if (section.title.contains(query, ignoreCase = true)) out += Hit(section.title, -1, -1, section.title)
            section.rows.forEachIndexed { i, row ->
                if (row.key.contains(query, ignoreCase = true)) out += Hit(section.title, i, -1, row.key)
                row.value.split('\n').forEachIndexed { j, line ->
                    if (line.contains(query, ignoreCase = true)) out += Hit(section.title, i, j, line)
                }
            }
        }
        return out
    }

    /** Rebuilds the hit list for the current query; hidden when there is no query. */
    private fun renderHits() {
        val hitsBox = panel.findViewWithTag<View>("hits")
        val keepY = hitScroll.scrollY
        hitList.removeAllViews()
        if (query.isEmpty() || snapshot == null || busy) { hitsBox.visibility = View.GONE; return }
        hitsBox.visibility = View.VISIBLE
        val all = hits()
        val camera = snapshot?.camera(cameraKey.orEmpty())?.cameraId ?: "—"
        hitHeader.text = "${all.size}개 일치 · Camera $camera" + if (all.size > MAX_HITS) " · 앞 ${MAX_HITS}개 표시" else ""
        if (all.isEmpty()) {
            hitList.addView(Look.text(this, "일치하는 줄이 없습니다. 다른 카메라를 고르거나 단어를 줄여 보세요.", 12, Look.onDarkMuted).apply { setPadding(dp(12), dp(10), dp(12), dp(10)) })
            return
        }
        val rowsByTitle = shown.associate { it.title to it.rows }
        var group: String? = null
        all.take(MAX_HITS).forEach { hit ->
            val key = rowsByTitle[hit.section]?.getOrNull(hit.row)?.key.orEmpty()
            val where = when {
                hit.row < 0 -> "섹션 제목"
                hit.line < 0 || key == hit.text -> hit.section
                else -> "${hit.section} › $key"
            }
            // A hundred hits under one key would repeat the same header a hundred times.
            if (where != group) {
                group = where
                hitList.addView(Look.text(this, where, 11, Look.onDarkMuted, bold = true).apply { setPadding(dp(12), dp(10), dp(12), dp(2)) })
            }
            val label = SpannableStringBuilder().append(hit.text)
            highlight(label, 0)
            // A key hit alone ("1920x1080") says little; its value is the reason to tap it. A list value shows its
            // first entry and how many more follow, so a key that owns a list does not read as a key with no value.
            val value = rowsByTitle[hit.section]?.getOrNull(hit.row)?.value
            if (hit.line < 0 && hit.row >= 0 && value != null) {
                val lines = value.split('\n')
                val more = if (lines.size > 1) " · 외 ${lines.size - 1}줄" else ""
                label.append("  ${lines.first()}$more", ForegroundColorSpan(Look.onDarkMuted), 0)
            }
            val active = hit == activeHit
            val item = Look.text(this, label, 12, Look.onDark, mono = true).apply {
                minHeight = dp(40)
                isClickable = true; isFocusable = true
                contentDescription = "${hit.section}의 ${hit.text} 줄로 이동${if (active) ", 현재 위치" else ""}"
                setPadding(dp(12), dp(6), dp(12), dp(6))
                if (active) setBackgroundColor(Look.expertTile3)
                setOnClickListener { jump(hit) }
            }
            hitList.addView(item)
        }
        hitScroll.post { hitScroll.scrollTo(0, keepY) }
    }

    /** Unfolds the hit's section if needed and scrolls the report so the line sits just under the top edge. */
    private fun jump(hit: Hit) {
        hideKeyboard()
        activeHit = hit
        collapsed -= hit.section
        pendingTarget = hit
        render()
    }

    private fun scrollTo(target: Hit): Boolean {
        val placed = sectionViews[target.section] ?: return false
        var y: Int
        var v: View
        if (target.row < 0) { y = 0; v = placed.card }
        else {
            val text = placed.text ?: return false
            val layout = text.layout ?: return false
            val offset = placed.lines.getOrNull(target.row)?.getOrNull(target.line + 1) ?: return false
            y = layout.getLineTop(layout.getLineForOffset(offset)) + text.paddingTop
            v = text
        }
        while (v !== scroll.getChildAt(0)) { y += v.top; v = v.parent as View }
        // A quarter of the visible report above the line, so the rows before it give the section some context.
        scroll.smoothScrollTo(0, (y - (scroll.height / 4).coerceIn(dp(24), dp(120))).coerceAtLeast(0))
        return true
    }

    private fun hideKeyboard() {
        getSystemService(Context.INPUT_METHOD_SERVICE).let { it as InputMethodManager }.hideSoftInputFromWindow(filterBox.windowToken, 0)
    }

    // ---- report ----

    private fun reload() {
        val manager = getSystemService(CameraManager::class.java)
        val display = displayRows()
        work({ CameraProbeReader(manager, display).read() }) { result ->
            snapshot = result
            error = null
            if (result.camera(cameraKey.orEmpty()) == null) cameraKey = result.cameras.firstOrNull()?.key
            render()
        }
    }

    /** What the reference probes list under "screen": the preview grade of ProfileCompatibility depends on it. */
    private fun displayRows(): List<ProbeRow> {
        val metrics = resources.displayMetrics
        val (w, h, refresh) = if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), display?.refreshRate ?: 0f)
        } else {
            @Suppress("DEPRECATION") val d = windowManager.defaultDisplay
            val real = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") d.getRealMetrics(it) }
            Triple(real.widthPixels, real.heightPixels, d.refreshRate)
        }
        val inches = Math.hypot((w / metrics.xdpi).toDouble(), (h / metrics.ydpi).toDouble())
        return listOf(
            ProbeRow("Display", "${w}x$h px · ${ProbeFormat.aspect(w, h)} · ${String.format(Locale.US, "%.1f", inches)}\""),
            ProbeRow("Density", "${metrics.densityDpi} dpi · ${String.format(Locale.US, "%.2f", metrics.density)}x"),
            ProbeRow("Refresh rate", String.format(Locale.US, "%.0f Hz", refresh))
        )
    }

    private fun render() {
        // The loading screen is short, so its scroll position is not the one to come back to.
        if (lastRenderFull) listScrollY = scroll.scrollY
        lastRenderFull = false
        body.removeAllViews()
        sectionViews.clear()
        shown = emptyList()
        if (busy) { text("카메라 사양을 읽고 있습니다."); renderHits(); return }
        error?.let { text("읽지 못했습니다: $it"); button("다시 읽기") { reload() }; renderHits(); return }
        val snap = snapshot ?: return
        val cameras = snap.cameras
        val current = snap.camera(cameraKey.orEmpty()) ?: cameras.firstOrNull()
        if (cameras.isNotEmpty()) {
            button("Camera · ${current?.title ?: "—"} ▾") { anchor ->
                showSelectionPopup(anchor, cameras.map { it.title }, cameras.indexOf(current)) { cameraKey = cameras[it].key; render() }
            }
        }
        // Short labels: three 15sp buttons share one row, and "JSON 공유" was cut to "JSON" on a 360dp phone.
        val exports = Look.row(this)
        exports.addView(Look.ghostButton(this, "TXT", dark = true) { export(snap, "txt") }.apply { contentDescription = "모든 카메라를 TXT 파일로 공유" }, LinearLayout.LayoutParams(0, dp(48), 1f))
        exports.addView(Look.ghostButton(this, "JSON", dark = true) { export(snap, "json") }.apply { contentDescription = "모든 카메라를 JSON 파일로 공유" }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        exports.addView(Look.ghostButton(this, "복사", dark = true) { copy(snap, current) }.apply { contentDescription = "현재 카메라를 클립보드에 복사" }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        body.addView(exports, lp())
        text("TXT·JSON: 전체 ${cameras.size}개 · 복사: 현재 카메라", 12)
        val sections = ArrayList<ProbeSection>()
        sections += ProbeSection("Device", snap.device)
        if (current != null) sections += current.sections
        if (snap.errors.isNotEmpty()) sections += ProbeSection("Errors", snap.errors.map { ProbeRow("!", it) })
        shown = sections
        if (current == null) text("공개된 카메라가 없습니다.")
        sections.forEach { section(it.title, it.rows) }
        renderHits()
        lastRenderFull = true
        val target = pendingTarget
        pendingTarget = null
        scroll.post { if (target == null || !scrollTo(target)) scroll.scrollTo(0, listScrollY) }
    }

    /**
     * One card per section, folded or unfolded by its title. The raw dump and the mandatory-combination prose
     * start folded: they are the complete record for the export, not what someone scrolls to on a phone.
     */
    private fun section(title: String, rows: List<ProbeRow>) {
        val open = title !in collapsed
        val card = Look.card(this, dark = true)
        val header = Look.text(this, "${if (open) "▾" else "▸"} $title (${rows.size})", 14, Look.onDarkMuted, bold = true).apply {
            minHeight = dp(32)
            isClickable = true; isFocusable = true
            contentDescription = "$title 섹션 ${if (open) "접기" else "펼치기"}"
            setOnClickListener { if (open) collapsed += title else collapsed -= title; render() }
        }
        card.addView(header)
        var view: TextView? = null
        var lines: List<List<Int>> = emptyList()
        if (open) {
            tableTitle = title
            val (content, offsets) = table(rows)
            lines = offsets
            view = Look.text(this, content, 12, Look.onDark, mono = true).apply { setTextIsSelectable(true) }
            card.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        body.addView(card, lp())
        sectionViews[title] = Placed(card, view, lines)
    }

    /**
     * The same two columns as [CameraProbeText.section], but a value that wraps on a phone continues under the
     * value column rather than under the key: each row is a paragraph with a hanging indent of the key column.
     * Also returns, per row, the character offset of the key and of each value line, so a filter hit can be
     * scrolled to.
     */
    private fun table(rows: List<ProbeRow>): Pair<CharSequence, List<List<Int>>> {
        if (rows.isEmpty()) return "(none)" to emptyList()
        val width = rows.maxOf { it.key.length }.coerceAtMost(CameraProbeText.MAX_KEY_WIDTH)
        val paint = TextPaint().apply { typeface = Look.mono; textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics) }
        val indent = paint.measureText(" ".repeat(width + 2)).toInt()
        val listIndent = paint.measureText("  ").toInt()
        val out = SpannableStringBuilder()
        val offsets = ArrayList<List<Int>>()
        rows.forEachIndexed { i, row ->
            if (i > 0) out.append('\n')
            // A key wider than the column, or a list value, takes its own line: a list squeezed into the value
            // column on a phone wraps every entry, so it hangs under the key with a shallow indent instead.
            val lines = row.value.split('\n')
            val ownLine = row.key.length > width || lines.size > 1
            val keyStart = out.length
            val rowOffsets = ArrayList<Int>().apply { add(keyStart) }
            out.append(if (ownLine) row.key else row.key.padEnd(width + 2), ForegroundColorSpan(Look.onDarkMuted), 0)
            if (isActive(i, -1)) out.setSpan(BackgroundColorSpan(ACTIVE_LINE), keyStart, out.length, 0)
            if (ownLine) out.append('\n')
            lines.forEachIndexed { j, line ->
                if (j > 0) out.append('\n')
                // A paragraph span has to start at the paragraph, which for a same-line value is the key itself.
                val start = if (j == 0 && !ownLine) keyStart else out.length
                rowOffsets += out.length
                val lineStart = out.length
                out.append(line)
                if (isActive(i, j)) out.setSpan(BackgroundColorSpan(ACTIVE_LINE), lineStart, out.length, 0)
                val span = if (ownLine) LeadingMarginSpan.Standard(listIndent, listIndent * 2) else LeadingMarginSpan.Standard(0, indent)
                out.setSpan(span, start, out.length, 0)
            }
            offsets += rowOffsets
        }
        highlight(out, 0)
        return out to offsets
    }

    /** True when this printed line is the one the user jumped to; [tableTitle] is set by [section] before [table] runs. */
    private fun isActive(row: Int, line: Int): Boolean {
        val a = activeHit ?: return false
        return a.section == tableTitle && a.row == row && a.line == line
    }
    private var tableTitle = ""

    /** Marks every occurrence of the query from [from] on, so the eye lands on it after the scroll. */
    private fun highlight(text: SpannableStringBuilder, from: Int) {
        if (query.isEmpty()) return
        val plain = text.toString()
        var at = plain.indexOf(query, from, ignoreCase = true)
        while (at >= 0) {
            text.setSpan(BackgroundColorSpan(HIGHLIGHT), at, at + query.length, 0)
            text.setSpan(ForegroundColorSpan(Color.WHITE), at, at + query.length, 0)
            at = plain.indexOf(query, at + query.length, ignoreCase = true)
        }
    }

    // ---- export ----

    /** The camera on screen as text, to the clipboard: the quickest way into a chat or an issue. */
    private fun copy(snap: CameraProbeSnapshot, camera: CameraProbeEntry?) {
        val text = CameraProbeText.render(snap, camera?.key)
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("camera-probe", text))
        if (Build.VERSION.SDK_INT < 33) message("클립보드에 복사했습니다.")
    }

    private fun export(snap: CameraProbeSnapshot, kind: String) {
        work({
            val dir = File(cacheDir, "benchmark-exports").apply { check(isDirectory || mkdirs()) }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val model = Build.MODEL.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val file = File(dir, "camera-probe-$model-$stamp.$kind")
            val content = if (kind == "json") (json(snap.toJsonMap()) as JSONObject).toString(2) else CameraProbeText.render(snap)
            file.writeText(content, Charsets.UTF_8)
            file
        }, showBusy = false, fail = { message(it.message ?: "내보내기에 실패했습니다.") }) { share(it, if (kind == "json") "application/json" else "text/plain") }
    }

    /** org.json stays at the file boundary; the snapshot itself is a Map. */
    private fun json(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { obj -> value.forEach { (k, v) -> obj.put(k.toString(), json(v)) } }
        is List<*> -> JSONArray().also { arr -> value.forEach { arr.put(json(it)) } }
        else -> value
    }

    private fun share(file: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                clipData = ClipData.newRawUri("camera-probe", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "카메라 사양 내보내기"))
        } catch (e: Exception) { message(e.message ?: "내보내기에 실패했습니다.") }
    }

    /**
     * One serial worker. [showBusy] replaces the screen with the loading line while the snapshot is read; an
     * export keeps the list on screen, because the chooser opens over it and a flash to "reading" would be a lie.
     */
    private fun <T> work(
        task: () -> T,
        showBusy: Boolean = true,
        fail: (Throwable) -> Unit = { error = it.message ?: it.javaClass.simpleName; render() },
        done: (T) -> Unit
    ) {
        if (busy) { message("아직 읽는 중입니다."); return }
        busy = true
        if (showBusy) render()
        io.execute {
            val result = runCatching(task)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                result.fold(done, fail)
            }
        }
    }

    // ---- widgets ----

    /** A ScrollView that grows with its content up to a fraction of the screen, then scrolls. */
    private class CappedScrollView(context: Context, private val maxFraction: Float) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cap = (resources.displayMetrics.heightPixels * maxFraction).toInt()
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
        }
    }

    private fun text(value: String, size: Int = 14, bold: Boolean = false) {
        body.addView(Look.text(this, value, size, if (size < 14) Look.onDarkMuted else Look.onDark, bold = bold), lp())
    }
    private fun button(label: String, click: (View) -> Unit) {
        body.addView(Look.ghostButton(this, label, dark = true) {}.apply {
            setOnClickListener { if (!busy) click(it) }
            isEnabled = !busy
            minHeight = dp(48)
        }, lp())
    }
    private fun message(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun lp() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    private fun dp(value: Int) = Look.dp(this, value)

    companion object {
        /** A logical camera id, or "logical.physical" for a physical camera behind it ([CameraProbeEntry.key]). */
        const val EXTRA_CAMERA_ID = "camera_id"
        /** Hit rows built for one query; "qcamera3" alone hits 120 lines on a Snapdragon. */
        private const val MAX_HITS = 100
        private val HIGHLIGHT = Color.argb(150, 0, 102, 204)
        private val ACTIVE_LINE = Color.argb(60, 41, 151, 255)
    }
}
