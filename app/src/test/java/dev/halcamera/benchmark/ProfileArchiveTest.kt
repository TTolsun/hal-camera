package dev.halcamera.benchmark

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream

class ProfileArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val run = BenchmarkRunFixture.run(metrics = listOf(BenchmarkRunFixture.metric("H.1", 1.0)))

    @Test fun `content duplicates are idempotent while run ID collisions preserve both originals`() {
        val directory = temporary.newFolder()
        val archive = ProfileArchive(directory) { run }
        val a = archive.import("{\"a\":1}".byteInputStream())
        assertFalse(a.duplicate)
        assertTrue(archive.import("{\"a\":1}".byteInputStream()).duplicate)
        val b = archive.import("{\"a\":2}".byteInputStream())
        assertNotEquals(a.sha256, b.sha256)
        assertEquals(2, ProfileArchive(directory) { run }.files().size)
        assertEquals("{\"a\":1}", a.file.readText())
    }

    @Test fun `bad imports do not change existing bytes or unrelated baseline pointers`() {
        val directory = temporary.newFolder()
        val archive = ProfileArchive(directory) { if (it == "bad") error("invalid schema") else run }
        val a = archive.import("{}".byteInputStream())
        val baseline = temporary.newFile("baseline.json").apply { writeText("original baseline") }
        assertThrows(IllegalStateException::class.java) { archive.import("bad".byteInputStream()) }
        assertThrows(Exception::class.java) { archive.import(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
        assertThrows(IllegalArgumentException::class.java) { archive.import(("[".repeat(65) + "]".repeat(65)).byteInputStream()) }
        assertEquals(1, archive.files().size)
        assertEquals("{}", a.file.readText())
        assertEquals("original baseline", baseline.readText())
    }

    @Test fun `size is bounded before decoding or writing`() {
        var decoded = false
        val directory = temporary.newFolder()
        val archive = ProfileArchive(directory) { decoded = true; run }
        val endless = object : InputStream() {
            override fun read() = 32
            override fun read(b: ByteArray, off: Int, len: Int): Int { b.fill(32, off, off + len); return len }
        }
        assertThrows(IllegalArgumentException::class.java) { archive.import(endless) }
        assertFalse(decoded)
        assertTrue(archive.files().isEmpty())
    }

    @Test fun `required fields and malformed metrics reject before storage`() {
        for (invalid in listOf(run.copy(runId = ""), run.copy(device = run.device.copy(model = "")),
            run.copy(metrics = emptyList()), run.copy(metrics = run.metrics + run.metrics),
            run.copy(metrics = listOf(run.metrics.first().copy(value = Double.NaN))))) {
            val archive = ProfileArchive(temporary.newFolder()) { invalid }
            assertThrows(IllegalArgumentException::class.java) { archive.import("{}".byteInputStream()) }
            assertTrue(archive.files().isEmpty())
        }
    }

    @Test fun `JSON brackets inside escaped strings do not count toward depth`() {
        ProfileArchive.validateDepth("{\"s\":\"\\\"${"[".repeat(100)}\"}")
        assertThrows(IllegalArgumentException::class.java) { ProfileArchive.validateDepth("{\"a\":1") }
    }
}
