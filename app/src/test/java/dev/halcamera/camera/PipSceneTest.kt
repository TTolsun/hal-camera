package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class PipSceneTest {
    @Test fun `producer rotation and mirror preserve aspect without a second camera rotation`() {
        assertEquals(16f/9f,PipScene.sourceAspect(1280,720,1f,0f,0f,-1f),.0001f)
        assertEquals(9f/16f,PipScene.sourceAspect(1280,720,0f,1f,-1f,0f),.0001f)
        assertEquals(9f/16f,PipScene.sourceAspect(1280,720,0f,-1f,-1f,0f),.0001f)
    }

    @Test fun `every physical rectangle starts inside its logical device`() {
        for (count in 1..12) {
            val frames = PipScene.initial(count)
            assertEquals(count,frames.size)
            frames.forEach { assertTrue(it.x >= 0f && it.y >= 0f && it.x+it.width <= 1f && it.y+it.height <= 1f) }
            frames.zipWithNext().forEach { (a,b) -> assertTrue(a.y+a.height <= b.y+.00001f) }
        }
    }

    @Test fun `drag cannot cross any parent boundary`() {
        val rect = PipRect(.5f,.5f,.3f,.2f)
        assertEquals(PipRect(0f,0f,.3f,.2f),rect.moved(-20f,-20f))
        assertEquals(PipRect(.7f,.8f,.3f,.2f),rect.moved(20f,20f))
        assertEquals(PipRect(.2f,.4f,.3f,.2f),rect.moved(.2f,.4f))
    }

    @Test fun `physical IDs from a different logical device are rejected`() {
        assertEquals(listOf("wide","tele"),PipScene.selected(setOf("wide","tele"),listOf("wide","tele")))
        assertEquals(emptyList<String>(),PipScene.selected(emptySet(),emptyList()))
        assertThrows(IllegalArgumentException::class.java) { PipScene.selected(setOf("wide"),listOf("front")) }
        assertThrows(IllegalArgumentException::class.java) { PipScene.selected(setOf("wide"),listOf("wide","wide")) }
    }

    @Test fun `hit testing ignores other logical frames and off rectangle touches`() {
        val rect = PipRect(.6f,.1f,.3f,.3f)
        assertTrue(rect.contains(.7f,.2f))
        assertFalse(rect.contains(.1f,.2f))
        assertFalse(rect.contains(1.2f,.2f))
        assertFalse(rect.contains(.7f,-.1f))
    }
}
