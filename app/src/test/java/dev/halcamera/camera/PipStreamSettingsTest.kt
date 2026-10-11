package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class PipStreamSettingsTest {
    private val source = PipSource("device-selected-id",false,"Camera")
    private val size = LiveSize(640,480)
    private val caps = PipStreamCapabilities(source,listOf(size),listOf(size),listOf(size))
    private fun settings(value: PipStreamSettings) = LiveStreamSettings(size,size,size,null,pip = mapOf(source.key to value))

    @Test fun autoAndAdvertisedSizesAreAllowed() {
        assertNull(caps.rejection(settings(PipStreamSettings())))
        assertNull(caps.rejection(settings(PipStreamSettings(size,size))))
    }
    @Test fun rejectedPreviewAndPhotoAreNotReplaced() {
        val unsupported = LiveSize(1000,1000)
        assertEquals("Unsupported PIP preview size.",caps.rejection(settings(PipStreamSettings(unsupported,null))))
        assertEquals("Unsupported PIP JPEG size.",caps.rejection(settings(PipStreamSettings(null,unsupported))))
        assertEquals("Unsupported PIP YUV size.",caps.rejection(settings(PipStreamSettings(null,unsupported)).copy(jpeg = null,jpegFromYuv = true)))
    }
    @Test fun jpegOffDoesNotConfigureTheStoredOriginalSize() {
        val value = settings(PipStreamSettings(null,LiveSize(1000,1000))).copy(jpeg = null)
        assertNull(caps.rejection(value))
    }
    @Test fun physicalAndServiceNamespacesDoNotCollideAndSurviveStateSerialization() {
        val value = settings(PipStreamSettings(size,size)).copy(pip = mapOf(
            "physical:2" to PipStreamSettings(size,size),"service:2" to PipStreamSettings(LiveSize(1280,720),null)))
        val buffer = java.io.ByteArrayOutputStream()
        java.io.ObjectOutputStream(buffer).use { it.writeObject(value) }
        val restored = java.io.ObjectInputStream(java.io.ByteArrayInputStream(buffer.toByteArray())).use { it.readObject() }
        assertEquals(value,restored)
        assertEquals(value.pip,value.forMode(true).pip)
    }
}
