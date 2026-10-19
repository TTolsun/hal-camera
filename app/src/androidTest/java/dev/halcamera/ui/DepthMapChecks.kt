package dev.halcamera.ui

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.FrameLayout
import dev.halcamera.WorkbenchActivity
import dev.halcamera.camera.DepthFrame
import dev.halcamera.camera.LiveSize
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Synthetic DEPTH16 display validation; deliberately makes no claim about real depth camera support. */
object DepthMapChecks {
    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, WorkbenchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val output = File(context.getExternalFilesDir(null), "depth-map-synthetic.png")
        var failure: Throwable? = null
        try {
            instrumentation.runOnMainSync {
                try {
                    val width = 160; val height = 120
                    val buffer = ByteBuffer.allocate(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (y in 0 until height) for (x in 0 until width) {
                        val person = x in 55..105 && y in 20..100
                        val mm = if (person) 1000 else 4000 + x * 20
                        val code = if (x < 12) 1 else 0
                        buffer.putShort(((code shl 13) or mm).toShort())
                    }
                    buffer.flip()
                    val frame = DepthFrame.render(buffer, width, height, width * 2, 2)
                    check(frame.centerMm == 1000)
                    val view = DepthMapView(activity, LiveSize(width, height), 90, false)
                    val stage = FrameLayout(activity).apply { setBackgroundColor(android.graphics.Color.BLACK); addView(view, FrameLayout.LayoutParams(-1, -1)) }
                    activity.setContentView(stage)
                    view.setChromeInsets(Look.dp(activity, 160), Look.dp(activity, 180))
                    view.bind(frame)
                    val metrics = context.resources.displayMetrics
                    stage.measure(android.view.View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, android.view.View.MeasureSpec.EXACTLY))
                    stage.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
                    check(view.contentDescription.toString().contains("Center 1.000 m"))
                    val image = Bitmap.createBitmap(stage.width, stage.height, Bitmap.Config.ARGB_8888)
                    try {
                        stage.draw(Canvas(image))
                        check((image.getPixel(stage.width / 2, stage.height / 2) and 255) > 0)
                        output.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    } finally { image.recycle() }
                } catch (error: Throwable) { failure = error }
            }
            failure?.let { throw it }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
        return "DEPTH_MAP_CHECKS_OK · synthetic stride decode, center distance, display and accessibility · ${output.absolutePath}"
    }
}
