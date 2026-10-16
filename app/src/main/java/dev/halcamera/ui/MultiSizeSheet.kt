package dev.halcamera.ui

import android.content.Context
import android.view.Gravity
import android.widget.*
import androidx.core.view.ViewCompat
import dev.halcamera.camera.*

/** Compact per-camera output pairs. A dismissed sheet never applies its draft. */
internal class MultiSizeSheet(private val context: Context) {
    fun show(streams: List<ConcurrentStream>, apply: (Map<String, Pair<LiveSize, LiveSize>>) -> Boolean) {
        val chosen = streams.associate { it.camera.id to (it.preview to it.photo) }.toMutableMap()
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        streams.forEachIndexed { index, stream ->
            val camera = stream.camera
            content.addView(text("${index+1} · ${camera.label}",12).apply {
                ViewCompat.setAccessibilityHeading(this,true)
            },lp(if (index == 0) 0 else 16))
            val outputs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            fun picker(title: String, choices: List<LiveSize>, current: LiveSize, set: (LiveSize) -> Unit) {
                var selected = choices.indexOf(current).coerceAtLeast(0)
                fun caption() = "$title\n${choices[selected].toString().replace('x','×')}  ›"
                lateinit var value: Button
                value = CameraWidgets(context).chromeButton(caption()) {
                    LiveChoiceSheet.show(context,"${camera.label} · $title",
                        choices.map { LiveChoiceSheet.Choice(it.toString().replace('x','×')) },selected,
                        edge = LiveChoiceSheet.Edge.TOP) { next ->
                        selected = next; set(choices[next]); value.text = caption()
                        value.contentDescription = "${camera.label}, $title ${choices[next]}, change size"
                    }
                }.apply {
                    setSingleLine(false); maxLines = 2; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    background = CameraWidgets(context).chrome(Look.cameraControlGlass,outlined = true)
                    setPadding(dp(12),dp(8),dp(12),dp(8))
                    isEnabled = choices.size > 1
                    contentDescription = "${camera.label}, $title $current, change size"
                }
                outputs.addView(value,LinearLayout.LayoutParams(0,dp(64),1f).apply {
                    if (outputs.childCount > 0) marginStart = dp(8)
                })
            }
            picker("Preview",ConcurrentPlanner.previewChoices(camera),stream.preview) { chosen[camera.id] = it to chosen.getValue(camera.id).second }
            picker("JPEG",ConcurrentPlanner.photoChoices(camera),stream.photo) { chosen[camera.id] = chosen.getValue(camera.id).first to it }
            content.addView(outputs,lp(8))
        }
        content.addView(text("Video uses Preview size.",12).apply { setTextColor(Look.onDarkMuted) },lp(12))
        LiveChoiceSheet.show(context,"Sizes",content = content,edge = LiveChoiceSheet.Edge.TOP,
            actionLabel = "Apply",confirm = { apply(chosen.toMap()) })
    }
    private fun dp(value: Int) = Look.dp(context,value)
    private fun text(value: String,size: Int) = Look.text(context,value,size,Look.onDark).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(top) }
}
