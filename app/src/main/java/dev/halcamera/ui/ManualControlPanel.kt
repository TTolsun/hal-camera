package dev.halcamera.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import dev.halcamera.R
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
    private val expandedChanged: (Boolean) -> Unit = {},
) {
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(12))
        background = Look.cardBackground(context, Look.cameraControlGlass, Color.TRANSPARENT).apply { cornerRadius=dp(24).toFloat() }
        visibility = View.GONE
    }
    private val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val title = button("Manual") { toggle() }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
    private val reset = button("Reset") { commit(ManualControls()) }.apply { contentDescription = "Reset exposure, focus and white balance to Auto" }
    private val fold = IconButton(context,R.drawable.ic_action_close,"Close manual controls",dark=true) { expanded = false; render() }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val tabRow = LinearLayout(context).apply {
        setPadding(dp(4),dp(4),dp(4),dp(4))
        background=Look.pill(context,Look.cameraControlGlass)
        isBaselineAligned=false
    }
    private val scroll = ScrollView(context).apply { addView(body); isFillViewport = false }
    private val actual = text(10).apply { maxLines = 2; gravity=Gravity.CENTER; setPadding(0,dp(8),0,0) }
    private var autoValue: TextView? = null
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
        header.addView(reset); header.addView(fold,LinearLayout.LayoutParams(dp(48),dp(48)))
        view.addView(header)
        view.addView(tabRow,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
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
        view.visibility = if (expanded) View.VISIBLE else View.GONE
        expandedChanged(expanded)
        updateHeight()
        title.text = if (expanded) "Manual" else current.summary()
        title.textSize = 12f
        title.isClickable = !expanded
        title.contentDescription = if (expanded) "Manual capture" else "${current.summary()}, expand manual controls"
        reset.visibility = if (expanded && support.camera2 && current.active) View.VISIBLE else View.GONE
        reset.isEnabled = enabled; reset.alpha = if (enabled) 1f else 0.4f
        fold.visibility = if (expanded) View.VISIBLE else View.GONE
        scroll.visibility = if (expanded) View.VISIBLE else View.GONE
        tabRow.visibility = if (expanded) View.VISIBLE else View.GONE
        actual.visibility = if (expanded && support.camera2) View.VISIBLE else View.GONE
        body.removeAllViews()
        autoValue=null
        tabRow.removeAllViews()
        if (!expanded) return
        tabs.forEachIndexed { index, label ->
            tabRow.addView(button(label) { selected = index; scroll.scrollTo(0, 0); render() }.apply {
                setTextColor(if (index == selected) Look.cameraOnSelection else Look.onDarkMuted)
                background=Look.pill(context,if (index==selected) Look.cameraSelection else Color.TRANSPARENT)
                typeface = if (index == selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                isSelected = index == selected
                contentDescription = "$label adjust${if (index == selected) ", Selected" else ""}"
            }, LinearLayout.LayoutParams(0, -2, 1f))
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
        // Anchor the header and tabs while matching the panel height to the editor's content.
        // Only the editor body scrolls, including with a large system font.
        val automatic = when (selected) {
            0,1 -> current.exposure == null
            2 -> current.focusDiopters == null
            else -> true
        }
        val height = if (expanded) dp(if (automatic) 232 else 280) else -2
        if (params.height != height) { params.height = height; view.layoutParams = params }
    }

    private fun exposureEditor() {
        if (!support.canExpose) { explanation("This camera does not support manual exposure."); return }
        modes(current.exposure != null, "Exposure") { manual ->
            val e = if (manual) support.exposure(number("iso")?.toInt() ?: support.iso!!.first,
                number("exposureNs")?.toLong() ?: 10_000_000L) else null
            if (e != null && (e.iso != number("iso")?.toInt() || e.timeNs != number("exposureNs")?.toLong()))
                notice("Current readings adjusted to supported manual ranges: ISO ${e.iso} · ${fmt(e.timeNs / 1e6)} ms")
            commit(current.copy(exposure = e, wb = if (!manual && current.wb == WhiteBalance.CUSTOM) WhiteBalance.AUTO else current.wb))
        }
        val e = current.exposure ?: run { autoReading(); return }
        if (selected == 0) {
            numeric("ISO ${e.iso}", e.iso.toDouble(), support.iso!!.first.toDouble(), support.iso!!.last.toDouble(), true,
                "ISO", e.iso.toString()) { raw -> raw.toIntOrNull()?.let { commit(current.copy(exposure = e.copy(iso = it))) } ?: invalid() }
        } else {
            numeric("${ManualControls.shutter(e.timeNs)} · ${fmt(e.timeNs / 1e6)} ms", e.timeNs / 1e6,
                support.exposureNs!!.first / 1e6, support.maxExposureNs!! / 1e6, true, "Exposure time (ms or 1/125)", fmt(e.timeNs / 1e6)) { raw ->
                val ns = parseExposure(raw)
                if (ns == null) invalid() else commit(current.copy(exposure = e.copy(timeNs = ns)))
            }
        }
    }

    private fun focusEditor() {
        if (support.maxFocus <= 0f) { explanation("This camera has fixed focus or does not support manual focus."); return }
        modes(current.focusDiopters != null, "Focus") { manual -> commit(current.copy(focusDiopters =
            if (manual) (number("focusDiopters")?.toFloat() ?: 0f).coerceIn(0f, support.maxFocus) else null)) }
        val focus = current.focusDiopters ?: run { autoReading(); return }
        numeric("${fmt(focus.toDouble())} D", focus.toDouble(), 0.0, support.maxFocus.toDouble(), false,
            "Focus (diopters)", focus.toString()) { raw -> raw.toFloatOrNull()?.let { commit(current.copy(focusDiopters = it)) } ?: invalid() }
    }

    private fun wbEditor() {
        val choices = support.whiteBalances
        body.addView(button("WB · ${current.wb.label} ▾") {
            if (!enabled) return@button
            inputDialog = LiveChoiceSheet.show(context,"White balance",choices.map { LiveChoiceSheet.Choice(it.label) },
                choices.indexOf(current.wb)) { index -> commit(current.copy(wb=choices[index])) }
        }.apply { isEnabled = enabled })
        if (current.wb == WhiteBalance.CUSTOM) {
            body.addView(button("Gains / Matrix") { editColor() }.apply { isEnabled = enabled })
        }
    }

    private fun modes(manual: Boolean, label: String, set: (Boolean) -> Unit) {
        val row = LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL; isBaselineAligned=false
            setPadding(dp(4),dp(4),dp(4),dp(4))
            background=Look.pill(context,Look.cameraControlGlass)
        }
        listOf(false to "Auto", true to "Manual").forEach { (m, name) -> row.addView(button(name) { if (enabled) set(m) }.apply {
            isEnabled = enabled; isSelected=m==manual
            setTextColor(if (m == manual) Look.cameraOnSelection else Look.onDarkMuted)
            background=Look.pill(context,if (m==manual) Look.cameraSelection else Color.TRANSPARENT)
            contentDescription = "$label $name${if (m == manual) ", Selected" else ""}"
        },LinearLayout.LayoutParams(0,-2,1f)) }
        body.addView(row,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
    }

    private fun numeric(label: String, value: Double, min: Double, max: Double, logarithmic: Boolean, hint: String,
                        raw: String, set: (String) -> Unit) {
        val valueButton = button(label) { if (enabled) edit(hint, raw, set) }.apply {
            isEnabled = enabled; typeface = Typeface.DEFAULT; textSize=12f; contentDescription = "$hint enter a value, $label"
            setTextColor(Look.onDark)
        }
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        fun at(p: Int): Double = if (logarithmic && min > 0) exp(ln(min) + (ln(max) - ln(min)) * p / 1000) else min + (max - min) * p / 1000
        val position = if (max <= min) 0 else ((if (logarithmic && min > 0) (ln(value) - ln(min)) / (ln(max) - ln(min)) else (value - min) / (max - min)) * 1000).roundToInt().coerceIn(0, 1000)
        fun submit(p: Int) { val v = at(p.coerceIn(0, 1000)); set(if (selected == 0) v.roundToInt().toString() else v.toString()) }
        row.addView(valueButton, LinearLayout.LayoutParams(-1, -2))
        body.addView(row)
        body.addView(SeekBar(context).apply {
            this.max = 1000; progress = position; isEnabled = enabled; contentDescription = "$hint adjust"
            progressTintList=ColorStateList.valueOf(Look.onDark)
            progressBackgroundTintList=ColorStateList.valueOf(Look.onDarkMuted)
            thumbTintList=ColorStateList.valueOf(Look.onDark)
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
            textSize=12f; setTextColor(Look.onDark); setHintTextColor(Look.onDarkMuted)
            this.hint = hint
            inputType = if (selected == 1) InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(value); setSelectAllOnFocus(true); setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        inputDialog = LiveChoiceSheet.show(context,tabs[selected],content=input,actionLabel="Apply",confirm=confirm@{
            if (!enabled) return@confirm false
            val raw = input.text.toString().trim()
            val candidate = when (selected) {
                0 -> raw.toIntOrNull()?.let { n -> current.exposure?.let { current.copy(exposure = it.copy(iso = n)) } }
                1 -> parseExposure(raw)?.let { n -> current.exposure?.let { current.copy(exposure = it.copy(timeNs = n)) } }
                2 -> raw.toFloatOrNull()?.let { current.copy(focusDiopters = it) }
                else -> null
            }
            val error = candidate?.let(support::rejection) ?: if (candidate == null) "Enter a valid number." else null
            if (error != null) input.error = error else set(raw)
            error == null
        })
        input.requestFocus(); input.selectAll()
        inputDialog?.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private fun editColor() {
        if (!enabled) return
        val fields = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        val gains = EditText(context).apply { setText(current.color.gains.joinToString(" ")); hint = "R G(even) G(odd) B" }
        val matrix = EditText(context).apply { setText(current.color.transform.chunked(3).joinToString("\n") { it.joinToString(" ") }); hint = "3×3 matrix (row order)"; minLines = 3 }
        listOf(gains,matrix).forEach { it.textSize=12f; it.setTextColor(Look.onDark); it.setHintTextColor(Look.onDarkMuted) }
        fields.addView(text(12).apply { text = "Gains · R / G(even) / G(odd) / B · 1–100 each" })
        fields.addView(gains)
        fields.addView(text(12).apply { text = "3×3 color matrix · row order · −100–100 each" })
        fields.addView(matrix)
        fields.addView(text(12).apply {
            text = "Applied gains\n${observedColor("colorGains")}\nApplied matrix\n${observedColor("colorTransform", 3)}"
        })
        inputDialog = LiveChoiceSheet.show(context,"White balance",content=fields,actionLabel="Apply",confirm=confirm@{
            if (!enabled) return@confirm false
            fun parse(s: String) = s.trim().split(Regex("[\\s,]+")).map { it.toFloatOrNull() ?: Float.NaN }
            val next = current.copy(color = ManualColor(parse(gains.text.toString()), parse(matrix.text.toString())))
            val error = support.rejection(next)
            if (error != null) matrix.error = error else commit(next)
            error == null
        })
    }

    private fun renderActual(now: Long) {
        val pending = changedNs > 0 && (latest?.atNs ?: 0L) < changedNs || now - changedNs < 300_000_000L
        val observed = when (selected) {
            0 -> number("iso")?.toInt()?.toString()
            1 -> number("exposureNs")?.let { "${fmt(it / 1e6)} ms" }
            2 -> number("focusDiopters")?.let { "${fmt(it)} D" }
            else -> number("awbMode")?.toInt()?.let { key -> WhiteBalance.entries.find { it.key == key }?.label }
        }
        val label = when {
            pending && now - changedNs < 2_000_000_000L -> "Applying · Actual ${observed ?: "checking"}"
            observed == null -> "Applied values unavailable"
            else -> "Actual $observed"
        }
        autoValue?.text=observed ?: "—"
        autoValue?.contentDescription="Current ${tabs[selected]}, ${observed ?: "unavailable"}"
        if (expanded && support.camera2) actual.visibility=if (autoValue==null) View.VISIBLE else View.GONE
        if (actual.text.toString() != label) actual.text = label
    }

    private fun autoReading() {
        autoValue=Look.text(context,"—",12,Look.onDark).apply {
            gravity=Gravity.CENTER; minimumHeight=dp(48)
            contentDescription="Current ${tabs[selected]}"
        }
        body.addView(autoValue,LinearLayout.LayoutParams(-1,-2))
    }

    private fun number(key: String) = (latest?.values?.get(key) as? Number)?.toDouble()
    private fun observedColor(key: String, columns: Int = 4): String =
        (latest?.values?.get(key) as? List<*>)?.mapNotNull { (it as? Number)?.toDouble()?.let(::fmt) }
            ?.chunked(columns)?.joinToString("\n") { it.joinToString(" / ") } ?: "Unavailable"
    private fun invalid() = notice("Enter a number in the supported range.")
    private fun explanation(value: String) { body.addView(text(12).apply { text = value }) }
    private fun text(size: Int) = TextView(context).apply { textSize = size.toFloat(); setTextColor(Look.onDarkMuted); gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
    private fun button(label: String, action: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 12f; setTextColor(Look.onDark)
        minWidth = dp(48); minimumWidth = dp(48); minHeight = dp(48); minimumHeight = dp(48)
        setPadding(dp(8), 0, dp(8), 0); background = Look.touchBackground(context, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        backgroundTintList=null; stateListAnimator=null; includeFontPadding=false; gravity=Gravity.CENTER
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
