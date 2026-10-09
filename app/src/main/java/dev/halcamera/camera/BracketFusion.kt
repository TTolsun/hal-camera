package dev.halcamera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Saves the fourth image of a bracket (#178): the three shots fused by [ExposureFusion] into "<name>_AEB_HDR.jpg",
 * with a metadata JSON naming the shots it came from. It runs on its own thread after the bracket has finished, so
 * the camera is free again while it works, and reports on the main thread.
 *
 * Memory stays near one full-size output: the weight maps come from small decodes, and the full-size shots are
 * read in strips of [STRIP_ROWS] rows. The output keeps the first shot's EXIF orientation, because the camera's
 * JPEG stores its pixels sideways and says so only in EXIF.
 */
class BracketFusion(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val library = MediaLibrary(context)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun close() { io.shutdown() }

    /** One source: the JPEG to read and the request id that says which EV it asked for. */
    data class Source(val uri: Uri, val requestId: String, val fileName: String)

    fun fuse(bracketId: String, sources: List<Source>, done: (Result<PhotoArtifact>) -> Unit) {
        io.execute {
            val result = runCatching { fuseNow(bracketId, sources) }
            main.post { done(result) }
        }
    }

    private fun fuseNow(bracketId: String, sources: List<Source>): PhotoArtifact {
        require(sources.size >= 2) { "Need at least two shots" }
        val started = System.nanoTime()
        val bytes = sources.map { s -> resolver.openInputStream(s.uri)?.use { it.readBytes() } ?: error("Cannot read ${s.fileName}") }
        val bounds = bytes.map { b -> BitmapFactory.Options().apply { inJustDecodeBounds = true }
            .also { BitmapFactory.decodeByteArray(b, 0, b.size, it) } }
        val width = bounds[0].outWidth; val height = bounds[0].outHeight
        check(width > 0 && height > 0) { "Cannot decode ${sources[0].fileName}" }
        check(bounds.all { it.outWidth == width && it.outHeight == height }) { "Shots differ in size" }

        val scale = ExposureFusion.MAP_SIZE.toFloat() / max(width, height)
        val mapWidth = max(1, (width * scale).roundToInt()); val mapHeight = max(1, (height * scale).roundToInt())
        val qualities = bytes.map { b -> small(b, width, height, mapWidth, mapHeight).let { ExposureFusion.quality(it, mapWidth, mapHeight) } }
        val weights = ExposureFusion.weights(qualities, mapWidth, mapHeight, ExposureFusion.radiusFor(mapWidth, mapHeight))

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val decoders = bytes.map(::regionDecoder)
            try {
                val strips = List(sources.size) { IntArray(width * STRIP_ROWS) }
                val out = IntArray(width * STRIP_ROWS)
                var top = 0
                while (top < height) {
                    val rows = minOf(STRIP_ROWS, height - top)
                    decoders.forEachIndexed { k, decoder ->
                        val strip = decoder.decodeRegion(Rect(0, top, width, top + rows), BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: error("Cannot decode ${sources[k].fileName}")
                        try { strip.getPixels(strips[k], 0, width, 0, 0, width, rows) } finally { strip.recycle() }
                    }
                    ExposureFusion.blendStrip(strips, width, rows, top, width, height, weights, mapWidth, mapHeight, out)
                    output.setPixels(out, 0, width, 0, top, width, rows)
                    top += rows
                }
            } finally { decoders.forEach { it.recycle() } }
            val stream = ByteArrayOutputStream()
            check(output.compress(Bitmap.CompressFormat.JPEG, 95, stream)) { "Cannot encode the fused image" }
            val orientation = JpegOrientation.read(bytes[0])
            val jpeg = JpegOrientation.write(stream.toByteArray(), orientation)
            return library.saveFusion(library.name(), jpeg, mapOf("schema" to 1, "kind" to "exposure_fusion",
                "bracketId" to bracketId, "method" to "Mertens weights, single-scale, ${mapWidth}x$mapHeight blurred weight maps",
                "width" to width, "height" to height, "exifOrientation" to orientation,
                "elapsedMs" to (System.nanoTime() - started) / 1_000_000,
                "sources" to sources.map { mapOf("file" to it.fileName, "requestId" to it.requestId) }))
        } finally { output.recycle() }
    }

    /** The whole shot at [mapWidth] × [mapHeight], decoded at a power-of-two reduction first. */
    private fun small(jpeg: ByteArray, width: Int, height: Int, mapWidth: Int, mapHeight: Int): IntArray {
        var sample = 1
        while (width / (sample * 2) >= mapWidth && height / (sample * 2) >= mapHeight) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: error("Cannot decode a shot")
        val scaled = Bitmap.createScaledBitmap(decoded, mapWidth, mapHeight, true)
        try {
            return IntArray(mapWidth * mapHeight).also { scaled.getPixels(it, 0, mapWidth, 0, 0, mapWidth, mapHeight) }
        } finally { if (scaled !== decoded) scaled.recycle(); decoded.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun regionDecoder(jpeg: ByteArray): BitmapRegionDecoder =
        (if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size)
        else BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false)) ?: error("Cannot open a shot for decoding")

    /** The fused image and its metadata JSON; neither is published unless both were written. */
    private fun MediaLibrary.saveFusion(name: String, jpeg: ByteArray, metadata: Map<String, Any?>): PhotoArtifact {
        val file = "$name${BracketFiles.FUSED}.jpg"
        val image = create(file, false)
        val entries = mutableListOf(image)
        try {
            write(image) { it.write(jpeg) }
            val json = createData("$name${BracketFiles.FUSED}_metadata.json", "application/json")
            entries += json
            write(json) { it.write(org.json.JSONObject(metadata + mapOf("file" to file, "byteLength" to jpeg.size))
                .toString(2).toByteArray(Charsets.UTF_8)) }
            entries.forEach { publish(it) }
            return PhotoArtifact(file, "image/jpeg", image, jpeg.size.toLong())
        } catch (e: Exception) {
            entries.forEach { runCatching { resolver.delete(it, null, null) } }
            throw e
        }
    }

    companion object {
        const val STRIP_ROWS = 128

        /** The file to fuse from one saved shot: the camera's JPEG when it was saved, else the YUV JPEG. */
        fun pick(result: PhotoResult): Source? {
            val jpegs = result.artifacts.filter { it.mime == "image/jpeg" }
            val chosen = jpegs.firstOrNull { it.name.endsWith("_JPEG.jpg") } ?: jpegs.firstOrNull { it.name.endsWith("_YUV.jpg") }
            return chosen?.let { Source(it.uri, result.requestId ?: "", it.name) }
        }
    }
}
