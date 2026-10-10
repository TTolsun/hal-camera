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
    private data class JpegChoice(val size: LiveSize?, val fromYuv: Boolean = false)
    data class Page(val root: LinearLayout, val scroll: ScrollView)
    fun create(context: Context, support: LiveStreamSupport, current: LiveStreamSettings,
             actual: String, restore: (() -> Unit)?, back: () -> Unit, changed: (LiveStreamSettings) -> Unit, apply: (LiveStreamSettings) -> Unit,
             backDescription: String = "Back to Lab", baseline: LiveStreamSettings = current,
             engine: String = "Camera2", multiMaxDevices: Int = 0, multiDeviceCount: Int = 0,
             multiBaseline: Int = multiMaxDevices, multiChanged: (Int) -> Unit = {}, multiOnly: Boolean = false): Page {
        val themed = context
        fun dp(value: Int) = Look.dp(themed, value)
        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL

        }
        content.addView(Look.titleBar(themed, "Live Streams", 24, backDescription, back))
        fun section(title: String): LinearLayout {
            content.addView(Look.text(themed, title, 13, Look.inkMuted, bold = true).apply {
                visibility = if (multiOnly && title != "Multi") View.GONE else View.VISIBLE
            },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(8) })
            return LinearLayout(themed).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (multiOnly && title != "Multi") View.GONE else View.VISIBLE
                background = Look.cardBackground(themed, Look.labSurface)
                clipToOutline = true
                content.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        val refreshers = mutableListOf<() -> Unit>()
        fun <T> choice(group: LinearLayout, label: String, values: () -> List<T>, selected: () -> T, change: (T) -> Unit,
                       hint: () -> String? = { null }, display: (T) -> String) {
            val value = selected()
            if (group.childCount > 0) group.addView(View(themed).apply { setBackgroundColor(Look.hairline) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(18); marginEnd = dp(18) })
            val valueText = Look.text(themed, display(value), 15, Look.primary)
            val hintText = Look.text(themed, "", 13, Look.inkMuted)
            val words = LinearLayout(themed).apply {
                orientation = LinearLayout.VERTICAL
                addView(Look.text(themed, label, 17, Look.ink))
                addView(valueText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                addView(hintText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
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
                hintText.text = hint().orEmpty()
                hintText.visibility = if (hintText.text.isEmpty()) View.GONE else View.VISIBLE
                row.contentDescription = listOfNotNull(label, display(selected()), hint()).joinToString(", ")
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
                    }.setNegativeButton("Cancel", null).show().also { if (label == "JPEG") it.listView.setSelection(0) }
            }
            group.addView(row, LinearLayout.LayoutParams(-1, -2))

        }
        fun sizes(values: List<LiveSize>) = values.sortedByDescending { it.width.toLong() * it.height }
        var preview = current.preview
        var yuv = current.yuv
        var jpeg = current.jpeg
        var fps = current.fps
        var stabilization = current.stabilization
        var jpegFromYuv = current.jpegFromYuv
        var raw = current.raw
        var multiLimit = multiMaxDevices
        if (multiDeviceCount >= 2) {
            choice(section("Multi"), "Maximum camera devices", { listOf(0) + (2..multiDeviceCount).toList() },
                { multiLimit }, { multiLimit = it; multiChanged(it) }) { if (it == 0) "All available" else it.toString() }
        }
        val outputs = section("Outputs")
        choice(outputs, "Preview", { sizes(support.preview) }, { preview }, { preview = it }) { it.toString() }
        choice(outputs, "YUV", { sizes(support.yuv) + listOf(null) }, { yuv }, {
            yuv = it; if (it == null) jpegFromYuv = false
        }) { it?.toString() ?: "Off" }
        choice(outputs, "JPEG", {
            listOfNotNull(if (yuv != null) JpegChoice(null, true) else null) +
                sizes(support.jpeg).map { JpegChoice(it) } + JpegChoice(null)
        }, { JpegChoice(jpeg, jpegFromYuv) }, { jpeg = it.size; jpegFromYuv = it.fromYuv },
            hint = { if (jpegFromYuv) "App converts YUV to JPEG." else null }) {
            if (it.fromYuv) "YUV ($yuv)" else it.size?.toString() ?: "Off"
        }
        choice(outputs, "RAW (DNG)", { if (support.raw.isEmpty()) emptyList() else sizes(support.raw) + listOf(null) },
            { raw }, { raw = it }) { it?.toString() ?: "Off" }
        val exportHint = Look.text(themed, "", 13, Look.inkMuted).apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        outputs.addView(exportHint)
        refreshers += {
            exportHint.text = when {
                engine == "CameraX" -> "RAW: unavailable in CameraX"
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
        refreshers += { changed(LiveStreamSettings(preview, yuv, jpeg, fps, video.requested, stabilization, jpegFromYuv, raw)) }
        if (actual.startsWith("Failed:") || actual.startsWith("실패:")) {
            section("Status").addView(Look.text(themed, actual, 13, Look.inkMuted).apply {
                setPadding(dp(18), dp(14), dp(18), dp(14))
            })
            if (restore != null) content.addView(Look.ghostButton(themed, "Restore previous settings", action = restore),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        val scroll = ScrollView(themed).apply { isFillViewport = true; addView(content) }
        val applyButton = Look.primaryButton(themed, "Apply") {
            val settings = LiveStreamSettings(preview, yuv, jpeg, fps, video.requested, stabilization, jpegFromYuv, raw)
            val rejection = if (multiOnly) null else support.rejection(settings)
            if (rejection == null) apply(settings)
            else AlertDialog.Builder(themed, R.style.LabDialogTheme).setMessage(rejection).setPositiveButton("OK", null).show()
        }
        refreshers += {
            val pending = LiveStreamSettings(preview, yuv, jpeg, fps, video.requested, stabilization, jpegFromYuv, raw)
            applyButton.isEnabled = pending != baseline || multiLimit != multiBaseline || actual.startsWith("Failed:") || actual.startsWith("실패:")
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
