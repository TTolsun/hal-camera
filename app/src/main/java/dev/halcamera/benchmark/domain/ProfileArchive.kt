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
        check(directory.isDirectory || directory.mkdirs()) { "가져오기 저장소를 만들 수 없습니다." }
        val target = File(directory, "$hash.json")
        if (target.exists()) {
            check(target.readBytes().contentEquals(bytes)) { "저장된 원본이 손상됐습니다." }
            return Imported(target, hash, true)
        }
        require(files().size < MAX_FILES) { "가져온 자료는 최대 ${MAX_FILES}개까지 보관합니다." }
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
                require(output.size().toLong() + n <= MAX_BYTES) { "파일은 16 MiB 이하여야 합니다." }
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
                    '{', '[' -> { depth++; require(depth <= 64) { "JSON 중첩이 너무 깊습니다." } }
                    '}', ']' -> { depth--; require(depth >= 0) { "JSON 구조가 잘못됐습니다." } }
                }
            }
            require(depth == 0 && !quoted) { "완성되지 않은 JSON입니다." }
        }
        fun validate(run: BenchmarkRun) {
            require(run.runId.isNotBlank() && run.runId.length <= 200) { "run_id가 없거나 너무 깁니다." }
            require(run.device.manufacturer.isNotBlank() && run.device.model.isNotBlank() && run.device.fingerprint.isNotBlank()) { "기기 식별 필드가 누락됐습니다." }
            require(run.app.versionName.isNotBlank() && run.endpoint.logicalCameraId.isNotBlank()) { "앱 버전 또는 카메라 ID가 누락됐습니다." }
            require(run.metrics.isNotEmpty() && run.metrics.size <= 100 && run.metrics.map { it.id }.distinct().size == run.metrics.size) { "지표가 없거나 중복·과다입니다." }
            require(run.metrics.all { it.id.isNotBlank() && it.unit.isNotBlank() && it.sampleCount >= 0 &&
                (it.value == null || it.value.isFinite() && it.value in 0.0..1e12) }) { "잘못된 지표 값입니다." }
        }
    }
}
