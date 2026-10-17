package dev.halcamera.cts

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.SurfaceView
import android.widget.FrameLayout
import dev.halcamera.ui.Look

/** Keeps the runner's surface attached while covering its stale last frame between runs. */
// Constructed programmatically with required collaborators; never inflated from XML.
@android.annotation.SuppressLint("ViewConstructor")
internal class CtsPreviewFrame(context: Context, preview: SurfaceView) : FrameLayout(context) {
    val cover = Look.text(context, "", 12, Look.onDarkMuted).apply {
        gravity = Gravity.CENTER
        setBackgroundColor(Color.BLACK)
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(preview, LayoutParams(-1, -1))
        addView(cover, LayoutParams(-1, -1))
    }
}
