package dev.halcamera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The LIVE control rules of #169 and #176 that do not need a camera: what a camera offers and when to precapture. */
class LiveControlsTest {

    private val rear = LiveControlSupport(evRange = -6..6, evStep = 1.0 / 3, aeLock = true, afLock = true,
        flash = true, autoFlash = true, alwaysFlash = true)
    private val front = LiveControlSupport(evRange = -4..4, evStep = 0.5, aeLock = true, afLock = false,
        flash = false, autoFlash = false, alwaysFlash = false)

    @Test
    fun `video mode offers only off and torch because auto and on fire in a still`() {
        assertEquals(listOf(FlashMode.OFF, FlashMode.AUTO, FlashMode.ON, FlashMode.TORCH), rear.flashModes(video = false))
        assertEquals(listOf(FlashMode.OFF, FlashMode.TORCH), rear.flashModes(video = true))
        assertEquals(FlashMode.OFF, LiveControls(flash = FlashMode.AUTO).coerce(rear, video = true).flash)
        assertEquals(FlashMode.TORCH, LiveControls(flash = FlashMode.TORCH).coerce(rear, video = true).flash)
    }

    @Test
    fun `a camera without a flash unit keeps flash off and never gets a flash AE mode`() {
        assertEquals(listOf(FlashMode.OFF), front.flashModes(video = false))
        assertEquals(FlashMode.OFF, LiveControls(flash = FlashMode.TORCH).coerce(front, video = false).flash)
    }

    @Test
    fun `flash modes the AE mode list lacks are not offered`() {
        val torchOnly = rear.copy(autoFlash = false, alwaysFlash = false)
        assertEquals(listOf(FlashMode.OFF, FlashMode.TORCH), torchOnly.flashModes(video = false))
    }

    @Test
    fun `coerce drops unsupported locks and clamps EV to the range`() {
        val asked = LiveControls(evIndex = 9, aeLock = true, afLock = true)
        assertEquals(LiveControls(evIndex = 4, aeLock = true, afLock = false), asked.coerce(front, video = false))
        assertEquals(0, LiveControls(evIndex = 3).coerce(LiveControlSupport.NONE, video = false).evIndex)
        assertEquals(LiveControls(), asked.coerce(LiveControlSupport.NONE, video = false))
    }

    @Test
    fun `precapture runs for flash auto and on, and not while AE is locked`() {
        assertTrue(LiveControls(flash = FlashMode.AUTO).needsPrecapture)
        assertTrue(LiveControls(flash = FlashMode.ON).needsPrecapture)
        assertFalse(LiveControls(flash = FlashMode.ON, aeLock = true).needsPrecapture)
        assertFalse(LiveControls(flash = FlashMode.TORCH).needsPrecapture)
        assertFalse(LiveControls().needsPrecapture)
    }

    @Test
    fun `EV labels read in EV with one decimal`() {
        assertEquals("EV 0", rear.evLabel(0))
        assertEquals("EV +0.7", rear.evLabel(2))
        assertEquals("EV −2.0", rear.evLabel(-6))
        assertEquals("EV +1.5", front.evLabel(3))
    }

    @Test
    fun `precapture waits for AE to pass through PRECAPTURE and leave it`() {
        val watch = PrecaptureWatch()
        assertFalse(watch.onResult(PrecaptureWatch.AE_STATE_PRECAPTURE))
        assertFalse(watch.onResult(PrecaptureWatch.AE_STATE_PRECAPTURE))
        assertTrue(watch.onResult(1)) // SEARCHING after PRECAPTURE still means the sequence ended
    }

    @Test
    fun `the copied AE state values are the Camera2 ones`() {
        assertEquals(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED, PrecaptureWatch.AE_STATE_CONVERGED)
        assertEquals(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_LOCKED, PrecaptureWatch.AE_STATE_LOCKED)
        assertEquals(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED, PrecaptureWatch.AE_STATE_FLASH_REQUIRED)
        assertEquals(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_PRECAPTURE, PrecaptureWatch.AE_STATE_PRECAPTURE)
    }

    @Test
    fun `precapture accepts a settled state when the device skips PRECAPTURE, and a null state`() {
        assertTrue(PrecaptureWatch().onResult(PrecaptureWatch.AE_STATE_FLASH_REQUIRED))
        assertTrue(PrecaptureWatch().onResult(PrecaptureWatch.AE_STATE_CONVERGED))
        assertTrue(PrecaptureWatch().onResult(null))
        assertFalse(PrecaptureWatch().onResult(1)) // SEARCHING before any PRECAPTURE: keep waiting
    }
}
