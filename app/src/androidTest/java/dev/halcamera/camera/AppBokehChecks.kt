package dev.halcamera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

object AppBokehChecks {
    /** Reuse a local photo so editor checks never need another live capture. */
    fun ui(test: android.app.Instrumentation, path: String): String {
        fun <T> main(block: () -> T): T {
            val result = java.util.concurrent.atomic.AtomicReference<Result<T>>()
            test.runOnMainSync { result.set(runCatching(block)) }
            return result.get().getOrThrow()
        }
        fun nodes(view: android.view.View): Sequence<android.view.View> = sequence {
            yield(view)
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) yieldAll(nodes(view.getChildAt(i)))
        }
        fun root() = android.view.inspector.WindowInspector.getGlobalWindowViews().last { it.isShown }
        fun button(label: String) = nodes(root()).filterIsInstance<android.widget.Button>().first { it.text.toString() == label }
        val activity = test.startActivitySync(android.content.Intent(test.targetContext, dev.halcamera.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val editor = main { dev.halcamera.ui.AppBokehEditor(activity, Uri.fromFile(File(path))) {}.also { it.show() } }
        try {
            fun awaitReady() {
                val deadline = android.os.SystemClock.uptimeMillis() + 25_000
                while (!main { button("Save Bokeh").isEnabled }) {
                    check(android.os.SystemClock.uptimeMillis() < deadline)
                    android.os.SystemClock.sleep(100)
                }
            }
            awaitReady()
            main {
                check(button("Bokeh").isSelected)
                button("Original").performClick()
                check(button("Original").isSelected && !button("Bokeh").isSelected)
                button("Depth").performClick()
                check(button("Depth").isSelected)
            }
            for (strength in listOf(0, 50, 100)) {
                main {
                    val slider = nodes(root()).filterIsInstance<android.widget.SeekBar>().first()
                    check(slider.height >= (48 * activity.resources.displayMetrics.density).toInt())
                    check(slider.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                        android.os.Bundle().apply { putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, strength.toFloat()) }))
                    check(button("Bokeh").isSelected && !button("Depth").isSelected)
                }
                awaitReady()
                main {
                    check(nodes(root()).filterIsInstance<android.widget.TextView>().any { it.text.startsWith("Blur $strength%") })
                }
            }
            main { check(button("Save Bokeh").performClick()) }
            awaitReady()
            main { check(nodes(root()).filterIsInstance<android.widget.TextView>().any { it.text.startsWith("Saved Bokeh JPEG + depth PNG.") }) }
            main {
                val view = root()
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                File(test.targetContext.getExternalFilesDir(null), "app-bokeh-ux.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
                check(nodes(view).first { it.contentDescription == "Close App Bokeh" }.performClick())
            }
            return "APP_BOKEH_UI_OK selected comparison, accessible strength 0/50/100, saved Bokeh, shared close control"
        } finally { main { editor.dismiss(); activity.finish() } }
    }

    fun run(context: Context, path: String, focusX: Float = .5f, focusY: Float = .5f): String {
        val processor = AppBokehProcessor(context)
        val source = Uri.fromFile(File(path))
        val scene = processor.load(source)
        check(scene.depth.width == 256 && scene.depth.height == 256)
        check(scene.original.width <= 1600 && scene.original.height <= 1600)
        val dir = File(context.getExternalFilesDir(null), "app-bokeh-check").apply { mkdirs() }
        File(dir, "original.png").outputStream().use { scene.original.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "depth.png").outputStream().use { AppBokehProcessor.depthBitmap(scene.depth).compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Exact model floats allow a before/after renderer comparison without re-inference.
        java.io.DataOutputStream(File(dir, "depth.f32").outputStream()).use { output ->
            scene.depth.values.forEach(output::writeFloat)
        }
        val renderTimes = mutableListOf<Long>()
        for (strength in listOf(50, 100)) {
            val start = android.os.SystemClock.elapsedRealtime()
            val rendered = processor.render(scene, focusX, focusY, strength)
            renderTimes += android.os.SystemClock.elapsedRealtime() - start
            File(dir, "bokeh-$strength.jpg").outputStream().use { rendered.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            rendered.recycle()
        }
        val result = processor.render(scene, focusX, focusY, 80)
        File(dir, "bokeh.jpg").outputStream().use { result.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        val zero = processor.render(scene, focusX, focusY, 0)
        check(zero.sameAs(scene.original)) { "Zero strength must preserve source pixels" }
        val artifacts = processor.save(scene, result, source, focusX, focusY, 80)
        check(artifacts.size == 3 && artifacts.all { it.bytes > 0 })
        artifacts.filter { it.mime.startsWith("image/") }.forEach { artifact ->
            context.contentResolver.openInputStream(artifact.uri)!!.use { check(BitmapFactory.decodeStream(it) != null) }
        }
        return "APP_BOKEH_OK source=${scene.original.width}x${scene.original.height} depth=256x256 focus=$focusX,$focusY loadAndInferenceMs=${scene.elapsedMs} render50And100Ms=$renderTimes saved=${artifacts.joinToString { it.name }}"
    }
}
