package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class PipOutputsTest {
    @Test fun rawStaysAStillTargetWhilePipPreviewAndYuvRepeat() {
        val plan = PipOutputs("6")
        val raw = ConfiguredOutput(OutputDescriptor("raw", OutputKind.RAW, false, stillCapture = true), "raw")
        val yuv = ConfiguredOutput(OutputDescriptor("analysis", OutputKind.YUV, true, stillCapture = true), "yuv")
        val config = plan.configure(listOf("main", "physical"), listOf(yuv, raw))
        assertEquals(listOf("main", "physical", "yuv"), config.repeating.map { it.target })
        assertEquals(listOf("raw"), config.still.map { it.target })
        assertEquals("RAW", config.metadata().last()["label"])
    }
    @Test fun physicalTargetsAndGraphShareDescriptorsAndRetainYuv() {
        val plan = PipOutputs("6")
        val yuv = ConfiguredOutput(OutputDescriptor("analysis", OutputKind.YUV, true), "reader")
        val config = plan.configure(listOf("main", "physical"), listOf(yuv))
        assertEquals(listOf("main", "physical", "reader"), config.repeating.map { it.target })
        assertEquals(listOf(null, "6", null), config.outputs.map { it.descriptor.physicalId })
        assertEquals(listOf("Preview", "Preview (Phy)", "YUV"), config.metadata().map { it["label"] })
        assertEquals("pip_preview_available", config.metadata()[1]["eventKind"])
        assertEquals("Jpeg", plan.photo.metadata()["label"])
        assertFalse(config.targets.contains(plan.photo.id))
    }
    @Test fun servicePipExcludesSecondaryDeviceFromMainConfiguration() {
        val plan = PipOutputs(null)
        assertEquals(listOf("preview"), plan.configure(listOf("main"), emptyList()).outputs.map { it.descriptor.id })
        assertNull(plan.inputs.getOrNull(1))
    }
    @Test fun physicalYuvLabelComesFromDescriptor() {
        assertEquals("YUV (Phy)", OutputDescriptor("yuv", OutputKind.YUV, true, physicalId = "6").metadata()["label"])
    }
}
