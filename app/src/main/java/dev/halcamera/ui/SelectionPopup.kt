package dev.halcamera.ui

import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListPopupWindow
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** A checked choice list attached to its control; the platform places it above when needed. */
fun showSelectionPopup(anchor: View, items: List<String>, selected: Int, onSelect: (Int) -> Unit) =
    showPopup(anchor, items, selected, checked = true, onSelect)

/**
 * The same list without radio buttons, for items that go somewhere rather than set a value. A radio button says
 * which value is current, and a list of destinations has none, so every circle would sit empty.
 */
fun showActionPopup(anchor: View, items: List<String>, onSelect: (Int) -> Unit) =
    showPopup(anchor, items, -1, checked = false, onSelect)

private fun showPopup(anchor: View, items: List<String>, selected: Int, checked: Boolean, onSelect: (Int) -> Unit) {
    if (items.isEmpty() || !anchor.isAttachedToWindow || !anchor.isEnabled) return
    val context = anchor.context
    val layout = if (checked) android.R.layout.simple_list_item_single_choice else android.R.layout.simple_list_item_1
    val adapter = object : ArrayAdapter<String>(context, layout, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getView(position, convertView, parent) as TextView).apply {
                textSize = 14f
                minimumHeight = Look.dp(context, 48)
            }
    }
    val label = TextView(context).apply { textSize = 14f }
    val textWidth = items.maxOf { label.paint.measureText(it).toInt() }
    val maxWidth = (context.resources.displayMetrics.widthPixels - Look.dp(context, 24)).coerceAtLeast(1)
    val popup = ListPopupWindow(context).apply {
        anchorView = anchor
        width = (textWidth + Look.dp(context, 72)).coerceAtLeast(anchor.width).coerceAtMost(maxWidth)
        height = ViewGroup.LayoutParams.WRAP_CONTENT
        isModal = true
        inputMethodMode = ListPopupWindow.INPUT_METHOD_NOT_NEEDED
        setAdapter(adapter)
        setOnItemClickListener { _, _, position, _ ->
            dismiss()
            onSelect(position)
        }
    }
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    val observer = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) = popup.dismiss()
    }
    popup.setOnDismissListener { lifecycle?.removeObserver(observer) }
    lifecycle?.addObserver(observer)
    popup.show()
    popup.listView?.apply {
        if (checked) {
            choiceMode = ListView.CHOICE_MODE_SINGLE
            setItemChecked(selected, true)
            if (selected >= 0) setSelection(selected)
        }
        ViewCompat.setAccessibilityPaneTitle(this, anchor.contentDescription ?: (anchor as? TextView)?.text)
    }
}
