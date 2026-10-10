package dev.halcamera.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Shared HAL-CAMERA-Editorial tokens; platform mappings live in docs/design/DESIGN.md. */
object Look {
    val canvas = Color.WHITE
    val card = Color.WHITE
    val labSurface = Color.parseColor("#f5f5f7")
    val hairline = Color.parseColor("#e2e4e8")
    val ink = Color.parseColor("#1d1d1f")
    val inkMuted = Color.parseColor("#62666d")
    val primary = Color.parseColor("#0066cc")
    val onPrimary = Color.WHITE
    val expertTile = Color.parseColor("#18191b")
    val expertTile2 = Color.parseColor("#202124")
    val expertTile3 = Color.parseColor("#303238")
    val onDark = Color.WHITE
    val onDarkMuted = Color.parseColor("#bdc1c7")
    // Dark camera adaptation of the same Editorial palette.
    val cameraSurface = expertTile
    val cameraCard = expertTile2
    val cameraOutline = expertTile3
    val cameraGlass = Color.argb(220, 24, 25, 27)
    val cameraControlGlass = Color.argb(120, 24, 25, 27)
    val cameraSelection = Color.WHITE
    val cameraOnSelection = ink
    val primaryOnDark = Color.parseColor("#2997ff")
    // Runway-inspired surfaces are scoped to Gallery and About.
    val gallerySurface = Color.parseColor("#111111")
    val galleryControl = Color.parseColor("#252525")
    val galleryOutline = Color.parseColor("#454545")
    val runwayInk = Color.parseColor("#030303")
    val runwayMuted = Color.parseColor("#676f7b")
    val statusPass = Color.parseColor("#34c759")
    val statusWarn = Color.parseColor("#ff9500")
    val statusFail = Color.parseColor("#ff3b30")
    val statusUnknown = inkMuted
    val successInk = Color.parseColor("#18794e")
    val warningInk = Color.parseColor("#9a5700")
    val failureInk = Color.parseColor("#c62828")

    fun statusColor(level: String): Int = when (level.lowercase()) {
        "normal", "pass", "ok" -> statusPass
        "warning", "warn", "watch" -> statusWarn
        "issue", "fail" -> statusFail
        else -> statusUnknown
    }

    fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun cardBackground(context: Context, fill: Int = card, stroke: Int = hairline) = GradientDrawable().apply {
        setColor(fill)
        val light = fill == card || fill == labSurface || fill == canvas
        cornerRadius = dp(context, if (light) 18 else 4).toFloat()
        if (!light || stroke != hairline) setStroke(dp(context, 1), stroke)
    }

    fun pill(context: Context, fill: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(context, 999).toFloat() }

