package dev.halcamera.camera

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test

class LiveBurstLifecycleTest {
    private val support = LiveControlSupport(-6..6, 1.0 / 3, true, true, true, true, true)

    private class Camera : MediaCapture, LiveTuning {
        var controls = LiveControls(bracket = true)
        val exposures = mutableListOf<Int>()
        var answer: (() -> Unit)? = null
        override val mediaBusy get() = answer != null
        override fun setControls(next: LiveControls, restore: Boolean) { controls = next }
        override fun capturePhoto(requestId: String, done: (Result<PhotoResult>) -> Unit) {
            exposures += controls.evIndex
            answer = { answer = null; done(Result.success(PhotoResult(requestId, "photo", 0L, emptyList()))) }
        }
        override fun startRecording(audio: Boolean, started: () -> Unit, done: ((Result<Uri>) -> Unit)?) = Unit
        override fun stopRecording() = Unit
    }

    private class Rig {
        val camera = Camera()
        var active: MediaCapture? = camera
        var now = 0L
        var changes = 0
        val pending = ArrayDeque<Pair<Long, () -> Unit>>()
        val notices = mutableListOf<String>()
        val burst = LiveBurst({ now }, { delay, block -> pending.addLast(delay to block) }, { active },
            { true }, { _, _, _ -> }, { changes++ }, notices::add)
        fun settle() { val (delay, block) = pending.removeFirst(); now += delay; block() }
    }

    @Test fun `a bracket holds scene controls until all three shots save and restores the base EV`() {
        val r = Rig()
        val base = r.camera.controls.copy(evIndex = 1)
        r.burst.bracket("s", base, support)
        assertTrue(r.burst.controlsLocked)
        assertEquals("AEB 0/3", r.burst.label)
        repeat(3) { index ->
            r.settle()
            assertTrue(r.burst.controlsLocked)
            r.camera.answer!!.invoke()
            if (index < 2) assertEquals("AEB ${index + 1}/3", r.burst.label)
        }
        assertEquals(listOf(1, -5, 6), r.camera.exposures)
        assertEquals(base, r.camera.controls)
        assertFalse(r.burst.controlsLocked)
        assertFalse(r.burst.bracketing)
        assertNull(r.burst.label)
    }

    @Test fun `touch release does not cancel a tap-started bracket but explicit stop does`() {
        val r = Rig()
        r.burst.bracket("s", r.camera.controls, support)
        r.burst.release()
        r.settle()
        assertEquals(listOf(0), r.camera.exposures)
        r.burst.stop()
        assertEquals("Stopping…", r.burst.label)
        assertTrue(r.burst.controlsLocked)
        r.camera.answer!!.invoke()
        assertFalse(r.burst.controlsLocked)
        assertTrue(r.notices.single().contains("1/3 saved"))
        assertTrue(r.pending.isEmpty())
    }

    @Test fun `stop while EV settles takes no shot and restores controls before unlocking`() {
        val r = Rig()
        val base = r.camera.controls.copy(evIndex = 2)
        r.burst.bracket("s", base, support)
        r.settle(); r.camera.answer!!.invoke()
        assertEquals(-4, r.camera.controls.evIndex)
        r.burst.stop()
        assertTrue(r.burst.controlsLocked)
        r.settle()
        assertEquals(listOf(2), r.camera.exposures)
        assertEquals(base, r.camera.controls)
        assertFalse(r.burst.controlsLocked)
    }

    @Test fun `a held burst stops on release after saving the in-flight shot`() {
        val r = Rig()
        r.burst.hold("s")
        assertEquals("Burst · 0", r.burst.label)
        r.burst.release()
        r.camera.answer!!.invoke()
        assertEquals(listOf(0), r.camera.exposures)
        assertEquals(listOf("Burst · 1 saved"), r.notices)
        assertFalse(r.burst.controlsLocked)
    }

    @Test fun `destroying during settle neither shoots nor notifies the dead screen`() {
        val r = Rig()
        r.burst.bracket("s", r.camera.controls, support)
        r.burst.dispose()
        val changes = r.changes
        r.settle()
        r.burst.hold("s")
        r.burst.bracket("s", r.camera.controls, support)
        assertTrue(r.camera.exposures.isEmpty())
        assertTrue(r.notices.isEmpty())
        assertEquals(changes, r.changes)
        assertNull(r.burst.run)
    }

    @Test fun `manual exposure cannot start a bracket through a stale action`() {
        val r = Rig()
        r.burst.bracket("s", r.camera.controls.copy(manual = ManualControls(exposure = ManualExposure(100, 10_000_000L))), support)
        assertNull(r.burst.run)
        assertTrue(r.pending.isEmpty())
    }

    @Test fun `destroying during a save keeps the save but suppresses progress and completion UI`() {
        val r = Rig()
        r.burst.hold("s")
        r.burst.dispose()
        val changes = r.changes
        r.camera.answer!!.invoke()
        assertEquals(listOf(0), r.camera.exposures)
        assertEquals(changes, r.changes)
        assertTrue(r.notices.isEmpty())
        assertNull(r.burst.run)
    }
}
