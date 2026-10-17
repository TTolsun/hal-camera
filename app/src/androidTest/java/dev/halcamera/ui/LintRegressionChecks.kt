package dev.halcamera.ui

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface
import dev.halcamera.camera.SplitArrangement
import java.io.File

/** Opt-in checks without camera capture or writes to the user's media library. */
object LintRegressionChecks {
    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        fun ui(action: () -> Unit) {
            var failure: Throwable? = null
            instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
            failure?.let { throw it }
        }
        val photo = File.createTempFile("lint-exif-", ".jpg", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)
            try { photo.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) } }
            finally { bitmap.recycle() }
            for (orientation in 1..8) {
                ExifInterface(photo).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }
                photo.inputStream().use {
                    check(ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) == orientation)
                }
            }
        } finally { check(photo.delete()) }

        fun tap(view: View) {
            val start = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                val event = MotionEvent.obtain(start, start + index * 20L, action, 20f, 20f, 0)
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
        val activity = instrumentation.startActivitySync(android.content.Intent(context, dev.halcamera.WorkbenchActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        var galleryClicks = 0
        var shutterClicks = 0
        var handleClicks = 0
        lateinit var gallery: GalleryImageView
        lateinit var shutter: ShutterButton
        lateinit var handle: TextView
        try {
            ui {
                val stage = FrameLayout(activity)
                activity.setContentView(stage)
                gallery = GalleryImageView(activity) { error("A tap must not page") }.apply {
                    setOnClickListener { galleryClicks++ }
                }
                shutter = ShutterButton(activity).apply {
                    setOnClickListener { shutterClicks++ }
                    bindBurstInput({ false }, { it() }, { error("Unexpected burst") }, { error("Unexpected release") })
                }
                handle = TextView(activity).apply { setOnClickListener { handleClicks++ } }
                val panel = FrameLayout(activity)
                SplitPanelDrag(stage, { null }, { SplitArrangement.identity(2) }, { false }, {}).attach(handle, panel, 0)
                listOf(gallery, shutter, handle).forEachIndexed { index, view ->
                    stage.addView(view, FrameLayout.LayoutParams(200, 200).apply { topMargin = index * 220 })
                }
            }
            instrumentation.waitForIdleSync()
            ui { tap(gallery); tap(shutter); tap(handle) }
            SystemClock.sleep(500) // Confirm the gallery tap and let Button's posted click run on an attached view.
            ui {
                check(galleryClicks == 1) { "Gallery tap count: $galleryClicks" }
                check(shutterClicks == 1) { "Shutter tap count: $shutterClicks" }
                check(handleClicks == 1) { "Split handle tap count: $handleClicks" }
                check(gallery.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                check(shutter.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                check(galleryClicks == 2 && shutterClicks == 2)
                val target = Bitmap.createBitmap(200, 40, Bitmap.Config.ARGB_8888)
                try {
                    val meter = MeterView(activity, .5f, 1f, false, baseBeyond = true)
                    meter.layout(0, 0, 200, 40)
                    repeat(30) { meter.draw(Canvas(target)) }
                    check(target.getPixel(199, 20) != 0) { "Reference arrow was not drawn" }
                } finally { target.recycle() }
            }
        } finally { ui { activity.finish() } }
        return "LINT_REGRESSION_OK: EXIF orientations 1..8; gallery/shutter click and accessibility; split tap; repeated meter drawing"
    }
}
