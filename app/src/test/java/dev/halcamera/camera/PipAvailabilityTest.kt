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

    @Test fun `unavailable queries preserve potentially usable combinations without claiming verified support`() {
        val source = PipSource("physical-wide",true,"Wide")
        val results = listOf(source.withStreamSupport { PipStreamSupport.UNKNOWN },
            source.withStreamSupport { throw UnsupportedOperationException() },
            source.withStreamSupport { throw IllegalStateException("Camera closed") })
        assertTrue(results.all { it.selectable && it.supportNote == "Not verified" })
        assertNull(source.withStreamSupport { PipStreamSupport.SUPPORTED }.supportNote)
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

    @Test fun `CameraX keeps a source available when a smaller concurrent preview pair works`() {
        val large = LiveSize(1280,720)
        val small = LiveSize(640,480)
        val result = choosePipPreviewSizes(listOf(large,small),listOf(large,small)) { main,pip ->
            if (main == small && pip == small) PipStreamSupport.SUPPORTED else PipStreamSupport.UNSUPPORTED
        }
        assertEquals(PipStreamSupport.SUPPORTED,result.first)
        assertEquals(listOf(small,small),result.second)
    }

    @Test fun `all concurrent pairs rejected blocks source while an unavailable query remains unknown`() {
        val size = LiveSize(1280,720)
        assertEquals(PipStreamSupport.UNSUPPORTED,choosePipPreviewSizes(listOf(size),listOf(size)) { _,_ ->
            PipStreamSupport.UNSUPPORTED
        }.first)
        assertEquals(PipStreamSupport.UNKNOWN,choosePipPreviewSizes(listOf(size),listOf(size)) { _,_ ->
            PipStreamSupport.UNKNOWN
        }.first)
    }

    @Test fun `late preflight rejection preserves a running PIP while actual configuration failure clears it`() {
        val previous = PipSource("front",false,"Front")
        val requested = PipSource("tele",true,"Tele")
        val rejection = Result.failure<Unit>(PipSelectionRejected("Unsupported streams"))
        assertEquals(previous,pipSelectionAfter(previous,requested,rejection))
        assertNull(pipSelectionAfter(null,requested,rejection))
        assertNull(pipSelectionAfter(previous,requested,Result.failure(IllegalStateException("Configure failed"))))
        assertEquals(requested,pipSelectionAfter(previous,requested,Result.success(Unit)))
    }

    @Test fun `CameraX concurrent mode rejects 4K analysis even if the HAL supports it alone`() {
        assertTrue(cameraXConcurrentAnalysisSupported(null))
        assertTrue(cameraXConcurrentAnalysisSupported(LiveSize(640,480)))
        assertTrue(cameraXConcurrentAnalysisSupported(LiveSize(1920,1440)))
        assertFalse(cameraXConcurrentAnalysisSupported(LiveSize(3840,2160)))
    }
}
