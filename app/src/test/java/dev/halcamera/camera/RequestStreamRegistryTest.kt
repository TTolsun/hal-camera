package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class RequestStreamRegistryTest {
    @Test fun delayedPreviewCallbackRetainsItsTargetsAfterRecordingBegins() {
        val registry = RequestStreamRegistry<Any>()
        val selected = mutableListOf("preview")
        val preview = registry.track(Any(), selected)
        selected += "recording"
        val recording = registry.track(Any(), selected)
        selected.clear()
        assertEquals(listOf("preview"), registry[preview])
        assertEquals(listOf("preview", "recording"), registry[recording])
    }

    @Test fun unknownRequestIsDistinctFromARequestWithNoTargets() {
        val registry = RequestStreamRegistry<Any>()
        assertNull(registry[Any()])
        val request = registry.track(Any(), emptyList())
        assertEquals(emptyList<String>(), registry[request])
    }
}
