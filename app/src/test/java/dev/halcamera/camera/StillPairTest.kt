package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class StillPairTest {
    @Test fun `preview and stale JPEG cannot become the requested still`() {
        val pair = StillPair<String, String>()
        pair.timestamp = 200
        pair.yuv(100, "preview"); pair.jpeg(100, "stale")
        pair.jpeg(200, "camera JPEG")
        assertNull(pair.complete())
        pair.yuv(200, "still YUV")
        assertEquals("still YUV" to "camera JPEG", pair.complete())
    }

    @Test fun `images arriving before the result are matched only after its timestamp is known`() {
        val pair = StillPair<String, String>()
        pair.yuv(100, "preview"); pair.yuv(200, "still YUV"); pair.jpeg(200, "camera JPEG")
        assertNull(pair.complete())
        pair.timestamp = 200
        assertEquals("still YUV" to "camera JPEG", pair.complete())
    }

    @Test fun `new request does not inherit an incomplete previous capture`() {
        val old = StillPair<String, String>().apply { timestamp = 100; yuv(100, "old") }
        val next = StillPair<String, String>().apply { timestamp = 200; jpeg(100, "late JPEG"); yuv(200, "new") }
        assertNull(old.complete()); assertNull(next.complete())
        next.jpeg(200, "new JPEG")
        assertEquals("new" to "new JPEG", next.complete())
    }
}
