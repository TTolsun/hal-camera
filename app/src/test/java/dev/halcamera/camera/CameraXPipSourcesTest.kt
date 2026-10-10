package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class CameraXPipSourcesTest {
    @Test fun `only advertised pairs containing the main camera become service choices`() {
        val sources = CameraXPipSources.forParent("0",listOf(listOf("0","1"),listOf("2","3"),listOf("1","0"),listOf("0","4")))
        assertEquals(listOf("1","4"),sources.map { it.id })
        assertTrue(sources.none { it.physical })
    }
    @Test fun `missing parent duplicate IDs and larger groups cannot fabricate a pair`() {
        assertTrue(CameraXPipSources.forParent("0",listOf(listOf("0"),listOf("0","0"),listOf("0","1","2"),listOf("2","3"))).isEmpty())
        assertTrue(CameraXPipSources.forParent("0",emptyList()).isEmpty())
    }
}
