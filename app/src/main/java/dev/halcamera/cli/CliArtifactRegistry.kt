package dev.halcamera.cli

import java.io.InputStream
import java.security.MessageDigest

/**
 * Files a request's outputs under stable `file-N` IDs with their size and SHA-256, read once at completion.
 * [Source.location] stays in the private record; [CliJson.publicRecord] removes it before anything is shown.
 */
object CliArtifactRegistry {
    class Source(val name: String, val mimeType: String, val location: String, val open: () -> InputStream)

    fun register(sources: List<Source>): List<Map<String, Any?>> = sources.mapIndexed { index, source ->
        val hash = MessageDigest.getInstance("SHA-256")
        var size = 0L
        source.open().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                hash.update(buffer, 0, n); size += n
            }
        }
        mapOf("artifact_id" to "file-$index", "name" to source.name, "mime_type" to source.mimeType, "size_bytes" to size,
            "sha256" to hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, "source_uri" to source.location)
    }
}
