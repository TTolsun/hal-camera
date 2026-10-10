package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class StreamConfigurationTest {
    private fun output(id: String, kind: OutputKind, repeating: Boolean = true, still: Boolean = false, observable: Boolean = true) =
        ConfiguredOutput(OutputDescriptor(id, kind, repeating, still, observable), Any())

    @Test fun sessionTargetsRequestTargetsAndGraphRowsComeFromExactlyTheSameOutputs() {
        val preview = output("p", OutputKind.PREVIEW)
        val yuv1 = output("yuv-small", OutputKind.YUV, still = true)
        val yuv2 = output("yuv-large", OutputKind.YUV, repeating = false, still = true)
        val jpeg = output("jpeg-main", OutputKind.JPEG, repeating = false, still = true)
        val config = StreamConfiguration(listOf(preview, yuv1, yuv2, jpeg))
        assertEquals(listOf(preview.target, yuv1.target, yuv2.target, jpeg.target), config.targets)
        assertEquals(listOf(preview, yuv1), config.repeating)
        assertEquals(listOf(yuv1, yuv2, jpeg), config.still)
        assertEquals(listOf("p", "yuv-small", "yuv-large", "jpeg-main"), config.metadata().map { it["id"] })
        assertEquals(listOf("Preview", "YUV", "YUV", "JPEG"), config.metadata().map { it["label"] })
        assertEquals(listOf(true, true, false, false), config.metadata().map { it["repeating"] })
    }

    @Test fun aSingleYuvIsNumberedAndDisplayRenumberingDoesNotChangeIdentity() {
        val a = output("yuv-small", OutputKind.YUV)
        val b = output("yuv-large", OutputKind.YUV)
        val metadata = StreamConfiguration(listOf(b, a)).metadata()
        assertEquals("yuv-large", metadata[0]["id"])
        assertEquals("YUV", metadata[0]["label"])
        assertEquals("YUV", StreamConfiguration(listOf(a)).metadata().single()["label"])
    }

    @Test fun recordingRemainsAnActualSessionTargetEvenWithoutAnObservableBufferCallback() {
        val recording = output("encoder", OutputKind.RECORDING, observable = false)
        val config = StreamConfiguration(listOf(recording))
        assertEquals(recording.target, config.targets.single())
        assertEquals(false, config.metadata().single()["observable"])
    }

    @Test(expected = IllegalArgumentException::class) fun duplicateIdsAreRejectedInsteadOfMergingRows() {
        StreamConfiguration(listOf(output("same", OutputKind.YUV), output("same", OutputKind.YUV)))
    }

    @Test(expected = IllegalArgumentException::class) fun duplicateTargetsCannotBeCountedAsTwoStreams() {
        val a = output("a", OutputKind.YUV)
        StreamConfiguration(listOf(a, a.copy(descriptor = a.descriptor.copy(id = "b"))))
    }
}
