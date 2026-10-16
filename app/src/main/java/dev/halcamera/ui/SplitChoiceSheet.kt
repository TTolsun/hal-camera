package dev.halcamera.ui

import android.content.Context
import android.view.Gravity
import android.widget.*
import androidx.core.view.ViewCompat
import dev.halcamera.camera.*

/** Renders Split choices; the draft owns combination, count and ordering rules. Layout comes from dragging panels. */
internal class SplitChoiceSheet(private val context: Context) {
    fun show(plans: List<ConcurrentPlan>, ids: List<String>, edge: LiveChoiceSheet.Edge = LiveChoiceSheet.Edge.TOP,
             apply: (List<String>) -> Boolean) {
        val draft = SplitSelection(plans,ids)
        var showUnavailable = false
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun section(title: String) { content.addView(text(title,12).apply { ViewCompat.setAccessibilityHeading(this,true) },lp(12)) }
        fun picker(title: String,values: List<String>,selection: Int,onChange: (Int) -> Unit) {
            val control = CameraWidgets(context).choiceButton(title,values,selection,edge,
                caption = { if (values.size == 1) it else "$it  ›" },onChange = onChange)
            content.addView(row().apply {
                addView(text(title,12),LinearLayout.LayoutParams(0,dp(48),1f))
                addView(control,LinearLayout.LayoutParams(-2,dp(48)))
            },lp())
        }
        val counts = draft.counts
        val cameras = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun renderDevices() {
            cameras.removeAllViews()
            fun addPlan(plan: ConcurrentPlan) {
                val ids = plan.streams.map { it.camera.id }; val chosen = ids.toSet() == draft.ids.toSet()
                cameras.addView(button((if (chosen) "✓ " else "")+plan.label) {
                    draft.selectPlan(plan); renderDevices()
                }.apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    background = Look.cardBackground(context,Look.cameraControlGlass,
                        if (chosen) Look.cameraSelection else Look.cameraOutline)
                    setPadding(dp(12),0,dp(12),0)
                    isEnabled = plan.advertised
                    alpha = if (isEnabled) 1f else .4f
                    isSelected = chosen; ViewCompat.setStateDescription(this,if (chosen) "Selected" else "Not selected")
                    contentDescription = "$text. ${if (plan.advertised) "Advertised combination" else "Requires device preflight"}"
                },LinearLayout.LayoutParams(-1,dp(48)).apply { topMargin = dp(8) })
            }
            draft.candidates.filter { it.advertised }.forEach(::addPlan)
            val unavailable = draft.candidates.filterNot { it.advertised }
            if (unavailable.isNotEmpty()) {
                cameras.addView(button("${if (showUnavailable) "⌃" else "⌄"}  Unavailable combinations · ${unavailable.size}") {
                    showUnavailable = !showUnavailable; renderDevices()
                }.apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL; setTextColor(Look.onDarkMuted)
                    ViewCompat.setStateDescription(this,if (showUnavailable) "Expanded" else "Collapsed")
                },LinearLayout.LayoutParams(-1,dp(48)))
                if (showUnavailable) unavailable.forEach(::addPlan)
            }
            cameras.addView(text("Screen order",12).apply {
                setTextColor(Look.onDarkMuted); ViewCompat.setAccessibilityHeading(this,true)
            },lp(12))
            val orderChoices = draft.ids.sorted()
            val cameraNames = plans.flatMap { it.streams }.associate { it.camera.id to it.camera.label }
            draft.ids.forEachIndexed { slot, id ->
                cameras.addView(row().apply {
                    addView(text("Screen ${slot+1}",12),LinearLayout.LayoutParams(0,dp(48),1f))
                    addView(CameraWidgets(context).choiceButton("Camera for Split screen ${slot+1}",
                        orderChoices.map { cameraNames.getValue(it) },orderChoices.indexOf(id),edge) { next ->
                        if (draft.assign(slot,orderChoices[next])) renderDevices()
                    },LinearLayout.LayoutParams(0,dp(48),1f))
                },lp(4))
            }
        }
        picker("Cameras",counts.map { it.toString() },counts.indexOf(draft.count)) {
            draft.selectCount(counts[it])
            renderDevices()
        }
        section("Camera devices"); content.addView(cameras,lp()); renderDevices()
        content.addView(text("Hold and drag a camera ID to move its preview.",12).apply {
            setTextColor(Look.onDarkMuted)
        },lp(12))
        LiveChoiceSheet.show(context,"Split",content = content,edge = edge,
            actionLabel = "Apply",confirm = { draft.valid && apply(draft.ids) })
    }
    private fun dp(value: Int) = Look.dp(context,value)
    private fun text(value: String,size: Int) = Look.text(context,value,size,Look.onDark).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun button(value: String,action: () -> Unit) = CameraWidgets(context).chromeButton(value,action)
    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(top) }
}
