package dev.halcamera.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.view.accessibility.AccessibilityManager
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import dev.halcamera.R
import dev.halcamera.camera.FlashMode
import dev.halcamera.camera.LiveControlSupport
import dev.halcamera.camera.LiveControlText
import dev.halcamera.camera.LiveControls

/**
 * LIVE's quick controls for flash, AF lock, AE lock and EV (issues #169 and #176), laid out like the top row of a
 * stock camera app: round glyph buttons under the status line, white when on, the same selection look as the
 * zoom rail. Two of them open in place instead of cycling blind:
 *
 * - Flash turns the row into its choices (off, auto, on, torch) with captions. It stays open until a pick or a
 *   tap on the pill: folding on a timer put the AE button under a finger that was reaching for a choice.
 * - EV opens a dial under the row; it folds away four seconds after the last touch. Nothing sits where the dial
 *   was, so a late tap there reaches the preview, not another control.
 *
 * The buttons show what was requested. What the camera applied is the readout line built by [stateLine].
 * A control the camera lacks stays visible and dimmed, and a tap says why: a missing flash on the front camera is
 * itself something a developer checks. On CameraX a tap asks [Host.needsCamera2] to switch engines and then
 * carries out the tap once the Camera2 session is ready, so one tap is enough.
 */
class LiveControlBar(private val context: Context, private val host: Host) {
    interface Host {
        fun controlsChanged(controls: LiveControls)
        /** Starts the switch to Camera2; false when it cannot switch now (a recording is running). */
        fun needsCamera2(): Boolean
        fun notice(text: String)
    }

    /** A tap made on CameraX, run once the Camera2 session it waited for is ready. */
    private var pending: (() -> Unit)? = null

    val view = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    var support = LiveControlSupport.NONE; private set
    var controls = LiveControls(); private set
    private var camera2 = true
    private var video = false
    private var enabled = true

    private val mainRow = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private val flashRow = LinearLayout(context).apply { gravity = Gravity.CENTER; visibility = View.GONE }
    private val flash = QuickButton(context) { tap { openFlash() } }
    private val afLock = QuickButton(context) { tap { toggleAf() } }
    private val aeLock = QuickButton(context) { tap { toggleAe() } }
    private val ev = QuickButton(context) { tap { toggleRuler() } }
    private val ruler = EvRuler(context) { index ->
        controls = controls.copy(evIndex = index); host.controlsChanged(controls); render(); scheduleFold()
    }.apply {
        visibility = View.GONE
    }
    private val evPanel = Look.row(context).apply {
        visibility = View.GONE
        background = Look.cardBackground(context, Look.cameraGlass, Look.cameraOutline)
    }
    private val accessibility = context.getSystemService(AccessibilityManager::class.java)
    private val fold = Runnable { closePanels() }

    /** The buttons stay folded behind [handle] until asked for, so the preview is not covered by controls not in use. */
    private var expanded = false
    private val rows = FrameLayout(context).apply { visibility = View.GONE }
    private val handle = TextView(context).apply {
        textSize = 12f; setTextColor(Look.onDark); gravity = Gravity.CENTER
        background = InsetDrawable(Look.pill(context, Look.cameraGlass), 0, dp(8), 0, dp(8))
        minimumHeight = dp(48)
        isFocusable = true
        compoundDrawablePadding = dp(4)
        setOnClickListener { setExpanded(!expanded) }
    }

