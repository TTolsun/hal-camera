package dev.halcamera.camera

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reads the platform constants from android.jar: the values are compile-time constants, so no device is needed. */
class MetadataNamesTest {

    @Test
    fun `hardware level keeps the word LEVEL when the remainder is a number`() {
        assertEquals("LEVEL_3", MetadataNames.name("INFO_SUPPORTED_HARDWARE_LEVEL_", CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3))
        assertEquals("FULL", MetadataNames.name("INFO_SUPPORTED_HARDWARE_LEVEL_", CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL))
        assertEquals("—", MetadataNames.name("INFO_SUPPORTED_HARDWARE_LEVEL_", null))
        assertEquals("99", MetadataNames.name("INFO_SUPPORTED_HARDWARE_LEVEL_", 99))
    }

    @Test
    fun `scene modes drop the DEVICE_CUSTOM range markers`() {
        val names = MetadataNames.of("CONTROL_SCENE_MODE_").values
        assertTrue("PORTRAIT" in names)
        assertFalse(names.any { it.startsWith("DEVICE_CUSTOM") })
    }
}
