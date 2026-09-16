package dev.halcamera.benchmark.platform

import android.content.Context
import dev.halcamera.benchmark.domain.AtomicFiles
import dev.halcamera.benchmark.domain.BenchmarkReportCodec
import dev.halcamera.benchmark.domain.BenchmarkRun
import dev.halcamera.telemetry.Event
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Writes and reads run JSON files under files/benchmarks/. The only place org.json touches the contract. */
class BenchmarkReport(private val store: BenchmarkStore) {
    constructor(context: Context) : this(BenchmarkStore(context))

    /** Writes atomically (temp file, fsync, rename) so a killed process never leaves a truncated run file. */
    fun write(run: BenchmarkRun, events: List<Event>): File {
        val file = store.file(run.runId)
        val withEvents = run.copy(events = events.map { BenchmarkReportCodec.eventToMap(it) })
        AtomicFiles.write(file, json(BenchmarkReportCodec.toJsonMap(withEvents)).toString(2))
        return file
    }

    /** null means unreadable; the reason is kept in [lastReadError] so the UI can say so instead of hiding it. */
    fun read(file: File): BenchmarkRun? = try {
        lastReadError = null
        BenchmarkReportCodec.fromJsonMap(toMap(JSONObject(file.readText())), file)
    } catch (e: Exception) {
        lastReadError = "${file.name}: ${e.message}"
        android.util.Log.w(TAG, "unreadable run file ${file.name}: ${e.message}")
        null
    }

    var lastReadError: String? = null
        private set

    companion object {
        private const val TAG = "BenchmarkReport"

        fun utcNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

        /** Millisecond suffix keeps ids unique across a fast abort and restart within the same second. */
        fun newRunId(): String = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

        fun json(map: Map<*, *>): JSONObject = JSONObject().also { o -> map.forEach { (k, v) -> o.put(k.toString(), value(v)) } }

        private fun value(v: Any?): Any = when (v) {
            null -> JSONObject.NULL
            is Map<*, *> -> json(v)
            is List<*> -> JSONArray(v.map(::value))
            is Enum<*> -> v.name.lowercase(Locale.US)
            is Double -> if (v.isNaN() || v.isInfinite()) JSONObject.NULL else v
            is Float -> if (v.isNaN() || v.isInfinite()) JSONObject.NULL else v.toDouble()
            else -> v
        }

        fun toMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { k -> fromJson(o.opt(k)) }

        private fun fromJson(v: Any?): Any? = when (v) {
            null, JSONObject.NULL -> null
            is JSONObject -> toMap(v)
            is JSONArray -> (0 until v.length()).map { fromJson(v.opt(it)) }
            else -> v
        }
    }
}