    init {
        view.gravity = Gravity.CENTER_HORIZONTAL
        view.addView(handle, LinearLayout.LayoutParams(-2, -2))
        rows.addView(mainRow, FrameLayout.LayoutParams(-1, -2))
        rows.addView(flashRow, FrameLayout.LayoutParams(-1, -2))
        view.addView(rows, LinearLayout.LayoutParams(-1, -2))
        ruler.onInteraction = { touching -> if (touching) view.removeCallbacks(fold) else scheduleFold() }
        listOf("−" to -1, "+" to 1, "0" to 0).forEach { (label, delta) ->
            if (delta == 1) evPanel.addView(ruler, LinearLayout.LayoutParams(0, dp(64), 1f))
            evPanel.addView(Button(context).apply {
                text = label; textSize = 14f; isAllCaps = false
                setTextColor(Look.onDark)
                minWidth = 0; minimumWidth = 0
                setPadding(0, 0, 0, 0)
                backgroundTintList = null
                background = Look.touchBackground(context, Color.TRANSPARENT, Color.TRANSPARENT)
                stateListAnimator = null
                setOnClickListener {
                    if (enabled) { if (delta == 0) ruler.resetValue() else ruler.adjustBy(delta); scheduleFold() }
                }
                contentDescription = when (delta) { -1 -> "노출 보정 한 단계 낮추기"; 1 -> "노출 보정 한 단계 높이기"; else -> "노출 보정 EV 0으로 초기화" }
                tooltipText = contentDescription
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        view.addView(evPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        listOf(flash, afLock, aeLock, ev).forEach { button ->
            mainRow.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { view.removeCallbacks(fold) }
        })
        render()
    }

    private fun setExpanded(open: Boolean) {
        expanded = open
        if (!open) closePanels()
        rows.visibility = if (open) View.VISIBLE else View.GONE
        if (open && android.animation.ValueAnimator.areAnimatorsEnabled()) { rows.alpha = 0f; rows.translationY = -dp(8).toFloat(); rows.animate().alpha(1f).translationY(0f).setDuration(160).start() }
        render()
    }

    /** A new camera or engine starts from the defaults; a lock or an EV step never carries over to another lens. */
    fun reset(support: LiveControlSupport) {
        this.support = support
        controls = LiveControls()
        pending = null
        setExpanded(false)
    }

    /**
     * Called on every LIVE refresh. Switching to video mode drops flash auto and on, which only a still can fire;
     * the engine is told so the repeating request matches the buttons.
     */
    fun bind(camera2: Boolean, video: Boolean, enabled: Boolean) {
        val changed = camera2 != this.camera2 || video != this.video || enabled != this.enabled
        this.camera2 = camera2; this.video = video; this.enabled = enabled
        val coerced = controls.coerce(support, video)
        if (coerced != controls) { controls = coerced; host.controlsChanged(coerced); render() }
        if (changed) {
            if (!enabled) closePanels()
            render()
        }
        // The switch reset the bar; reopen it so the finished tap is visible where it was made.
        if (camera2 && enabled) pending?.let { pending = null; setExpanded(true); it() }
    }

    private fun tap(action: () -> Unit) {
        if (!enabled) return
        if (camera2) action()
        else if (host.needsCamera2()) pending = action
    }

    private fun update(next: LiveControls) {
        controls = next.coerce(support, video)
        host.controlsChanged(controls)
        render()
    }

    private fun openFlash() {
        if (!support.flash) { host.notice("이 카메라에는 플래시가 없습니다"); return }
        closePanels()
        flashRow.removeAllViews()
        support.flashModes(video).forEach { mode ->
            val option = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val button = QuickButton(context) { flashRow.visibility = View.GONE; mainRow.visibility = View.VISIBLE; update(controls.copy(flash = mode)) }
            button.show(icon = flashIcon(mode), text = null, active = mode == controls.flash, locked = false, available = true,
                description = "${mode.label}${if (mode == controls.flash) ", 선택됨" else ""}")
            option.addView(button, LinearLayout.LayoutParams(dp(48), dp(48)))
            option.addView(TextView(context).apply {
                text = mode.label.removePrefix("Flash ").replaceFirstChar { it.uppercase() }
                textSize = 11f; gravity = Gravity.CENTER
                setTextColor(if (mode == controls.flash) Look.onDark else Look.onDarkMuted)
                setShadowLayer(dp(2).toFloat(), 0f, 0f, Color.BLACK)
                setOnClickListener { button.performClick() }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-2, -2))
            flashRow.addView(option, LinearLayout.LayoutParams(0, -2, 1f))
        }
        mainRow.visibility = View.INVISIBLE
        flashRow.visibility = View.VISIBLE
    }

    private fun toggleAf() {
        if (!support.afLock) { host.notice("초점이 고정된 카메라라서 AF 잠금이 없습니다"); return }
        update(controls.copy(afLock = !controls.afLock))
    }

    private fun toggleAe() {
        if (!support.aeLock) { host.notice("이 카메라는 AE 잠금을 지원하지 않습니다"); return }
        update(controls.copy(aeLock = !controls.aeLock))
    }

    private fun toggleRuler() {
        val range = support.evRange ?: run { host.notice("이 카메라는 노출 보정(EV)을 지원하지 않습니다"); return }
        if (ruler.visibility == View.VISIBLE) { closePanels(); return }
        closePanels()
        ruler.set(range, support.evStep, controls.evIndex)
        ruler.visibility = View.VISIBLE
        render()
        scheduleFold()
    }

    private fun scheduleFold() {
        view.removeCallbacks(fold)
        if (ruler.isInteracting || accessibility.isTouchExplorationEnabled) return
        val timeout = if (Build.VERSION.SDK_INT >= 29) accessibility.getRecommendedTimeoutMillis(4000, AccessibilityManager.FLAG_CONTENT_CONTROLS) else 4000
        view.postDelayed(fold, timeout.toLong())
    }

    private fun closePanels() {
        view.removeCallbacks(fold)
        flashRow.visibility = View.GONE
        mainRow.visibility = View.VISIBLE
        evPanel.visibility = View.GONE
        if (ruler.visibility != View.GONE) { ruler.visibility = View.GONE; render() }
    }

    private fun render() {
        evPanel.visibility = ruler.visibility
        val suffix = if (!camera2) ". Camera2에서만 사용할 수 있습니다" else ""
        flash.show(flashIcon(controls.flash), null, controls.flash != FlashMode.OFF, false, camera2 && support.flash,
            "플래시, 현재 ${controls.flash.label}$suffix")
        afLock.show(null, "AF", controls.afLock, controls.afLock, camera2 && support.afLock,
            (if (controls.afLock) "AF 잠금 켜짐, 누르면 해제" else "AF 잠금, 누르면 초점을 맞추고 잠금") + suffix)
        aeLock.show(null, "AE", controls.aeLock, controls.aeLock, camera2 && support.aeLock,
            (if (controls.aeLock) "AE 잠금 켜짐, 누르면 해제" else "AE 잠금, 누르면 현재 노출을 잠금") + suffix)
        val evText = if (controls.evIndex == 0) null else support.evLabel(controls.evIndex).removePrefix("EV ")
        ev.show(if (evText == null) R.drawable.ic_exposure else null, evText,
            controls.evIndex != 0 || ruler.visibility == View.VISIBLE, false, camera2 && support.evRange != null,
            "노출 보정, 현재 ${support.evLabel(controls.evIndex)}, ${if (ruler.visibility == View.VISIBLE) "조절기 접기" else "조절기 펼치기"}$suffix")
        // Dim while the bar is off (camera not ready, recording being saved, a CLI command running), as MainActivity
        // dims every other Live control, so a tap that does nothing never looks like one that should.
        listOf(flash, afLock, aeLock, ev).forEach { it.isEnabled = enabled; if (!enabled) it.alpha = 0.4f }
        // Folded, the handle names whatever is on: a hidden lock or EV step must never change pictures unseen.
        val on = listOfNotNull(
            controls.flash.takeIf { it != FlashMode.OFF }?.label,
            "AF lock".takeIf { controls.afLock }, "AE lock".takeIf { controls.aeLock },
            support.evLabel(controls.evIndex).takeIf { controls.evIndex != 0 },
        )
        handle.text = if (expanded) "촬영 설정 접기" else on.joinToString(" · ").ifEmpty { "Flash · AF · AE · EV" }
        ViewCompat.setStateDescription(handle, if (expanded) "펼쳐짐" else "접힘")
        val chevron = ContextCompat.getDrawable(context, if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down)!!
            .mutate().apply { setBounds(0, 0, dp(18), dp(18)) }
        handle.setCompoundDrawablesRelative(null, null, chevron, null)
        // Chevron alone: equal sides keep it centred in the pill. With a summary: text first, chevron after it.
        if (handle.text.isEmpty()) { handle.compoundDrawablePadding = 0; handle.setPadding(dp(19), 0, dp(19), 0) }
        else { handle.compoundDrawablePadding = dp(4); handle.setPadding(dp(14), 0, dp(10), 0) }
        handle.contentDescription = if (expanded) "카메라 제어 접기" else
            "카메라 제어 펼치기: 플래시, AF 잠금, AE 잠금, EV" + if (on.isEmpty()) "" else ". 켜짐: ${on.joinToString(", ")}"
    }

    private fun flashIcon(mode: FlashMode) = when (mode) {
        FlashMode.OFF -> R.drawable.ic_flash_off
        FlashMode.AUTO -> R.drawable.ic_flash_auto
        FlashMode.ON -> R.drawable.ic_flash_on
        FlashMode.TORCH -> R.drawable.ic_torch
    }

    private fun dp(value: Int) = Look.dp(context, value)

    companion object {
        /*
         * The readout says only what the screen does not already show. Short words stand in for the Camera2 constant
         * names, which wrapped to a second line; the incident ZIP keeps the raw values.
         */

        fun aeState(ae: Int?): String =
            "AE " + when (ae) { null -> "—"; 0 -> "Idle"; 1 -> "Searching"; 2 -> "OK"; 3 -> "Locked"; 4 -> "Flash needed"; 5 -> "Metering"; else -> "#$ae" }

        /** A held AF lock reads as its outcome; the button's padlock already says it is held. */
        fun afState(af: Int?): String =
            "AF " + when (af) { null -> "—"; 0 -> "Idle"; 1, 3 -> "Scanning"; 2, 4 -> "Focused"; 5 -> "No focus"; 6 -> "Unfocused"; else -> "#$af" }

        /** Only while a flash mode is on: it is the one applied value the flash button cannot show. */
        fun flashState(state: Int?, controls: LiveControls): String? = if (controls.flash == FlashMode.OFF) null else
            "Flash " + when (state) { null -> "—"; 0 -> "Off"; 1 -> "Charging"; 2 -> "Ready"; 3 -> "Fired"; 4 -> "Partial"; else -> "#$state" }

        /** The applied EV only when it differs from the EV button. */
        fun evApplied(applied: Int?, controls: LiveControls, support: LiveControlSupport): String? =
            applied?.takeIf { it != controls.evIndex }?.let { "${support.evLabel(it)} applied" }
    }
}

/**
 * A round quick-control button: a line icon or a short word ("AF", "+0.7") in a 40dp circle inside the full
 * 48dp touch target, with a small lock badge while a lock holds. On, it takes the zoom rail's white circle.
 */
private class QuickButton(context: Context, action: () -> Unit) : Button(context) {
    private var icon: Drawable? = null
    private var iconRes = 0
    private var word: String? = null
    private var active = false
    private var locked = false
    private val badge = ContextCompat.getDrawable(context, R.drawable.ic_lock_badge)!!.mutate()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = Look.dp(context, 13).toFloat()
    }

    init {
        text = ""
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(0, 0, 0, 0)
        stateListAnimator = null
        backgroundTintList = null
        // The circle is drawn in onDraw; the press ripple rides on top as a round foreground of the same size.
        background = null
        foreground = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, null).apply { radius = Look.dp(context, 22) }
        setOnClickListener { action() }
    }

    fun show(@DrawableRes icon: Int?, text: String?, active: Boolean, locked: Boolean, available: Boolean, description: String) {
        if (icon != null && icon != iconRes) { iconRes = icon; this.icon = ContextCompat.getDrawable(context, icon)?.mutate() }
        if (icon == null) { iconRes = 0; this.icon = null }
        word = text; this.active = active; this.locked = locked
        alpha = if (available) 1f else 0.4f
        contentDescription = description
        tooltipText = description
        ViewCompat.setStateDescription(this, if (active) "켜짐" else null)
        invalidate()
    }

    override fun drawableHotspotChanged(x: Float, y: Float) {
        // Keep the ripple centred on the circle wherever the finger lands in the cell.
        super.drawableHotspotChanged(width / 2f, height / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val color = if (active) Look.cameraOnSelection else Look.onDark
        val cx = width / 2f; val cy = height / 2f
        // A 40dp circle centred in whatever width the row gives the button; the touch target stays the whole cell.
        fill.color = if (active) Look.cameraSelection else Look.cameraGlass
        canvas.drawCircle(cx, cy, Look.dp(context, 20).coerceAtMost(minOf(width, height) / 2).toFloat(), fill)
        icon?.let {
            val edge = Look.dp(context, 22)
            it.setBounds((cx - edge / 2).toInt(), (cy - edge / 2).toInt(), (cx + edge / 2).toInt(), (cy + edge / 2).toInt())
            it.setTint(color)
            it.draw(canvas)
        }
        // A held lock puts a small padlock under the word, inside the circle, the way "AE/AF lock" reads on stock cameras.
        val lift = if (locked) Look.dp(context, 5) else 0
        word?.let {
            textPaint.color = color
            canvas.drawText(it, cx, cy - lift - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        }
        if (locked) {
            val size = Look.dp(context, 10)
            val top = (cy + Look.dp(context, 4)).toInt()
            badge.setBounds((cx - size / 2f).toInt(), top, (cx + size / 2f).toInt(), top + size)
            badge.setTint(color)
            badge.draw(canvas)
        }
    }
}
