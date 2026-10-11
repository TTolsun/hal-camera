package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class PipOutputsTest {
    @Test fun serviceAndPhysicalPreviewCompositionNeverAdvertiseAnAppRecordingStream() {
        for (physicalId in listOf(null, "arbitrary-sensor")) {
            val plan = PipOutputs(physicalId)
            val targets = if (physicalId == null) listOf("main") else listOf("main", "secondary")
            val config = plan.configure(targets, emptyList())
            val rows = config.metadata()
            assertFalse(rows.any { it["kind"] == "RECORDING" })
            assertEquals(targets, config.targets)
            assertTrue(config.outputs.all { it.descriptor.kind == OutputKind.PREVIEW })
            assertFalse(config.metadata().any { it["kind"] == "RECORDING" })
        }
    }
    @Test fun supportQueryRetainsExactlyTheHalStreamsUsedByPip() {
        val original = OutputKind.entries.map { kind ->
            ConfiguredOutput(OutputDescriptor(kind.name,kind,false),kind.name)
        }
        val retained = PipOutputs.retained(original)
        assertEquals(listOf(OutputKind.YUV,OutputKind.JPEG,OutputKind.RAW),retained.map { it.descriptor.kind })
        val physical = PipOutputs("arbitrary-physical-id").configure(listOf("main-preview","pip-preview"),retained)
        assertEquals(listOf("main-preview","pip-preview","YUV","JPEG","RAW"),physical.targets)
        assertEquals("arbitrary-physical-id",physical.outputs[1].descriptor.physicalId)
        val serviceMain = PipOutputs(null).configure(listOf("main-preview"),retained)
        assertEquals(listOf("main-preview","YUV","JPEG","RAW"),serviceMain.targets)
        assertTrue(serviceMain.outputs.none { it.descriptor.kind == OutputKind.RECORDING })
    }
    @Test fun selectedYuvSourceAndRawStayStillTargetsWhilePreviewRepeats() {
        val plan = PipOutputs("6")
        val raw = ConfiguredOutput(OutputDescriptor("raw", OutputKind.RAW, false, stillCapture = true), "raw")
        val yuv = ConfiguredOutput(OutputDescriptor("analysis", OutputKind.YUV, true, stillCapture = true), "yuv")
        val config = plan.configure(listOf("main", "physical"), listOf(yuv, raw))
        assertEquals(listOf("main", "physical", "yuv"), config.repeating.map { it.target })
        assertEquals(listOf("yuv","raw"), config.still.map { it.target })
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
        assertEquals("PIP JPEG", plan.photo.metadata()["label"])
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
    @Test fun composedPhotoIsNeverACameraRequestOrCallbackStream() {
        for (physical in listOf(null, "arbitrary-physical")) {
            val plan = PipOutputs(physical)
            val config = plan.configure(plan.inputs.map { it.id }, emptyList())
            assertFalse(config.targets.contains(plan.photo.id))
            assertFalse(config.metadata().any { it["id"] == plan.photo.id })
        }
    }
}
