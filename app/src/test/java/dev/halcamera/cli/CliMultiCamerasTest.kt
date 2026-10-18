package dev.halcamera.cli

import dev.halcamera.camera.CameraLabel
import dev.halcamera.camera.ConcurrentCamera
import dev.halcamera.camera.ConcurrentPlanner
import dev.halcamera.camera.LiveSize
import org.junit.Assert.*
import org.junit.Test

class CliMultiCamerasTest {
    private val id = "b616d5cc-7706-4983-b49e-4e4d5c0ef816"
    private val rear = ConcurrentCamera("0", CameraLabel.FACING_BACK, listOf(LiveSize(1920, 1080), LiveSize(1280, 720)),
        listOf(LiveSize(4000, 3000), LiveSize(1920, 1440)), listOf("2", "3"))
    private val front = ConcurrentCamera("1", CameraLabel.FACING_FRONT, listOf(LiveSize(1280, 720)), listOf(LiveSize(1920, 1440)))
    private val secondRear = ConcurrentCamera("2", CameraLabel.FACING_BACK, listOf(LiveSize(1280, 720)), listOf(LiveSize(1920, 1440)))

    @Test fun `multi cameras is a screenless query without camera parameters`() {
        val spec = CliCommands.get("multi.cameras")
        assertEquals(CliCommandSpec.Target.QUERY, spec.target)
        assertFalse(spec.toMap()["foreground"] as Boolean)
        assertEquals("multi.cameras", CliCommand(id, "multi.cameras", null, null, 30_000).command)
        assertEquals("multi.cameras", AdbArguments.command("multi.cameras", emptyMap()).command)
        try { AdbArguments.command("multi.cameras", mapOf("camera" to "0")); fail("camera accepted") }
        catch (error: CliFailure) { assertEquals("INVALID_ARGUMENT", error.code) }
    }

    @Test fun `advertised sets come first and non advertised pairs stay marked`() {
        val plans = ConcurrentPlanner.plans(30, setOf(setOf("0", "1")), listOf(rear, front, secondRear))
        val map = CliMultiCameras.toMap(plans, true)
        assertEquals(true, map["supported"])
        assertNull(map["reason"])
        @Suppress("UNCHECKED_CAST") val combinations = map["combinations"] as List<Map<String, Any?>>
        assertEquals(listOf("0", "1"), combinations.first()["cameras"])
        assertEquals(true, combinations.first()["advertised"])
        assertTrue(combinations.drop(1).all { it["advertised"] == false })
        @Suppress("UNCHECKED_CAST") val streams = combinations.first()["streams"] as List<Map<String, Any?>>
        assertEquals(mapOf("camera_id" to "0", "preview" to "1280x720", "photo" to "1920x1440"), streams.first())
    }

    @Test fun `cameras list the sizes Multi offers and physical ids for each device once`() {
        val map = CliMultiCameras.toMap(ConcurrentPlanner.plans(30, emptySet(), listOf(rear, front)), true)
        @Suppress("UNCHECKED_CAST") val cameras = map["cameras"] as List<Map<String, Any?>>
        assertEquals(listOf("0", "1"), cameras.map { it["camera_id"] })
        assertEquals("Rear 0", cameras.first()["label"])
        assertEquals(listOf("2", "3"), cameras.first()["physical_ids"])
        assertEquals(listOf("1280x720"), cameras.first()["preview_sizes"])
        assertEquals(listOf("1920x1440"), cameras.first()["photo_sizes"])
        assertEquals(CliMultiCameras.CAMERAX_MAX_DEVICES, map["camerax_max_devices"])
    }

    @Test fun `old Android reports why Multi is unavailable instead of an empty success`() {
        val map = CliMultiCameras.toMap(emptyList(), false)
        assertEquals(false, map["supported"])
        assertEquals("Multi requires Android 11 or later", map["reason"])
        assertEquals(emptyList<Any>(), map["combinations"])
    }
}
