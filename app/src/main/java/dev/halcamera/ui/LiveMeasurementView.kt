package dev.halcamera.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** Fixed columns reserve every known AE/AF state; changing a result never changes geometry or type size. */
class LiveMeasurementView(context: Context, private val dual: Boolean = false) : LinearLayout(context) {
    private val row = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private val cells = List(4) {
        Look.text(context, "", 12, Look.onDark).apply {
            setSingleLine(); ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL
            fontFeatureSettings = "tnum"
            setShadowLayer(Look.dp(context,2).toFloat(),0f,0f,Color.BLACK)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }
    private val settings = Look.text(context, "", 12, Look.onDark).apply {
        gravity = Gravity.CENTER; visibility = View.GONE
        setShadowLayer(Look.dp(context,2).toFloat(),0f,0f,Color.BLACK)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        orientation = VERTICAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        cells.forEach { row.addView(it, LayoutParams(0, -2)) }
        addView(row, LayoutParams(-1, -2)); addView(settings, LayoutParams(-1, -2))
        bind(emptyMap())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if (w <= 0 || w == oldw) return
        val base = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,12f,resources.displayMetrics)
        val paint = Paint(cells.first().paint).apply { textSize = base }
        val reservedWidth = LiveMeasurementText.reservedLabels(dual).flatten().maxOf(paint::measureText) + 2f
        val gap = Look.dp(context,8)
        val cellWidth = (w-paddingLeft-paddingRight-gap*3).coerceAtLeast(4) / 4
        val scale = minOf(1f, cellWidth / reservedWidth)
        cells.forEachIndexed { index, cell ->
            cell.setTextSize(TypedValue.COMPLEX_UNIT_PX,base*scale)
            cell.layoutParams = LayoutParams(0,-2,1f).apply {
                if (index > 0) marginStart = gap
            }
        }
    }

    fun bind(values: Map<String,Any?>, fps: String? = null, details: String = "") {
        val fields = LiveMeasurementText.fields(values,fps)
        cells.zip(fields).forEach { (cell, text) -> if (cell.text.toString() != text) cell.text = text }
        if (settings.text.toString() != details) settings.text = details
        settings.visibility = if (details.isEmpty()) View.GONE else View.VISIBLE
        contentDescription = (fields + listOf(details).filter { it.isNotEmpty() }).joinToString(" · ")
    }
}
