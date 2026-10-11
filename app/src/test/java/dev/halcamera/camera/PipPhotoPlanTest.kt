package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class PipPhotoPlanTest {
    @Test fun autoUsesSecondarySizesWithinMainBudget() {
        val plan = PipPhotoPlan(OutputKind.JPEG,LiveSize(1920,1080))
        assertEquals(listOf(LiveSize(1920,1080),LiveSize(1280,720)),
            plan.candidates(listOf(LiveSize(4000,3000),LiveSize(1280,720),LiveSize(1920,1080))))
    }
    @Test fun cameraWithOnlyLargerSourcesIsStillQueried() {
        val plan = PipPhotoPlan(OutputKind.JPEG,LiveSize(640,480))
        assertEquals(listOf(LiveSize(1920,1080)),plan.candidates(listOf(LiveSize(1920,1080))))
    }
    @Test fun smallerSupportedOriginalKeepsCameraSelectable() {
        val small = LiveSize(640,480); val large = LiveSize(1920,1080)
        val (support, selected) = choosePipInputs(listOf(small),listOf(small),listOf(large,small)) { _, _, photo ->
            if (photo == small) PipStreamSupport.SUPPORTED else PipStreamSupport.UNSUPPORTED
        }
        assertEquals(PipStreamSupport.SUPPORTED,support)
        assertEquals(small,selected?.photo)
    }
    @Test fun explicitOriginalDoesNotFallBackAfterRejection() {
        val small = LiveSize(640,480)
        val (support, selected) = choosePipInputs(listOf(small),listOf(small),listOf(LiveSize(4000,3000))) { _, _, _ ->
            PipStreamSupport.UNSUPPORTED
        }
        assertEquals(PipStreamSupport.UNSUPPORTED,support); assertNull(selected)
    }
    @Test fun unknownQueryRemainsUnverifiedInsteadOfUnsupported() {
        val size = LiveSize(640,480)
        assertEquals(PipStreamSupport.UNKNOWN,
            choosePipInputs(listOf(size),listOf(size),listOf(size)) { _, _, _ -> PipStreamSupport.UNKNOWN }.first)
    }
    @Test fun cameraXUsesItsCommonConcurrentFormatsWithoutDeviceRules() {
        val preview = LiveSize(1280,720); val yuv = LiveSize(640,480); val jpeg = LiveSize(1920,1080)
        assertTrue(cameraXConcurrentPipStreamsSupported(preview,null,jpeg))
        assertTrue(cameraXConcurrentPipStreamsSupported(preview,yuv,null))
        assertFalse(cameraXConcurrentPipStreamsSupported(preview,yuv,jpeg))
        assertFalse(cameraXConcurrentPipStreamsSupported(preview,null,LiveSize(4000,3000)))
        assertTrue(cameraXConcurrentPipStreamsSupported(LiveSize(1920,1080),null,null))
    }
}
