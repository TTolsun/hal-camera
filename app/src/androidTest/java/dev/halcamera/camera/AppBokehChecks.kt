package dev.halcamera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

object AppBokehChecks {
    fun run(context: Context, path: String, focusX: Float = .5f, focusY: Float = .5f): String {
        val processor = AppBokehProcessor(context)
        val source = Uri.fromFile(File(path))
        val scene = processor.load(source)
        check(scene.depth.width == 256 && scene.depth.height == 256)
        check(scene.original.width <= 1600 && scene.original.height <= 1600)
        val dir = File(context.getExternalFilesDir(null), "app-bokeh-check").apply { mkdirs() }
        File(dir, "original.png").outputStream().use { scene.original.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "depth.png").outputStream().use { AppBokehProcessor.depthBitmap(scene.depth).compress(Bitmap.CompressFormat.PNG, 100, it) }
        for (strength in listOf(50, 100)) {
            val rendered = processor.render(scene, focusX, focusY, strength)
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
        return "APP_BOKEH_OK source=${scene.original.width}x${scene.original.height} depth=256x256 focus=$focusX,$focusY loadAndInferenceMs=${scene.elapsedMs} saved=${artifacts.joinToString { it.name }}"
    }
}
