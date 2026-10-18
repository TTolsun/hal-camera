package dev.halcamera.cli

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CliArtifactRegistryTest {
    private fun source(name: String, bytes: ByteArray, location: String = "file:///$name") =
        CliArtifactRegistry.Source(name, "application/octet-stream", location) { bytes.inputStream() }

    @Test fun `artifacts get ordered IDs with their exact size and SHA-256`() {
        val files = CliArtifactRegistry.register(listOf(source("a.txt", "abc".toByteArray()), source("empty.bin", ByteArray(0))))
        assertEquals(listOf("file-0", "file-1"), files.map { it["artifact_id"] })
        assertEquals(3L, files[0]["size_bytes"])
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", files[0]["sha256"])
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", files[1]["sha256"])
        assertEquals("file:///a.txt", files[0]["source_uri"])
        assertEquals(listOf("artifact_id", "name", "mime_type", "size_bytes", "sha256", "source_uri"), files[0].keys.toList())
    }

    @Test fun `large files are read in full and every stream is closed`() {
        val big = ByteArray(200_000) { it.toByte() }
        var closed = false
        val stream = object : java.io.ByteArrayInputStream(big) { override fun close() { closed = true; super.close() } }
        val files = CliArtifactRegistry.register(listOf(CliArtifactRegistry.Source("big", "video/mp4", "content://x") { stream }))
        assertEquals(200_000L, files.single()["size_bytes"])
        assertTrue(closed)
    }

    @Test fun `a source that cannot be opened fails the registration`() {
        val missing = CliArtifactRegistry.Source("gone.jpg", "image/jpeg", "content://gone") { File("does/not/exist").inputStream() }
        assertThrows(java.io.FileNotFoundException::class.java) { CliArtifactRegistry.register(listOf(missing)) }
    }
}
