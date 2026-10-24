package dev.halcamera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Offline single-RGB relative-depth inference. No camera extension, person detector or network. */
class AppBokehProcessor(context: Context) {
    private val context = context.applicationContext
    data class Scene(val original: Bitmap, val depth: RelativeDepth, val elapsedMs: Long)

    fun load(uri: Uri): Scene {
        val start = android.os.SystemClock.elapsedRealtime()
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Cannot read source photo" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
        val decoded = resolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        } ?: error("Cannot decode source photo")
        val exif = resolver.openInputStream(uri)!!.use { ExifInterface(it) }
        val matrix = Matrix().apply {
            if (exif.isFlipped) postScale(-1f, 1f)
            postRotate(exif.rotationDegrees.toFloat())
        }
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (upright !== decoded) decoded.recycle()
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(upright.width, upright.height))
        val original = Bitmap.createScaledBitmap(upright, (upright.width * scale).toInt().coerceAtLeast(1),
            (upright.height * scale).toInt().coerceAtLeast(1), true)
        if (original !== upright) upright.recycle()
        val depth = infer(original)
        return Scene(original, depth, android.os.SystemClock.elapsedRealtime() - start)
    }

    fun infer(bitmap: Bitmap): RelativeDepth {
        val model = context.assets.openFd("midas-v2_1.tflite").use { asset ->
            FileInputStream(asset.fileDescriptor).use { it.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength) }
        }
        return Interpreter(model, Interpreter.Options().setNumThreads(4)).use { interpreter ->
            val inputTensor = interpreter.getInputTensor(0)
            val outputTensor = interpreter.getOutputTensor(0)
            val shape = inputTensor.shape()
            check(inputTensor.dataType() == DataType.FLOAT32 && shape.contentEquals(intArrayOf(1, 256, 256, 3))) { "Unexpected depth input tensor" }
            check(outputTensor.dataType() == DataType.FLOAT32 && outputTensor.numElements() == 256 * 256) { "Unexpected depth output tensor" }
            val resized = Bitmap.createScaledBitmap(bitmap, 256, 256, true)
            val pixels = IntArray(256 * 256)
            resized.getPixels(pixels, 0, 256, 0, 0, 256, 256)
            if (resized !== bitmap) resized.recycle()
            val input = ByteBuffer.allocateDirect(pixels.size * 3 * 4).order(ByteOrder.nativeOrder())
            // Matches the official MiDaS Android model_opt.tflite example (RGB, mean 115, std 58).
            pixels.forEach { pixel -> for (shift in intArrayOf(16, 8, 0)) input.putFloat((((pixel ushr shift) and 255) - 115f) / 58f) }
            input.rewind()
            val output = ByteBuffer.allocateDirect(pixels.size * 4).order(ByteOrder.nativeOrder())
            interpreter.run(input, output)
            output.rewind()
            val raw = FloatArray(pixels.size)
            output.asFloatBuffer().get(raw)
            RelativeDepth.normalize(256, 256, raw)
        }
    }

    fun render(scene: Scene, x: Float, y: Float, strength: Int): Bitmap {
        val bitmap = scene.original
        if (strength == 0) return bitmap
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Bitmap.createBitmap(DepthBokeh.render(pixels, bitmap.width, bitmap.height, scene.depth, x, y, strength),
            bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    }

    fun save(scene: Scene, result: Bitmap, source: Uri, x: Float, y: Float, strength: Int): List<PhotoArtifact> {
        val library = MediaLibrary(context)
        val name = library.name() + "_APP_BOKEH"
        return library.transaction {
            val jpeg = save("$name.jpg", "image/jpeg") { check(result.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            val depth = save("${name}_depth.png", "image/png") { check(depthBitmap(scene.depth).compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val metadata = mapOf("schema" to 1, "effect" to "app_depth_bokeh", "sourceUri" to source.toString(),
                "model" to "MiDaS v2.1 model_opt", "modelSha256" to MODEL_SHA256, "depthKind" to "relative_inverse_depth",
                "depthNear" to "white", "metricDepth" to false, "focusX" to x, "focusY" to y,
                "focusDepth" to scene.depth.focus(x, y), "strength" to strength,
                "renderer" to "guided_luma_mask_v1",
                "width" to result.width, "height" to result.height, "inferenceMs" to scene.elapsedMs,
                "outputs" to listOf(jpeg.name, depth.name))
            val json = save("${name}_metadata.json", "application/json") { it.write(org.json.JSONObject(metadata).toString(2).toByteArray()) }
            listOf(jpeg, depth, json)
        }
    }

    companion object {
        const val MAX_EDGE = 1600
        const val MODEL_SHA256 = "93d871071edff1218973ce25ee27ce95ccd20450c70a55e1b89efa3f5a772cdd"
        fun depthBitmap(depth: RelativeDepth): Bitmap = Bitmap.createBitmap(IntArray(depth.values.size) {
            val value = (depth.values[it] * 255).toInt().coerceIn(0, 255)
            -0x1000000 or (value shl 16) or (value shl 8) or value
        }, depth.width, depth.height, Bitmap.Config.ARGB_8888)
    }
}