    fun galleryButton(context: Context, label: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 15f
        val foreground = if (primary) Color.BLACK else Color.WHITE
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Color.GRAY, foreground)))
        val shape = pill(context, if (primary) Color.WHITE else galleryControl).apply {
            if (!primary) setStroke(dp(context, 1), galleryOutline)
        }
        background = RippleDrawable(ColorStateList.valueOf(0x40888888), shape, pill(context, Color.WHITE))
        backgroundTintList = null; stateListAnimator = null
        setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 12))
        minHeight = dp(context, 48); minimumHeight = dp(context, 48)
        setOnClickListener { action() }
    }

    fun touchBackground(context: Context, fill: Int, stroke: Int) = RippleDrawable(
        ColorStateList.valueOf(Color.argb(48, 128, 170, 220)),
        cardBackground(context, fill, stroke), cardBackground(context, Color.WHITE, Color.WHITE)
    )

    /**
     * A typeface whose digits and letters all occupy one cell, which is what every table in this app assumes:
     * the presenters align their columns with [String.padEnd] and the result is only a table if the face is
     * monospaced.
     *
     * [Typeface.MONOSPACE] is not enough. It names the `monospace` family, and a device whose system font has
     * been substituted resolves that name to the substituted face: on a Galaxy S25+ ten M glyphs, ten i glyphs
     * and ten digits all came out different widths, so every table on the device was ragged. Loading the font
     * file directly bypasses the family name, and the file is the one the platform's own `monospace` alias
     * points at, so nothing is bundled and nothing is licensed differently from the system.
     *
     * A device without any of these files falls back to the family name, which is no worse than before.
     */
    val mono: Typeface by lazy {
        val candidates = listOf(
            "/system/fonts/DroidSansMono.ttf",
            "/system/fonts/RobotoMono-Regular.ttf",
            "/system/fonts/CutiveMono.ttf"
        )
        candidates.asSequence()
            .mapNotNull { path -> runCatching { Typeface.createFromFile(path) }.getOrNull() }
            .firstOrNull { it.isMonospaced() }
            ?: Typeface.MONOSPACE
    }

    /** Measures the face rather than trusting its name: two glyphs of very different shape must be equally wide. */
    private fun Typeface.isMonospaced(): Boolean {
        val paint = android.graphics.Paint().apply { typeface = this@isMonospaced; textSize = 100f }
        val wide = paint.measureText("M")
        val narrow = paint.measureText("i")
        return wide > 0f && kotlin.math.abs(wide - narrow) < 0.5f
    }

    fun text(context: Context, s: CharSequence, sizeSp: Int, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(context).apply {
        text = s; textSize = sizeSp.toFloat(); setTextColor(color)
        typeface = if (mono) Look.mono else if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        if (sizeSp >= 17) letterSpacing = -0.02f
        setLineSpacing(0f, if (sizeSp <= 14) 1.35f else 1.15f)
    }

    fun primaryButton(context: Context, label: String, action: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 17f; setTextColor(onPrimary); background = RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), pill(context, primary), pill(context, Color.WHITE))
        backgroundTintList = null
        setPadding(dp(context, 24), dp(context, 14), dp(context, 24), dp(context, 14)); stateListAnimator = null
        minHeight = dp(context, 56); minimumHeight = dp(context, 56)
        setOnClickListener { action() }
    }

    fun ghostButton(context: Context, label: String, dark: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 15f
        setTextColor(if (dark) primaryOnDark else primary)
        background = if (dark) touchBackground(context, expertTile2, expertTile3) else RippleDrawable(ColorStateList.valueOf(0x180066CC), pill(context, labSurface), pill(context, Color.WHITE))
        backgroundTintList = null
        setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 12)); stateListAnimator = null
        minHeight = dp(context, 48); minimumHeight = dp(context, 48)
        setOnClickListener { action() }
    }

    /**
     * Layout params for a button row: the height grows with the text instead of being pinned.
     *
     * A pinned height clipped the label as soon as the padding plus the line box exceeded it, which happens
     * at the default font scale on a 17sp primary button and on every button at a large display size. The
     * buttons carry their own minimum height, so the tap target is unchanged.
     */
    fun buttonParams(width: Int = -1, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT, weight)

    fun card(context: Context, dark: Boolean = false) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = if (dark) cardBackground(context, expertTile2, expertTile3) else cardBackground(context, labSurface)
        setPadding(dp(context, 18), dp(context, 16), dp(context, 18), dp(context, 16))
    }

    fun row(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    /**
     * The title row every screen below LIVE opens with: the back icon to the left of the name, as in the gallery.
     * One place and one symbol, so the way out is never looked for twice. The row is returned so a screen can add
     * trailing controls after the title.
     */
    fun isLight(context: Context): Boolean {
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.windowLightStatusBar, value, true)
        return value.data != 0
    }

    fun pageMargin(view: android.view.View, horizontalInsets: Int): Int {
        val width = view.width.takeIf { it > 0 } ?: view.resources.displayMetrics.widthPixels
        return maxOf(dp(view.context, 20), (width - horizontalInsets - dp(view.context, 680)) / 2)
    }

    fun configureLabWindow(activity: android.app.Activity) {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    /** A quiet back link above the page heading, shared by every Lab destination. */
    fun titleBar(context: Context, title: CharSequence, sizeSp: Int, backLabel: String, onBack: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(ghostButton(context, "‹ Back") { onBack() }.apply {
            contentDescription = backLabel
            background = RippleDrawable(ColorStateList.valueOf(0x180066CC), null, pill(context, Color.WHITE))
            setPadding(dp(context, 4), 0, dp(context, 16), 0)
        }, LinearLayout.LayoutParams(-2, -2))
        addView(text(context, title, if (title.length > 28) 26 else 34, ink, bold = true).apply {
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 8); bottomMargin = dp(context, 16) })
    }

    /** Secondary information stays available without displacing the primary task. */
    fun disclosure(context: Context, title: String, content: android.view.View, initiallyExpanded: Boolean = false) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        content.visibility = if (initiallyExpanded) android.view.View.VISIBLE else android.view.View.GONE
        val toggle = ghostButton(context, "$title ${if (initiallyExpanded) "▴" else "▾"}", dark = false) {}
        toggle.minHeight = dp(context, 48)
        toggle.setOnClickListener {
            val expanded = content.visibility != android.view.View.VISIBLE
            content.visibility = if (expanded) android.view.View.VISIBLE else android.view.View.GONE
            toggle.text = "$title ${if (expanded) "▴" else "▾"}"
            androidx.core.view.ViewCompat.setStateDescription(toggle, if (expanded) "펼쳐짐" else "접힘")
        }
        androidx.core.view.ViewCompat.setStateDescription(toggle, if (initiallyExpanded) "펼쳐짐" else "접힘")
        addView(toggle, LinearLayout.LayoutParams(-1, -2))
        addView(content, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 8) })
    }
}
