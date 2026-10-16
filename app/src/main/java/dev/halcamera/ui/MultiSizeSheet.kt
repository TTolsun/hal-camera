package dev.halcamera.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.view.ViewCompat
import dev.halcamera.camera.*

/** Preview and JPEG size per Multi camera, listed in screen order. Choices come from [ConcurrentPlanner]; Apply returns them. */
internal class MultiSizeSheet(private val context: Context) {
    fun show(streams: List<ConcurrentStream>, apply: (Map<String, Pair<LiveSize, LiveSize>>) -> Boolean) {
        val chosen = streams.associate { it.camera.id to (it.preview to it.photo) }.toMutableMap()
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        streams.forEachIndexed { index, stream ->
            val camera = stream.camera
            content.addView(text("${index+1} · ${camera.label}",12).apply { ViewCompat.setAccessibilityHeading(this,true) },lp(if (index == 0) 0 else 16))
            fun picker(title: String, choices: List<LiveSize>, current: LiveSize, set: (LiveSize) -> Unit) {
                content.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(text(title,12),LinearLayout.LayoutParams(0,dp(48),1f))
                    addView(Spinner(context).apply {
                        adapter = ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,choices.map { it.toString().replace('x','×') })
                        setSelection(choices.indexOf(current).coerceAtLeast(0))
                        isEnabled = choices.size > 1; contentDescription = "$title size for ${camera.label}"
                        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                            override fun onNothingSelected(parent: AdapterView<*>?) {}
                            override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) = set(choices[position])
                        }
                    },LinearLayout.LayoutParams(0,dp(48),1f))
                },lp(4))
            }
            picker("Preview",ConcurrentPlanner.previewChoices(camera),stream.preview) { chosen[camera.id] = it to chosen.getValue(camera.id).second }
            picker("JPEG",ConcurrentPlanner.photoChoices(camera),stream.photo) { chosen[camera.id] = chosen.getValue(camera.id).first to it }
        }
        content.addView(text("Video uses the preview size. Sizes above 1280×720 preview or 1920×1440 JPEG are not guaranteed for concurrent cameras.",12).apply {
            setTextColor(Look.onDarkMuted)
        },lp(16))
        LiveChoiceSheet.show(context,"Sizes",content = content,edge = LiveChoiceSheet.Edge.TOP,
            actionLabel = "Apply",confirm = { apply(chosen.toMap()) })
    }
    private fun dp(value: Int) = Look.dp(context,value)
    private fun text(value: String,size: Int) = Look.text(context,value,size,Look.onDark).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(top) }
}
