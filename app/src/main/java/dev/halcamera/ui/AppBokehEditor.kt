package dev.halcamera.ui

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Button
import android.widget.SeekBar
import androidx.core.view.ViewCompat
import dev.halcamera.R
import dev.halcamera.camera.AppBokehProcessor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** One captured image, one estimated map. All inference/render/save work stays off the UI thread. */
class AppBokehEditor(private val activity: Activity, private val source: Uri, private val closed: () -> Unit) {
    private val processor = AppBokehProcessor(activity)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val revision = AtomicInteger()
    @Volatile private var alive = true
    private var saving = false
    private var scene: AppBokehProcessor.Scene? = null
    private var result: Bitmap? = null
    private var depthImage: Bitmap? = null
    private var focusX = .5f
    private var focusY = .5f
    private var strength = 65
    private var display = 0
    private val dialog = Dialog(activity)
    private val info = Look.text(activity, "Estimating depth on this device…", 13, Look.onDarkMuted).apply { minLines = 2 }
    private val picture = Picture(activity) { x, y -> focusX = x; focusY = y; render() }
    private val save = Look.galleryButton(activity, "Save Bokeh", true) { saveCopy() }
    private val close = IconButton(activity, R.drawable.ic_action_close, "Close App Bokeh", dark = true) { if (!saving) dismiss() }
    private val viewButtons = mutableListOf<Button>()
    private val slider = SeekBar(activity).apply {
        max = 100; progress = strength; contentDescription = "Background blur strength"
        minimumHeight = Look.dp(activity, 48)
    }

    fun show() {
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Look.gallerySurface)
            val pad = Look.dp(activity, 16); setPadding(pad, pad, pad, pad)
        }
        body.addView(Look.row(activity).apply {
            addView(Look.text(activity, "App Bokeh", 20, Look.onDark, true).apply {
                ViewCompat.setAccessibilityHeading(this, true)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(close, LinearLayout.LayoutParams(Look.dp(activity, 48), Look.dp(activity, 48)))
        })
        body.addView(Look.text(activity, "Tap a subject. Adjust background blur.\nEstimated depth · Output up to 1600 px", 13, Look.onDarkMuted))
        body.addView(picture, LinearLayout.LayoutParams(-1, 0, 1f))
        val views = LinearLayout(activity).apply { isBaselineAligned = false }
        listOf("Bokeh", "Original", "Depth").forEachIndexed { index, label ->
            views.addView(Look.galleryButton(activity, label) { display = index; updateImage() }.apply {
                viewButtons += this
                textSize = 13f
                setSingleLine(true)
                setHorizontallyScrolling(false)
                androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this, 11, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                val padding = Look.dp(activity, 8)
                setPadding(padding, Look.dp(activity, 12), padding, Look.dp(activity, 12))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(views)
        body.addView(info)
        body.addView(slider, LinearLayout.LayoutParams(-1, Look.dp(activity, 48)))
        slider.isEnabled = false
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                strength = progress
                if (fromUser) { display = 0; updateImage(); render() }
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        body.addView(save, LinearLayout.LayoutParams(-1, -2))
        save.isEnabled = false
        updateImage()
        dialog.setContentView(body)
        dialog.setOnDismissListener { dispose() }
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        worker.execute {
            val loaded = runCatching { processor.load(source) }
            main.post {
                if (!alive) return@post
                loaded.fold({ scene = it; depthImage = AppBokehProcessor.depthBitmap(it.depth); slider.isEnabled = true; render() },
                    { info.text = "Depth estimation failed: ${it.message}. Original photo is saved." })
            }
        }
    }

    private fun render() {
        val current = scene ?: return
        if (!alive || saving) return
        val token = revision.incrementAndGet()
        val x = focusX; val y = focusY; val amount = strength
        save.isEnabled = false
        info.text = "Applying blur $amount%…"
        worker.execute {
            if (!alive || revision.get() != token) return@execute
            val rendered = runCatching { processor.render(current, x, y, amount) }
            main.post {
                if (!alive || revision.get() != token) return@post
                rendered.fold({ result = it; save.isEnabled = true; info.text = "Blur $amount% · Depth ${current.elapsedMs} ms · Original kept"; updateImage() },
                    { info.text = "Blur failed: ${it.message}. Original photo is saved." })
            }
        }
    }

    private fun updateImage() {
        viewButtons.forEachIndexed { index, button ->
            val selected = display == index
            button.isSelected = selected
            button.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            ViewCompat.setStateDescription(button, if (selected) "Selected" else "Not selected")
            button.background = RippleDrawable(ColorStateList.valueOf(Look.galleryOutline),
                Look.pill(activity, Look.galleryControl).apply {
                    setStroke(Look.dp(activity, if (selected) 2 else 1), if (selected) Look.cameraSelection else Look.galleryOutline)
                }, null)
        }
        picture.bitmap = when (display) { 1 -> scene?.original; 2 -> depthImage; else -> result ?: scene?.original }
        // The depth image is square internally, but corresponds to the entire original frame.
        picture.aspect = scene?.original?.let { it.width.toFloat() / it.height } ?: 1f
        picture.focusX = focusX; picture.focusY = focusY
        picture.contentDescription = listOf("Bokeh result", "Original photo", "Estimated relative depth")[display]
        picture.invalidate()
    }

    private fun saveCopy() {
        val current = scene ?: return
        val rendered = result ?: return
        if (!alive || saving || !save.isEnabled) return
        saving = true; save.isEnabled = false; close.isEnabled = false; slider.isEnabled = false; picture.isEnabled = false
        dialog.setCancelable(false)
        val x = focusX; val y = focusY; val amount = strength
        info.text = "Saving Bokeh and estimated depth…"
        worker.execute {
            val saved = runCatching { processor.save(current, rendered, source, x, y, amount) }
            main.post {
                if (!alive) return@post
                saving = false; slider.isEnabled = true; picture.isEnabled = true; save.isEnabled = true; close.isEnabled = true
                dialog.setCancelable(true)
                info.text = saved.fold({ "Saved Bokeh JPEG + depth PNG. Original kept." }, { "Save failed: ${it.message}" })
            }
        }
    }

    fun dismiss() { dialog.dismiss(); dispose() }
    private fun dispose() {
        if (!alive) return
        alive = false; revision.incrementAndGet(); worker.shutdown(); closed()
    }

    private class Picture(context: android.content.Context, val selected: (Float, Float) -> Unit) : View(context) {
        var bitmap: Bitmap? = null
        var aspect = 1f
        var focusX = .5f; var focusY = .5f
        private val rect = RectF()
        private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Look.cameraActive; style = Paint.Style.STROKE; strokeWidth = Look.dp(context, 2).toFloat() }
        init { isClickable = true; isFocusable = true; contentDescription = "Bokeh result: tap subject to keep sharp" }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val image = bitmap ?: return
            val w = minOf(width.toFloat(), height * aspect); val h = w / aspect
            rect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            canvas.drawBitmap(image, null, rect, imagePaint)
            canvas.drawCircle(rect.left + w * focusX, rect.top + h * focusY, Look.dp(context, 12).toFloat(), ring)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isEnabled || bitmap == null || !rect.contains(event.x, event.y)) return false
            if (event.action == MotionEvent.ACTION_UP) {
                selected((event.x - rect.left) / rect.width(), (event.y - rect.top) / rect.height())
                performClick()
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
