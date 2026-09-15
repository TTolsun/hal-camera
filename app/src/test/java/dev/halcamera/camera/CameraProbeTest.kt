package dev.halcamera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraProbeTest {

    private val rear = CameraProbeEntry(
        cameraId = "0", physicalOf = null, title = "0 · 후면 · FULL",
        sections = listOf(
            ProbeSection("IDENTITY", listOf(ProbeRow("Camera ID", "0"), ProbeRow("Hardware level", "FULL"))),
            ProbeSection("STREAMS · JPEG", listOf(ProbeFormat.streamRow(4000, 3000, 33_333_333L, 0L)))
        )
    )
    private val tele = CameraProbeEntry(
        cameraId = "3", physicalOf = "0", title = "3 · physical · 후면 · LIMITED",
        sections = listOf(ProbeSection("IDENTITY", listOf(ProbeRow("Physical camera of", "0"))))
    )
    private val snapshot = CameraProbeSnapshot(
        capturedAt = "2026-09-16T10:00:00+09:00",
        device = listOf(ProbeRow("Model", "SM-S936N"), ProbeRow("Android", "16 (API 36)")),
        cameras = listOf(rear, tele),
        errors = listOf("physical 0/4: unreadable")
    )

    @Test
    fun `physical camera key is prefixed with its logical camera`() {
        assertEquals("0", rear.key)
        assertEquals("0.3", tele.key)
        assertSame(tele, snapshot.camera("0.3"))
        assertNull(snapshot.camera("9"))
    }

    @Test
    fun `frame duration reads as milliseconds and fps, zero as unconstrained`() {
        assertEquals("33.3 ms (30.0 fps)", ProbeFormat.frameDuration(33_333_333L))
        assertEquals("0 ms", ProbeFormat.frameDuration(0L))
        assertEquals("—", ProbeFormat.frameDuration(null))
        assertEquals("66.7 ms", ProbeFormat.stall(66_666_667L))
        assertEquals(ProbeRow("1920x1080", "min 16.7 ms (60.0 fps) · stall 0 ms"), ProbeFormat.streamRow(1920, 1080, 16_666_667L, 0L))
    }

    @Test
    fun `exposure picks the unit by magnitude`() {
        assertEquals("125.0 µs", ProbeFormat.exposureNs(125_000L))
        assertEquals("33.33 ms", ProbeFormat.exposureNs(33_333_333L))
        assertEquals("30.00 s", ProbeFormat.exposureNs(30_000_000_000L))
    }

    @Test
    fun `section aligns values in one column and indents continuation lines`() {
        val text = CameraProbeText.section(ProbeSection("CONTROL", listOf(
            ProbeRow("AF modes", "OFF, AUTO"),
            ProbeRow("Session keys", "a\nb")
        )))
        assertEquals(
            "== CONTROL ==\n" +
            "AF modes      OFF, AUTO\n" +
            "Session keys  a\n" +
            "              b\n", text)
    }

    @Test
    fun `a key wider than the column takes its own line`() {
        val text = CameraProbeText.section(ProbeSection("ALL", listOf(
            ProbeRow("android.sensor.info.preCorrectionActiveArraySize", "Rect(0, 0 - 4000, 3000)"),
            ProbeRow("android.lens.facing", "1")
        )))
        val lines = text.lines()
        assertEquals("android.sensor.info.preCorrectionActiveArraySize", lines[1])
        assertTrue(lines[2].startsWith(" ".repeat(28) + "Rect(0, 0"))
        assertEquals("android.lens.facing".padEnd(28) + "1", lines[3])
    }

    @Test
    fun `render lists every camera by default and one when a key is given`() {
        val all = CameraProbeText.render(snapshot)
        assertTrue(all.contains("######## CAMERA 0 · 0 · 후면 · FULL"))
        assertTrue(all.contains("######## CAMERA 0.3 · 3 · physical · 후면 · LIMITED"))
        assertTrue(all.contains("4000x3000  min 33.3 ms (30.0 fps) · stall 0 ms"))
        assertTrue(all.contains("== ERRORS ==\n!  physical 0/4: unreadable"))
        val one = CameraProbeText.render(snapshot, cameraKey = "0.3")
        assertTrue(one.contains("CAMERA 0.3"))
        assertTrue(!one.contains("CAMERA 0 ·"))
        assertTrue(one.contains("== DEVICE ==\nModel    SM-S936N"))
    }

    @Test
    fun `json map keeps section order and carries schema and errors`() {
        val map = snapshot.toJsonMap()
        assertEquals(CameraProbeSnapshot.SCHEMA, map["schema"])
        assertEquals(mapOf("Model" to "SM-S936N", "Android" to "16 (API 36)"), map["device"])
        val cameras = map["cameras"] as List<*>
        val first = cameras[0] as Map<*, *>
        assertEquals("0", first["camera_id"])
        assertNull(first["physical_of"])
        val sections = first["sections"] as List<*>
        assertEquals(listOf("IDENTITY", "STREAMS · JPEG"), sections.map { (it as Map<*, *>)["title"] })
        val rows = (sections[1] as Map<*, *>)["rows"] as List<*>
        assertEquals(mapOf("key" to "4000x3000", "value" to "min 33.3 ms (30.0 fps) · stall 0 ms"), rows[0])
        assertEquals("0", (cameras[1] as Map<*, *>)["physical_of"])
        assertEquals(listOf("physical 0/4: unreadable"), map["errors"])
    }
}
