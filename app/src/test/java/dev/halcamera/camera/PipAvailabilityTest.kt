package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class PipAvailabilityTest {
    @Test fun `unadvertised rear service is disabled while advertised front is selectable`() {
        val combinations = setOf(setOf("logical-rear","front"))
        assertNull(PipAvailability.serviceReason(setOf("logical-rear","front"),combinations))
        assertNotNull(PipAvailability.serviceReason(setOf("logical-rear","rear"),combinations))
    }

    @Test fun `arbitrary device IDs and advertised supersets use the same rule`() {
        val combinations = setOf(setOf("usb-camera","wide","tele"))
        assertNull(PipAvailability.serviceReason(setOf("tele","usb-camera"),combinations))
        assertNotNull(PipAvailability.serviceReason(setOf("tele","front"),combinations))
    }

    @Test fun `missing advertisements fail closed without disabling physical routing`() {
        assertNotNull(PipAvailability.serviceReason(setOf("0","2"),emptySet()))
        assertTrue(PipSource("2",true,"Physical 2").selectable)
        assertFalse(PipSource("2",false,"Service 2","Unsupported pair").selectable)
    }

    @Test fun `physical and service stream rejection both prevent selection`() {
        listOf(true,false).forEach { physical ->
            val source = PipSource("arbitrary-id",physical,"Camera")
            val rejected = source.withStreamSupport { PipStreamSupport.UNSUPPORTED }
            assertFalse(rejected.selectable)
            assertEquals(source.key,rejected.key)
            assertTrue(source.withStreamSupport { PipStreamSupport.SUPPORTED }.selectable)
        }
    }

    @Test fun `unavailable queries do not falsely advertise stream support`() {
        val source = PipSource("physical-wide",true,"Wide")
        assertFalse(source.withStreamSupport { PipStreamSupport.UNKNOWN }.selectable)
        assertFalse(source.withStreamSupport { throw UnsupportedOperationException() }.selectable)
        assertFalse(source.withStreamSupport { throw IllegalStateException("Camera closed") }.selectable)
    }

    @Test fun `metadata rejection never invokes a session query`() {
        val source = PipSource("rear",false,"Rear","Concurrent use unsupported")
        var queried = false
        assertEquals(source,source.withStreamSupport { queried = true; PipStreamSupport.SUPPORTED })
        assertFalse(queried)
    }

    @Test fun `stream checks are fresh when output settings change`() {
        val source = PipSource("tele",true,"Tele")
        var supported = true
        val query = { if (supported) PipStreamSupport.SUPPORTED else PipStreamSupport.UNSUPPORTED }
        assertTrue(source.withStreamSupport(query).selectable)
        supported = false
        assertFalse(source.withStreamSupport(query).selectable)
    }

    @Test fun `compositor source size refuses streams outside its actual pixel budget`() {
        assertNull(pipPreviewSize(listOf(LiveSize(1920,1080))))
        assertNull(pipPreviewSize(emptyList()))
        assertEquals(LiveSize(1280,720),pipPreviewSize(listOf(LiveSize(640,480),LiveSize(1920,1080),LiveSize(1280,720))))
    }
}
