package dev.halcamera.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import dev.halcamera.camera.*
import dev.halcamera.telemetry.Event
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** A non-modal editor above the existing shutter. Only one parameter is expanded at a time. */
class ManualControlPanel(
    private val context: Context,
    private val changed: (ManualControls) -> Unit,
    private val notice: (String) -> Unit,
) {
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(4))
        background = Look.cardBackground(context, Look.cameraGlass, Look.cameraOutline)
        visibility = View.GONE
    }
    private val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val title = button("Manual") { toggle() }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
    private val reset = button("Reset") { commit(ManualControls()) }.apply { contentDescription = "노출·초점·WB를 모두 자동으로 초기화" }
    private val fold = button("Hide") { expanded = false; render() }.apply { contentDescription = "설정을 유지하고 패널 접기" }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val tabRow = LinearLayout(context)
    private val scroll = ScrollView(context).apply { addView(body); isFillViewport = false }
    private val actual = text(12).apply { minLines = 2; maxLines = 2 }
    private var expanded = false
    val isExpanded get() = expanded
    val observedKey get() = if (expanded && support.camera2) when (selected) {
        0 -> "iso"
        1 -> "exposureNs"
        else -> null
    } else null
    private var selected = 0
    private var enabled = false
    private var support = ManualSupport()
    private var current = ManualControls()
    private var latest: Event? = null
    private var changedNs = 0L
    private var dragging = false
    private var inputDialog: AlertDialog? = null
    private val tabs = listOf("ISO", "Shutter", "Focus", "WB")

    init {
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(reset); header.addView(fold)
        view.addView(header)
        view.addView(tabRow)
        view.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        view.addView(actual)
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateHeight() }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { inputDialog?.dismiss() }
        })
    }

    fun reset(support: ManualSupport) {
        inputDialog?.dismiss()
        this.support = support; current = ManualControls(); latest = null; expanded = false; dragging = false
        render()
    }

    fun toggle() { if (support.camera2) { expanded = !expanded; render() } }
    fun close(): Boolean { if (!expanded) return false; expanded = false; render(); return true }

    fun bind(value: ManualControls, enabled: Boolean, nextSupport: ManualSupport, frame: Event?, nowNs: Long) {
        latest = frame?.takeIf { nowNs - it.atNs < 1_500_000_000L }
        val redraw = current != value || this.enabled != enabled || support != nextSupport
        current = value; this.enabled = enabled; support = nextSupport
        if (!enabled) inputDialog?.dismiss()
        if (redraw) render()
        renderActual(nowNs)
    }

    private fun commit(next: ManualControls) {
        if (!enabled) return
        support.rejection(next)?.let { notice(it); return }
        current = next; changedNs = SystemClock.elapsedRealtimeNanos()
        changed(next)
        if (!dragging) render()
    }

    private fun render() {
        if (!support.camera2) expanded = false
        view.visibility = if (expanded || current.active) View.VISIBLE else View.GONE
        updateHeight()
        title.text = if (expanded) "Manual" else current.summary()
        title.textSize = if (expanded) 16f else 12f
        title.isClickable = !expanded
        title.contentDescription = if (expanded) "수동 촬영" else "${current.summary()}, 수동 촬영 패널 펼치기"
        reset.visibility = if (expanded && support.camera2 && current.active) View.VISIBLE else View.GONE
        reset.isEnabled = enabled; reset.alpha = if (enabled) 1f else 0.4f
        fold.visibility = if (expanded) View.VISIBLE else View.GONE
        scroll.visibility = if (expanded) View.VISIBLE else View.GONE
        tabRow.visibility = if (expanded) View.VISIBLE else View.GONE
        actual.visibility = if (expanded && support.camera2) View.VISIBLE else View.GONE
        body.removeAllViews()
        tabRow.removeAllViews()
        if (!expanded) return
        tabs.forEachIndexed { index, label ->
            tabRow.addView(button(label) { selected = index; scroll.scrollTo(0, 0); render() }.apply {
                setTextColor(if (index == selected) Look.primaryOnDark else Look.onDark)
                typeface = if (index == selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                isSelected = index == selected
                contentDescription = "$label 조절${if (index == selected) ", 선택됨" else ""}"
            }, LinearLayout.LayoutParams(0, -2, listOf(0.8f, 1.3f, 1.1f, 0.8f)[index]))
        }
        when (selected) {
            0, 1 -> exposureEditor()
            2 -> focusEditor()
            else -> wbEditor()
        }
        renderActual(SystemClock.elapsedRealtimeNanos())
    }

    private fun updateHeight() {
        val params = view.layoutParams ?: return
        // Keep the header and tabs at the same coordinates across parameters and Auto/Manual.
        // Only the editor body scrolls, including with a large system font.
        val height = if (expanded) (view.rootView.height * 0.40f).toInt().coerceAtLeast(dp(240)) else -2
        if (params.height != height) { params.height = height; view.layoutParams = params }
    }

    private fun exposureEditor() {
        if (!support.canExpose) { explanation("이 카메라는 수동 노출을 지원하지 않습니다."); return }
        modes(current.exposure != null, "Exposure") { manual ->
            val e = if (manual) support.exposure(number("iso")?.toInt() ?: support.iso!!.first,
                number("exposureNs")?.toLong() ?: 10_000_000L) else null
            if (e != null && (e.iso != number("iso")?.toInt() || e.timeNs != number("exposureNs")?.toLong()))
                notice("현재 관측값을 수동 지원 범위에 맞췄습니다: ISO ${e.iso} · ${fmt(e.timeNs / 1e6)} ms")
            commit(current.copy(exposure = e, wb = if (!manual && current.wb == WhiteBalance.CUSTOM) WhiteBalance.AUTO else current.wb))
        }
        val e = current.exposure ?: run { explanation("Manual을 선택하면 현재 ISO와 노출 시간으로 시작합니다."); return }
        if (selected == 0) {
            numeric("ISO ${e.iso}", e.iso.toDouble(), support.iso!!.first.toDouble(), support.iso!!.last.toDouble(), true,
                "ISO", e.iso.toString()) { raw -> raw.toIntOrNull()?.let { commit(current.copy(exposure = e.copy(iso = it))) } ?: invalid() }
        } else {
            numeric("${ManualControls.shutter(e.timeNs)} · ${fmt(e.timeNs / 1e6)} ms", e.timeNs / 1e6,
                support.exposureNs!!.first / 1e6, support.maxExposureNs!! / 1e6, true, "노출 시간 (ms 또는 1/125)", fmt(e.timeNs / 1e6)) { raw ->
                val ns = parseExposure(raw)
                if (ns == null) invalid() else commit(current.copy(exposure = e.copy(timeNs = ns)))
            }
        }
    }

    private fun focusEditor() {
        if (support.maxFocus <= 0f) { explanation("고정 초점이거나 수동 초점을 지원하지 않는 카메라입니다."); return }
        modes(current.focusDiopters != null, "Focus") { manual -> commit(current.copy(focusDiopters =
            if (manual) (number("focusDiopters")?.toFloat() ?: 0f).coerceIn(0f, support.maxFocus) else null)) }
        val focus = current.focusDiopters ?: return
        numeric("${fmt(focus.toDouble())} D", focus.toDouble(), 0.0, support.maxFocus.toDouble(), false,
            "초점 (diopter)", focus.toString()) { raw -> raw.toFloatOrNull()?.let { commit(current.copy(focusDiopters = it)) } ?: invalid() }
    }

    private fun wbEditor() {
        val choices = support.whiteBalances
        body.addView(button("WB · ${current.wb.label} ▾") {
            if (!enabled) return@button
            inputDialog = AlertDialog.Builder(context).setTitle("White Balance")
                .setSingleChoiceItems(choices.map { it.label }.toTypedArray(), choices.indexOf(current.wb)) { dialog, index ->
                    dialog.dismiss(); commit(current.copy(wb = choices[index]))
                }.setNegativeButton("Close", null).show()
        }.apply { isEnabled = enabled })
        if (current.wb == WhiteBalance.CUSTOM) {
            body.addView(button("Gains / Matrix") { editColor() }.apply { isEnabled = enabled })
        }
    }

    private fun modes(manual: Boolean, label: String, set: (Boolean) -> Unit) {
        val row = LinearLayout(context)
        row.addView(text(14).apply { text = label }, LinearLayout.LayoutParams(0, -2, 1f))
        listOf(false to "Auto", true to "Manual").forEach { (m, name) -> row.addView(button(name) { if (enabled) set(m) }.apply {
            isEnabled = enabled; setTextColor(if (m == manual) Look.primaryOnDark else Look.onDarkMuted)
            contentDescription = "$label $name${if (m == manual) ", 선택됨" else ""}"
        }) }
        body.addView(row)
    }

    private fun numeric(label: String, value: Double, min: Double, max: Double, logarithmic: Boolean, hint: String,
                        raw: String, set: (String) -> Unit) {
        val valueButton = button(label) { if (enabled) edit(hint, raw, set) }.apply {
            isEnabled = enabled; typeface = Look.mono; contentDescription = "$hint 값 직접 입력, $label"
            setTextColor(Look.primaryOnDark)
        }
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        fun at(p: Int): Double = if (logarithmic && min > 0) exp(ln(min) + (ln(max) - ln(min)) * p / 1000) else min + (max - min) * p / 1000
        val position = if (max <= min) 0 else ((if (logarithmic && min > 0) (ln(value) - ln(min)) / (ln(max) - ln(min)) else (value - min) / (max - min)) * 1000).roundToInt().coerceIn(0, 1000)
        fun submit(p: Int) { val v = at(p.coerceIn(0, 1000)); set(if (selected == 0) v.roundToInt().toString() else v.toString()) }
        row.addView(valueButton, LinearLayout.LayoutParams(-1, -2))
        body.addView(row)
        body.addView(SeekBar(context).apply {
            this.max = 1000; progress = position; isEnabled = enabled; contentDescription = "$hint 조절"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || !enabled) return
                    val touching = dragging
                    dragging = true; submit(progress); dragging = touching
                    valueButton.text = when (selected) {
                        0 -> "ISO ${current.exposure?.iso}"
                        1 -> current.exposure?.let { "${ManualControls.shutter(it.timeNs)} · ${fmt(it.timeNs / 1e6)} ms" }
                        else -> "${fmt(current.focusDiopters?.toDouble() ?: 0.0)} D"
                    }
                    if (!touching) render()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { dragging = true }
                override fun onStopTrackingTouch(bar: SeekBar) { dragging = false; render() }
            })
        }, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun edit(hint: String, value: String, set: (String) -> Unit) {
        val input = EditText(context).apply {
            this.hint = hint
            inputType = if (selected == 1) InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(value); setSelectAllOnFocus(true); setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        inputDialog = AlertDialog.Builder(context).setTitle(tabs[selected]).setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Apply", null).show()
        inputDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            if (!enabled) return@setOnClickListener
            val raw = input.text.toString().trim()
            val candidate = when (selected) {
                0 -> raw.toIntOrNull()?.let { n -> current.exposure?.let { current.copy(exposure = it.copy(iso = n)) } }
                1 -> parseExposure(raw)?.let { n -> current.exposure?.let { current.copy(exposure = it.copy(timeNs = n)) } }
                2 -> raw.toFloatOrNull()?.let { current.copy(focusDiopters = it) }
                else -> null
            }
            val error = candidate?.let(support::rejection) ?: if (candidate == null) "올바른 숫자를 입력하세요." else null
            if (error != null) input.error = error else { set(raw); inputDialog?.dismiss() }
        }
        input.requestFocus(); input.selectAll()
        inputDialog?.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private fun editColor() {
        if (!enabled) return
        val fields = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        fields.addView(text(12).apply { text = "수동 노출에서 gains와 행렬을 직접 적용합니다." })
        val gains = EditText(context).apply { setText(current.color.gains.joinToString(" ")); hint = "R G(even) G(odd) B" }
        val matrix = EditText(context).apply { setText(current.color.transform.chunked(3).joinToString("\n") { it.joinToString(" ") }); hint = "3×3 matrix (행 순서)"; minLines = 3 }
        fields.addView(text(12).apply { text = "Gains · R / G(even) / G(odd) / B · 각 1–100" })
        fields.addView(gains)
        fields.addView(text(12).apply { text = "3×3 색 변환 행렬 · 행 순서 · 각 −100–100" })
        fields.addView(matrix)
        fields.addView(text(12).apply {
            text = "실제 gains\n${observedColor("colorGains")}\n실제 matrix\n${observedColor("colorTransform", 3)}"
        })
        inputDialog = AlertDialog.Builder(context).setTitle("WB Gains / Matrix")
            .setView(ScrollView(context).apply { addView(fields) }).setNegativeButton("Cancel", null)
            .setPositiveButton("Apply", null).show()
        inputDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            if (!enabled) return@setOnClickListener
            fun parse(s: String) = s.trim().split(Regex("[\\s,]+")).map { it.toFloatOrNull() ?: Float.NaN }
            val next = current.copy(color = ManualColor(parse(gains.text.toString()), parse(matrix.text.toString())))
            val error = support.rejection(next)
            if (error != null) matrix.error = error else { commit(next); inputDialog?.dismiss() }
        }
    }

    private fun renderActual(now: Long) {
        val pending = changedNs > 0 && (latest?.atNs ?: 0L) < changedNs || now - changedNs < 300_000_000L
        val observed = when (selected) {
            0 -> number("iso")?.toInt()?.toString()
            1 -> number("exposureNs")?.let { "${fmt(it / 1e6)} ms" }
            2 -> number("focusDiopters")?.let { "${fmt(it)} D" }
            else -> number("awbMode")?.toInt()?.let { key -> WhiteBalance.entries.find { it.key == key }?.label }
        }
        var label = when {
            pending && now - changedNs < 2_000_000_000L -> "적용 중 · 실제 ${observed ?: "확인 중"}"
            observed == null -> "실제 적용값 확인 불가"
            else -> "실제 $observed"
        }
        if (selected in 0..1 && current.exposure != null) label += "\nISO·셔터 함께 고정 · 최대 ${fmt(support.maxExposureNs!! / 1e6)} ms"
        if (selected == 2 && current.focusDiopters != null) label += "\n0 D 무한대 · ${fmt(support.maxFocus.toDouble())} D 근거리"
        if (actual.text.toString() != label) actual.text = label
    }

    private fun number(key: String) = (latest?.values?.get(key) as? Number)?.toDouble()
    private fun observedColor(key: String, columns: Int = 4): String =
        (latest?.values?.get(key) as? List<*>)?.mapNotNull { (it as? Number)?.toDouble()?.let(::fmt) }
            ?.chunked(columns)?.joinToString("\n") { it.joinToString(" / ") } ?: "확인 불가"
    private fun invalid() = notice("지원 범위 안의 숫자를 입력하세요.")
    private fun explanation(value: String) { body.addView(text(12).apply { text = value }) }
    private fun text(size: Int) = TextView(context).apply { textSize = size.toFloat(); setTextColor(Look.onDarkMuted); gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
    private fun button(label: String, action: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 14f; setTextColor(Look.onDark)
        minWidth = dp(48); minimumWidth = dp(48); minHeight = dp(48); minimumHeight = dp(48)
        setPadding(dp(8), 0, dp(8), 0); background = Look.touchBackground(context, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        setOnClickListener { action() }
    }
    private fun dp(n: Int) = Look.dp(context, n)
    private fun fmt(n: Double) = String.format(Locale.US, "%.3f", n).trimEnd('0').trimEnd('.')

    companion object {
        fun parseExposure(raw: String): Long? {
            val s = raw.trim().removeSuffix("ms").trim()
            val ns = if (s.startsWith("1/")) s.substring(2).toDoubleOrNull()?.takeIf { it > 0 }?.let { 1e9 / it }
                else s.toDoubleOrNull()?.times(1e6)
            return ns?.takeIf { it.isFinite() && it > 0 && it < Long.MAX_VALUE.toDouble() }?.toLong()
        }
    }
}
