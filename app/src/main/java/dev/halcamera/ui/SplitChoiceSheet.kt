package dev.halcamera.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.view.ViewCompat
import dev.halcamera.camera.*

/** Renders Split choices; the draft owns combination, count and ordering rules. Layout comes from dragging panels. */
internal class SplitChoiceSheet(private val context: Context) {
    fun show(plans: List<ConcurrentPlan>, ids: List<String>, apply: (List<String>) -> Boolean) {
        val draft = SplitSelection(plans,ids)
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun section(title: String) { content.addView(text(title,12).apply { ViewCompat.setAccessibilityHeading(this,true) },lp(12)) }
        fun spinner(title: String,values: List<String>,selection: Int,onChange: (Int) -> Unit): Spinner {
            section(title)
            val control = Spinner(context).apply {
                adapter = ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,values)
                setSelection(selection.coerceAtLeast(0)); contentDescription = title
                isEnabled = values.size > 1
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                    override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) = onChange(position)
                }
            }
            content.addView(control,LinearLayout.LayoutParams(-1,dp(48))); return control
        }
        val counts = draft.counts
        val cameras = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun renderDevices() {
            cameras.removeAllViews()
            draft.candidates.forEach { plan ->
                val ids = plan.streams.map { it.camera.id }; val chosen = ids.toSet() == draft.ids.toSet()
                cameras.addView(button((if (chosen) "✓ " else "")+plan.label) {
                    draft.selectPlan(plan); renderDevices()
                }.apply {
                    isEnabled = plan.advertised
                    alpha = if (isEnabled) 1f else .4f
                    isSelected = chosen; ViewCompat.setStateDescription(this,if (chosen) "Selected" else "Not selected")
                    contentDescription = "$text. ${if (plan.advertised) "Advertised combination" else "Requires device preflight"}"
                },LinearLayout.LayoutParams(-1,dp(48)))
            }
            val orderChoices = draft.ids.sorted()
            draft.ids.forEachIndexed { slot, id ->
                cameras.addView(row().apply {
                    addView(text("Screen ${slot+1}",12),LinearLayout.LayoutParams(0,dp(48),1f))
                    addView(Spinner(context).apply {
                        adapter = ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,
                            orderChoices.map { choice -> "Camera $choice" })
                        setSelection(orderChoices.indexOf(id))
                        contentDescription = "Camera for Split screen ${slot+1}"
                        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                            override fun onNothingSelected(parent: AdapterView<*>?) {}
                            override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,selectedId: Long) {
                                if (parent?.isAttachedToWindow != true) return
                                val choice = orderChoices[position]
                                if (draft.assign(slot,choice)) renderDevices()
                            }
                        }
                    },LinearLayout.LayoutParams(0,dp(48),1f))
                },lp(4))
            }
        }
        spinner("Cameras",counts.map { it.toString() },counts.indexOf(draft.count)) {
            draft.selectCount(counts[it])
            renderDevices()
        }
        section("Camera devices"); content.addView(cameras,lp()); renderDevices()
        content.addView(text("Hold a camera name and drag it to an edge or corner to arrange the screens.",12).apply {
            setTextColor(Look.onDarkMuted)
        },lp(12))
        LiveChoiceSheet.show(context,"Split",content = content,edge = LiveChoiceSheet.Edge.TOP,
            actionLabel = "Apply",confirm = { draft.valid && apply(draft.ids) })
    }
    private fun dp(value: Int) = Look.dp(context,value)
    private fun text(value: String,size: Int) = Look.text(context,value,size,Look.onDark).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun button(value: String,action: () -> Unit) = CameraWidgets(context).chromeButton(value,action)
    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(top) }
}