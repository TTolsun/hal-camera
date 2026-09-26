package dev.halcamera.cts.suite

import android.content.Intent
import android.content.res.ColorStateList
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.halcamera.R
import dev.halcamera.ui.IconButton
import dev.halcamera.ui.Look

/**
 * The shape shared by the two CTS lists: a checklist of items in run order, a 전체 선택 button per group, and a
 * bottom bar that runs the ticked items one after another through [CtsSuiteRunActivity]. The › of a row opens
 * the single-item screen instead. The selection is kept in preferences under [prefsName] so the same set can be
 * run again next time. Subclasses supply the items and the words; nothing here opens a camera.
 */
abstract class CtsChecklistActivity : ComponentActivity() {
    /** One titled block of the checklist. */
    class Group(val title: String, val detail: String, val items: List<SuiteItem>)

    protected abstract val screenTitle: String
    protected abstract val intro: String
    protected abstract val disclaimer: String
    protected abstract val prefsName: String
    protected abstract fun groups(): List<Group>
    /** The screen that runs [item] on its own. */
    protected abstract fun singleIntent(item: SuiteItem): Intent

    private lateinit var items: List<SuiteItem>
    private val selected = LinkedHashSet<String>()
    private val boxes = LinkedHashMap<String, CheckBox>()
    private val groupButtons = ArrayList<Pair<List<SuiteItem>, Button>>()
    private lateinit var summaryView: TextView
    private lateinit var runButton: Button
    private var cameras = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val groups = groups()
        items = groups.flatMap { it.items }
        cameras = runCatching { getSystemService(CameraManager::class.java).cameraIdList.size }.getOrDefault(0)
        val keys = items.map { it.key }.toSet()
        selected += (getSharedPreferences(prefsName, MODE_PRIVATE).getStringSet(KEY_SELECTED, emptySet()) ?: emptySet()).filter { it in keys }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Look.expertTile) }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar = buildBar()
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            scroll.setPadding(bars.left + dp(16), bars.top + dp(16), bars.right + dp(16), dp(16))
            bar.setPadding(bars.left + dp(16), dp(12), bars.right + dp(16), bars.bottom + dp(12))
            insets
        }

        body.addView(Look.titleBar(this, screenTitle, 22, "이전 화면으로 돌아가기") { finish() })
        body.addView(Look.text(this, intro, 12, Look.onDarkMuted), lp(top = 4))
        body.addView(Look.text(this, "앱 내 검사 · 공식 CTS 인증 결과 아님", 12, Look.onDarkMuted), lp(top = 4))
        groups.forEach { body.addView(section(it), lp(top = 20)) }
        body.addView(Look.disclosure(this, "실행 범위 안내", Look.text(this, disclaimer, 13, Look.onDarkMuted)), lp(top = 16))
        onSelectionChanged()
    }

    override fun onPause() {
        getSharedPreferences(prefsName, MODE_PRIVATE).edit().putStringSet(KEY_SELECTED, HashSet(selected)).apply()
        super.onPause()
    }

    // ---- groups and rows ----

    /** A heading with its 전체 선택 button, then one dark card holding a row per item, separated by hairlines. */
    private fun section(group: Group): View {
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = Look.row(this)
        head.addView(Look.text(this, group.title, 15, Look.onDark, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        val all = Look.ghostButton(this, "전체 선택", dark = true) {}
        all.setOnClickListener {
            val everySelected = group.items.all { it.key in selected }
            group.items.forEach { boxes[it.key]?.isChecked = !everySelected }
        }
        head.addView(all, LinearLayout.LayoutParams(-2, -2))
        block.addView(head)
        if (group.detail.isNotEmpty()) block.addView(Look.text(this, group.detail, 12, Look.onDarkMuted), lp(top = 2))
        groupButtons += group.items to all

        val card = Look.card(this, dark = true).apply { setPadding(0, 0, 0, 0) }
        group.items.forEachIndexed { index, item ->
            if (index > 0) card.addView(View(this).apply { setBackgroundColor(Look.expertTile3) }, LinearLayout.LayoutParams(-1, dp(1)))
            card.addView(row(item))
        }
        block.addView(card, lp(top = 10))
        return block
    }

    private fun row(item: SuiteItem): View {
        val row = Look.row(this).apply { minimumHeight = dp(56); setPadding(dp(6), dp(4), dp(4), dp(4)) }
        val estimate = SuitePlan.estimateLabel(item, cameras)
        val box = CheckBox(this).apply {
            isChecked = item.key in selected
            buttonTintList = ColorStateList.valueOf(Look.onDark)
            contentDescription = "${item.title}, $estimate"
            minimumWidth = dp(48); minimumHeight = dp(48)
            setOnCheckedChangeListener { _, checked ->
                if (checked) selected += item.key else selected -= item.key
                onSelectionChanged()
            }
        }
        boxes[item.key] = box
        row.addView(box, LinearLayout.LayoutParams(dp(48), dp(48)))
        // The texts toggle the box too, so the whole row minus the › is one target; TalkBack reads the box alone.
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true; isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setOnClickListener { box.toggle() }
        }
        column.addView(Look.text(this, item.title, 15, Look.onDark, bold = true))
        // A vendored row is titled by its method and grouped under its class, so its source would only repeat both.
        val line = if (item is SuiteItem.Vendored) estimate else "$estimate · ${item.source}"
        column.addView(Look.text(this, line, 12, Look.onDarkMuted), lp(top = 2))
        row.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        row.addView(IconButton(this, R.drawable.ic_action_next, "${item.title} 하나만 여는 화면") { startActivity(singleIntent(item)) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        return row
    }

    // ---- bottom bar ----

    private fun buildBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Look.cardBackground(this@CtsChecklistActivity, Look.expertTile2, Look.expertTile3)
        }
        summaryView = Look.text(this, "", 13, Look.onDark)
        bar.addView(summaryView, LinearLayout.LayoutParams(-1, -2))
        runButton = Look.primaryButton(this, "선택한 검사 실행") { run() }
        // Wrap height with a 48dp floor: a fixed 48dp clipped the label at large font scales.
        runButton.minHeight = dp(48)
        bar.addView(runButton, Look.buttonParams().apply { topMargin = dp(8) })
        return bar
    }

    private fun onSelectionChanged() {
        val chosen = SuitePlan.select(items, selected)
        summaryView.text = SuitePlan.summaryLine(chosen, cameras)
        runButton.isEnabled = chosen.isNotEmpty()
        runButton.text = if (chosen.isEmpty()) "검사 항목을 선택하세요" else "선택한 ${chosen.size}개 검사 실행"
        runButton.alpha = if (chosen.isEmpty()) 0.5f else 1f
        runButton.contentDescription = if (chosen.isEmpty()) "실행, 항목을 먼저 고르세요" else "선택한 ${chosen.size}개 실행"
        groupButtons.forEach { (groupItems, button) ->
            button.text = if (groupItems.isNotEmpty() && groupItems.all { it.key in selected }) "전체 해제" else "전체 선택"
        }
    }

    private fun run() {
        val chosen = SuitePlan.select(items, selected)
        if (chosen.isEmpty()) return
        startActivity(Intent(this, CtsSuiteRunActivity::class.java).putExtra(CtsSuiteRunActivity.EXTRA_KEYS, chosen.map { it.key }.toTypedArray()))
    }

    private fun dp(v: Int) = Look.dp(this, v)
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); gravity = Gravity.START }

    companion object {
        private const val KEY_SELECTED = "selected"
    }
}
