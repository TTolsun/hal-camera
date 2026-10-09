package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class DualStillPairTest {
    @Test fun acceptsLogicalTimestampOnlyFromTheSameCaptureResult() {
        val pair = DualStillPair<String>()
        pair.result(100, 103, 101)
        pair.image(0, 101, "main")
        pair.image(1, 102, "wrong")
        assertNull(pair.ready())
        pair.image(1, 101, "sub")
        assertEquals("main" to "sub", pair.ready())
    }
    @Test fun matchesDifferentPhysicalTimestampsFromOneResult() {
        val pair = DualStillPair<String>()
        pair.image(0, 100, "main")
        pair.image(1, 103, "sub")
        assertNull(pair.ready())
        pair.result(100, 103)
        assertEquals("main" to "sub", pair.ready())
    }
    @Test fun doesNotSaveOneSensorOrAnAdjacentFrame() {
        val pair = DualStillPair<String>()
        pair.result(100, 103)
        pair.image(0, 100, "main")
        pair.image(1, 102, "old sub")
        assertNull(pair.ready())
        pair.image(1, 103, "sub")
        assertEquals("main" to "sub", pair.ready())
    }
    @Test fun bufferHistoryIsBounded() {
        val pair = DualStillPair<Int>()
        repeat(10) { pair.image(0, it.toLong(), it) }
        pair.image(1, 1, 1)
        pair.result(1, 1)
        assertNull(pair.ready())
    }
}
