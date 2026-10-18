package dev.halcamera.ui

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import dev.halcamera.WorkbenchActivity
import dev.halcamera.camera.SplitArrangement
import dev.halcamera.camera.SplitLayout
import java.io.File

/** Real attached Views exercise timer ownership, click suppression and cancellation without capturing media. */
object SplitDragChecks {
    fun run(test: Instrumentation): String {
        fun ui(action: () -> Unit) {
            var error: Throwable? = null
            test.runOnMainSync { try { action() } catch (failure: Throwable) { error = failure } }
            error?.let { throw it }
        }
        val activity = test.startActivitySync(Intent(test.targetContext,WorkbenchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val distance = Look.dp(activity,24).toFloat()
        var enabled = true
        var clicks = 0
        var drops = 0
        var current = SplitArrangement.identity(2)
        lateinit var stage: FrameLayout
        lateinit var panel: FrameLayout
        lateinit var handle: TextView
        var down = 0L
        fun touch(action: Int,dx: Float = 0f,dy: Float = 0f) = ui {
            if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,20f+dx,20f+dy,0)
            try { handle.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        fun hold() { touch(MotionEvent.ACTION_DOWN); SystemClock.sleep(320); ui { check(panel.alpha < 1f) } }
        fun restored() = ui {
            check(panel.alpha == 1f && panel.translationX == 0f && panel.translationY == 0f)
        }
        try {
            ui {
                stage = FrameLayout(activity).apply { setBackgroundColor(Look.cameraSurface) }
                panel = FrameLayout(activity).apply { setBackgroundColor(Look.cameraCard) }
                handle = TextView(activity).apply {
                    text = "Sensor 2 · Touch test"; textSize = 14f; setTextColor(Look.onDark)
                    gravity = Gravity.CENTER; setOnClickListener { clicks++ }
                }
                panel.addView(handle,FrameLayout.LayoutParams(400,192))
                stage.addView(panel,FrameLayout.LayoutParams(400,800))
                activity.setContentView(stage)
                SplitPanelDrag(stage,{ Rect(0,0,stage.width,stage.height) },{ current },{ enabled }) {
                    current = it; drops++
                }.attach(handle,panel,1)
            }
            test.waitForIdleSync()
            ui {
                panel.layoutParams = FrameLayout.LayoutParams(stage.width/2,stage.height).apply { leftMargin = stage.width/2 }
                handle.layoutParams = FrameLayout.LayoutParams(-1,Look.dp(activity,48)).apply { topMargin = Look.dp(activity,96) }
            }
            test.waitForIdleSync()
            touch(MotionEvent.ACTION_DOWN); touch(MotionEvent.ACTION_UP)
            ui { check(clicks == 1 && drops == 0) }
            hold(); touch(MotionEvent.ACTION_UP); restored()
            ui { check(clicks == 1 && drops == 0) }
            hold(); touch(MotionEvent.ACTION_MOVE,dy = -distance)
            ui { check(panel.translationY == -distance && drops == 0) }
            SystemClock.sleep(100)
            test.waitForIdleSync()
            ui {
                val bitmap = Bitmap.createBitmap(stage.width,stage.height,Bitmap.Config.ARGB_8888)
                try {
                    stage.draw(Canvas(bitmap))
                    val directory = File(test.targetContext.getExternalFilesDir(null),"split-drag-checks").apply { mkdirs() }
                    File(directory,"top-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                } finally { bitmap.recycle() }
            }
            touch(MotionEvent.ACTION_UP,dy = -distance); restored()
            ui { check(drops == 1 && current.layout == SplitLayout.STACKED && current.order.first() == 1 && clicks == 1) }
            ui { current = SplitArrangement.identity(2) }
            hold(); touch(MotionEvent.ACTION_MOVE,dy = -distance)
            touch(MotionEvent.ACTION_MOVE,dy = -distance*.5f); touch(MotionEvent.ACTION_UP,dy = -distance*.5f)
            ui { check(drops == 1 && current.layout == SplitLayout.SIDE_BY_SIDE) }
            hold(); touch(MotionEvent.ACTION_MOVE,dy = -distance); touch(MotionEvent.ACTION_CANCEL); restored()
            ui { check(drops == 1) }
            hold(); touch(MotionEvent.ACTION_MOVE,dy = -distance)
            ui { enabled = false }; touch(MotionEvent.ACTION_UP,dy = -distance); restored()
            ui { check(drops == 1); enabled = true }
            hold(); touch(MotionEvent.ACTION_POINTER_DOWN); touch(MotionEvent.ACTION_UP); restored()
            ui { check(drops == 1 && clicks == 1) }
            // A detached handle must not run its pending pick-up timer.
            touch(MotionEvent.ACTION_DOWN); ui { panel.removeView(handle) }
            SystemClock.sleep(320); restored()
            ui { check(drops == 1) }
        } finally { ui { activity.finish() } }
        return "Split drag PASS: tap, 250ms hold, 24dp snap, one-to-one motion, return to cancel, ACTION_CANCEL, disabled drop, second pointer, detach"
    }
}
