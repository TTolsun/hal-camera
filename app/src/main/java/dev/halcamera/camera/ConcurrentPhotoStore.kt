package dev.halcamera.camera

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

internal data class ConcurrentPhoto(val bytes: ByteArray, val imageTimestampNs: Long, val sensorTimestampNs: Long)

/** File boundary: keep successful photos even when the other camera fails, with an explicit group manifest. */
internal class ConcurrentPhotoStore(context: Context) {
    private val library = MediaLibrary(context)

    fun save(capture: ConcurrentCapture<ConcurrentPhoto>, plan: ConcurrentPlan, timestampSources: Map<String, Int?>): String {
        val published = mutableListOf<Uri>()
        try {
            val records = JSONArray()
            plan.streams.forEachIndexed { index, stream ->
                val id = stream.camera.id
                val result = checkNotNull(capture.outcomes[id])
                val entry = JSONObject().put("cameraId", id).put("facing", stream.camera.facing)
                    .put("requestedPreview", stream.preview.toString()).put("configuredPreview", stream.preview.toString())
                    .put("requestedJpeg", stream.photo.toString()).put("configuredJpeg", stream.photo.toString())
                    .put("timestampSource", timestampSources[id] ?: JSONObject.NULL)
                val saved = result.mapCatching { photo ->
                    val file = "${capture.groupId}_${index}_cam${id.replace(Regex("[^A-Za-z0-9_-]"), "_")}_JPEG.jpg"
                    val uri = library.create(file, false)
                    try {
                        library.write(uri) { it.write(photo.bytes) }
                        library.publish(uri)
                    } catch (e: Exception) {
                        runCatching { library.resolver.delete(uri, null, null) }
                        throw e
                    }
                    published += uri
                    entry.put("file", file).put("uri", uri.toString())
                        .put("imageTimestampNs", photo.imageTimestampNs).put("sensorTimestampNs", photo.sensorTimestampNs)
                }
                entry.put("status", if (saved.isSuccess) "saved" else "failed")
                saved.exceptionOrNull()?.let { entry.put("error", it.message ?: it.javaClass.simpleName) }
                records.put(entry)
            }
            val manifest = JSONObject().put("schemaVersion", 1).put("groupId", capture.groupId)
                .put("requestedAtElapsedRealtimeNs", capture.requestedAtNs).put("sensorSynchronized", false)
                .put("note", "One app command; independent camera requests and sensor timestamps.")
                .put("cameras", records)
            val metadata = library.createData("${capture.groupId}_concurrent.json", "application/json")
            published += metadata
            library.write(metadata) { it.write(manifest.toString(2).toByteArray(Charsets.UTF_8)) }
            library.publish(metadata)
            return (0 until records.length()).joinToString(" · ") {
                val entry = records.getJSONObject(it)
                "Camera ${entry.getString("cameraId")}: ${entry.getString("status")}" +
                    if (entry.has("error")) " (${entry.getString("error")})" else ""
            } + "\nGroup ${capture.groupId}\nPhotos: DCIM/HALCamera · Report: Download/HALCamera"
        } catch (e: Exception) {
            published.forEach { runCatching { library.resolver.delete(it, null, null) } }
            throw e
        }
    }
}
