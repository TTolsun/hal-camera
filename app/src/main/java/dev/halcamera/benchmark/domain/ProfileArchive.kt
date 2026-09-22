package dev.halcamera.benchmark.domain

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Content-addressed private archive. Neither run_id nor a provider filename is ever used as a path. */
class ProfileArchive(private val directory: File, private val decode: (String) -> BenchmarkRun) {
    data class Imported(val file: File, val sha256: String, val duplicate: Boolean)
    fun import(input: InputStream): Imported = synchronized(writeLock) { importLocked(input) }

    private fun importLocked(input: InputStream): Imported {
        val bytes = boundedRead(input)
        val text = decodeUtf8(bytes)
        validateDepth(text)
        val run = decode(text)
        validate(run)
        val hash = hash(bytes)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create the import store." }
        val target = File(directory, "$hash.json")
        if (target.exists()) {
            check(target.readBytes().contentEquals(bytes)) { "The stored original is corrupted." }
            return Imported(target, hash, true)
        }
        require(files().size < MAX_FILES) { "At most $MAX_FILES imported files are kept." }
        AtomicFiles.write(target, text)
        return Imported(target, hash, false)
    }
    fun files(): List<File> = directory.listFiles()?.filter { it.name.matches(Regex("[0-9a-f]{64}\\.json")) }?.sortedBy { it.name } ?: emptyList()

    companion object {
        private val writeLock = Any()
        const val MAX_BYTES = 16 * 1024 * 1024
        const val MAX_FILES = 200
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun boundedRead(input: InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(output.size().toLong() + n <= MAX_BYTES) { "A file must be 16 MiB or smaller." }
                output.write(buffer, 0, n)
            }
            return output.toByteArray()
        }
        fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        fun validateDepth(text: String) {
            var depth = 0; var quoted = false; var escaped = false
            for (c in text) {
                if (quoted) {
                    if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
                } else when (c) {
                    '"' -> quoted = true
                    '{', '[' -> { depth++; require(depth <= 64) { "JSON nesting is too deep." } }
                    '}', ']' -> { depth--; require(depth >= 0) { "Malformed JSON structure." } }
                }
            }
            require(depth == 0 && !quoted) { "Incomplete JSON." }
        }
        fun validate(run: BenchmarkRun) {
            require(run.runId.isNotBlank() && run.runId.length <= 200) { "run_id is missing or too long." }
            require(run.device.manufacturer.isNotBlank() && run.device.model.isNotBlank() && run.device.fingerprint.isNotBlank()) { "Device identification fields are missing." }
            require(run.app.versionName.isNotBlank() && run.endpoint.logicalCameraId.isNotBlank()) { "App version or camera ID is missing." }
            require(run.metrics.isNotEmpty() && run.metrics.size <= 100 && run.metrics.map { it.id }.distinct().size == run.metrics.size) { "Metrics are missing, duplicated, or excessive." }
            require(run.metrics.all { it.id.isNotBlank() && it.unit.isNotBlank() && it.sampleCount >= 0 &&
                (it.value == null || it.value.isFinite() && it.value in 0.0..1e12) }) { "Invalid metric value." }
        }
    }
}
