package dev.halcamera.ui

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import dev.halcamera.R
import dev.halcamera.camera.*

/** A draft is committed only by Apply; dismissing a selector or this panel never changes the camera. */
object LiveStreamSettingsView {
    data class Page(val root: LinearLayout, val scroll: ScrollView)
    fun create(context: Context, support: LiveStreamSupport, current: LiveStreamSettings,
             actual: String, restore: (() -> Unit)?, back: () -> Unit, changed: (LiveStreamSettings) -> Unit, apply: (LiveStreamSettings) -> Unit,
             backDescription: String = "Back to Lab", baseline: LiveStreamSettings = current,
             engine: String = "Camera2"): Page {
        val themed = context
        fun dp(value: Int) = Look.dp(themed, value)
        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL

        }
        content.addView(Look.titleBar(themed, "Live Streams", 24, backDescription, back))
        fun section(title: String): LinearLayout {
            content.addView(Look.text(themed, title, 13, Look.inkMuted, bold = true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(8) })
            return LinearLayout(themed).apply {
                orientation = LinearLayout.VERTICAL
                background = Look.cardBackground(themed, Look.labSurface)
                clipToOutline = true
                content.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        val refreshers = mutableListOf<() -> Unit>()
        fun <T> choice(group: LinearLayout, label: String, values: () -> List<T>, selected: () -> T, change: (T) -> Unit, display: (T) -> String) {
            val value = selected()
            if (group.childCount > 0) group.addView(View(themed).apply { setBackgroundColor(Look.hairline) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(18); marginEnd = dp(18) })
            val valueText = Look.text(themed, display(value), 15, Look.primary)
            val words = LinearLayout(themed).apply {
                orientation = LinearLayout.VERTICAL
                addView(Look.text(themed, label, 17, Look.ink))
                addView(valueText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            }
            val row = Look.row(themed).apply {
                minimumHeight = dp(64)
                setPadding(dp(18), dp(14), dp(18), dp(14))
                background = Look.touchBackground(themed, Look.labSurface, Look.hairline)
                addView(words, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ImageView(themed).apply {
                    setImageResource(R.drawable.ic_action_next)
                    setColorFilter(Look.primary)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(12) })
                isFocusable = true
                contentDescription = "$label, ${display(value)}"
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = Button::class.java.name
                    }
                }
            }
            refreshers += {
                valueText.text = display(selected())
                row.contentDescription = "$label, ${display(selected())}"
                row.isEnabled = values().isNotEmpty()
                row.alpha = if (row.isEnabled) 1f else 0.5f
            }
            row.setOnClickListener {
                val options = values()
                AlertDialog.Builder(themed, R.style.LabDialogTheme).setTitle(label)
                    .setSingleChoiceItems(options.map(display).toTypedArray(), options.indexOf(selected())) { dialog, index ->
                        change(options[index])
                        refreshers.forEach { it() }
                        dialog.dismiss()
                    }.setNegativeButton("Cancel", null).show()
            }
            group.addView(row, LinearLayout.LayoutParams(-1, -2))

        }
        fun sizes(values: List<LiveSize>) = values.sortedByDescending { it.width.toLong() * it.height }
        var preview = current.preview
        var yuv = current.yuv
        var jpeg = current.jpeg
        var fps = current.fps
        var stabilization = current.stabilization
        var yuvSaveFormat = current.yuvSaveFormat
        var raw = current.raw
        val outputs = section("Outputs")
        choice(outputs, "Preview", { sizes(support.preview) }, { preview }, { preview = it }) { it.toString() }
        choice(outputs, "YUV", { sizes(support.yuv) + listOf(null) }, { yuv }, { yuv = it }) { it?.toString() ?: "Off" }
        choice(outputs, "JPEG", { sizes(support.jpeg) + listOf(null) }, { jpeg }, { jpeg = it }) { it?.toString() ?: "Off" }
        choice(outputs, "YUV Save Format", { if (yuv != null) support.yuvSaveFormats else emptyList() },
            { yuvSaveFormat }, { yuvSaveFormat = it }) { it.name }
        choice(outputs, "RAW (DNG)", { if (support.raw.isEmpty()) emptyList() else sizes(support.raw) + listOf(null) },
            { raw }, { raw = it }) { it?.toString() ?: "Off" }
        val exportHint = Look.text(themed, "", 13, Look.inkMuted).apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        outputs.addView(exportHint)
        refreshers += {
            exportHint.text = when {
                engine == "CameraX" -> "RAW / NV21: unavailable in CameraX"
                support.raw.isEmpty() -> support.rawUnavailableReason
                else -> raw?.let { "DNG ≈ ${it.width.toLong() * it.height * 2 / 1_000_000} MB/shot" }.orEmpty()
            }
            exportHint.visibility = if (exportHint.text.isEmpty()) View.GONE else View.VISIBLE
        }
        val timing = section("Frame Rate")
        choice(timing, "Preview FPS", { listOf(null) + support.fps }, { fps }, { fps = it }) { it?.toString() ?: "Auto" }
        val recording = section("Recording")
        val video = LiveVideoDraft(support, current.video)
        choice(recording, "Format", { video.formats }, { video.value?.codec.orEmpty() }, video::selectFormat) {
            it.ifEmpty { "Capabilities unavailable" }
        }
        choice(recording, "Resolution", { video.sizes() }, { video.value?.size }, { it?.let(video::selectSize) }) {
            it?.toString() ?: "Capabilities unavailable"
        }
        choice(recording, "Frame Rate", { video.rates() }, { video.value?.fps }, { it?.let(video::selectRate) }) {
            it?.let { rate -> "$rate fps" } ?: "Capabilities unavailable"
        }
        val stabilizationGroup = section("Stabilization")
        choice(stabilizationGroup, "Mode", { support.stabilization }, { stabilization }, { stabilization = it }) { it.label }
        stabilizationGroup.addView(Look.text(themed, support.stabilizationNotice, 13, Look.inkMuted).apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
        })
        refreshers += { changed(LiveStreamSettings(preview, yuv, jpeg, fps, video.requested, stabilization, yuvSaveFormat, raw)) }
        if (actual.startsWith("Failed:") || actual.startsWith("실패:")) {
            section("Status").addView(Look.text(themed, actual, 13, Look.inkMuted).apply {
                setPadding(dp(18), dp(14), dp(18), dp(14))
            })
            if (restore != null) content.addView(Look.ghostButton(themed, "Restore previous settings", action = restore),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        val scroll = ScrollView(themed).apply { isFillViewport = true; addView(content) }
        val applyButton = Look.primaryButton(themed, "Apply") {
            val settings = LiveStreamSettings(preview, yuv, jpeg, fps, video.requested, stabilization, yuvSaveFormat, raw)
            val rejection = support.rejection(settings)
            if (rejection == null) apply(settings)
            else AlertDialog.Builder(themed, R.style.LabDialogTheme).setMessage(rejection).setPositiveButton("OK", null).show()
        }
        refreshers += {
            val pending = LiveStreamSettings(preview, yuv, jpeg, fps, video.requested, stabilization, yuvSaveFormat, raw)
            applyButton.isEnabled = pending != baseline || actual.startsWith("Failed:") || actual.startsWith("실패:")
            applyButton.alpha = if (applyButton.isEnabled) 1f else 0.5f
            applyButton.contentDescription = if (applyButton.isEnabled) "Apply stream settings" else "No changes"
        }
        refreshers.forEach { it() }
        val root = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(applyButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        return Page(root, scroll)
    }
}
