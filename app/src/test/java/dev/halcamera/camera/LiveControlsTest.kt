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
    fun `composited snapshots disable still flash and bracketing but retain preview controls`() {
        val requested = LiveControls(evIndex = 2, aeLock = true, afLock = true, flash = FlashMode.ON, bracket = true)
        assertEquals(requested.copy(flash = FlashMode.OFF, bracket = false), requested.coerce(rear, false, composited = true))
        assertEquals(FlashMode.TORCH, requested.copy(flash = FlashMode.TORCH).coerce(rear, false, composited = true).flash)
        assertEquals(requested, requested.coerce(rear, false))
    }

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
    fun `precapture accepts a settled state only after three in a row, and a null state at once`() {
        val skipper = PrecaptureWatch()
        assertFalse(skipper.onResult(PrecaptureWatch.AE_STATE_FLASH_REQUIRED))
        assertFalse(skipper.onResult(PrecaptureWatch.AE_STATE_CONVERGED))
        assertTrue(skipper.onResult(PrecaptureWatch.AE_STATE_CONVERGED))
        assertTrue(PrecaptureWatch().onResult(null))
        assertFalse(PrecaptureWatch().onResult(1)) // SEARCHING before any PRECAPTURE: keep waiting
    }

    private val searching = 1
    private val converged = AeRelock.AE_STATE_CONVERGED
    private val locked = AeRelock.AE_STATE_LOCKED
    private val preview = AeRelock.Exposure(timeNs = 41_620_000, iso = 4274) // S25+ dark scene, #184
    private val recording = AeRelock.Exposure(timeNs = 33_330_000, iso = 2990)

    @Test
    fun `a rebuilt session runs unlocked until AE settles, then relocks and compares`() {
        val relock = AeRelock()
        relock.lockChanged(true)
        assertEquals(AeRelock.Step.None, relock.onResult(locked, preview)) // held exposure from the old session
        relock.sessionRebuilt(locked = true)
        assertTrue(relock.waiting)
        assertEquals(AeRelock.Step.None, relock.onResult(searching, recording))
        assertEquals(AeRelock.Step.None, relock.onResult(converged, recording)) // one settled result is not trusted
        assertEquals(AeRelock.Step.Relock, relock.onResult(converged, recording))
        assertFalse(relock.waiting)
        assertEquals(AeRelock.Step.None, relock.onResult(converged, recording)) // results still in flight
        val step = relock.onResult(locked, recording) as AeRelock.Step.Relocked
        assertEquals(preview, step.before)
        assertEquals(-0.83, step.deltaEv!!, 0.01)
        assertEquals(AeRelock.Step.None, relock.onResult(locked, recording)) // compared once per rebuild
    }

    @Test
    fun `a session rebuilt without the lock never waits`() {
        val relock = AeRelock()
        relock.sessionRebuilt(locked = false)
        assertFalse(relock.waiting)
        assertEquals(AeRelock.Step.None, relock.onResult(converged, preview))
    }

    @Test
    fun `the timeout relocks an AE that never settles, and only its own session while waiting`() {
        val relock = AeRelock()
        assertFalse(relock.timedOut(relock.generation))
        val old = relock.sessionRebuilt(locked = true)
        val gen = relock.sessionRebuilt(locked = true)
        assertFalse(relock.timedOut(old))
        assertTrue(relock.waiting)
        assertTrue(relock.timedOut(gen))
        assertFalse(relock.waiting)
        assertFalse(relock.timedOut(gen))
        // No exposure held before: the relock is reported without a difference.
        assertEquals(AeRelock.Step.Relocked(null, recording, null), relock.onResult(locked, recording))
    }

    @Test
    fun `a device without AE state relocks at once`() {
        val relock = AeRelock()
        relock.sessionRebuilt(locked = true)
        assertEquals(AeRelock.Step.Relock, relock.onResult(null, null))
    }

    @Test
    fun `the user toggling the lock cancels a pending relock and forgets the held exposure when off`() {
        val relock = AeRelock()
        relock.lockChanged(true)
        relock.onResult(locked, preview)
        relock.sessionRebuilt(locked = true)
        relock.lockChanged(false)
        assertFalse(relock.waiting)
        relock.lockChanged(true)
        relock.sessionRebuilt(locked = true)
        relock.onResult(converged, recording); relock.onResult(converged, recording)
        assertEquals(null, (relock.onResult(locked, recording) as AeRelock.Step.Relocked).before)
    }

    @Test
    fun `EV steps while locked move the held exposure the next relock compares with`() {
        val relock = AeRelock()
        relock.lockChanged(true)
        relock.onResult(locked, preview)
        val brighter = preview.copy(iso = preview.iso * 2)
        relock.onResult(locked, brighter)
        relock.sessionRebuilt(locked = true)
        relock.onResult(converged, brighter); relock.onResult(converged, brighter)
        assertEquals(0.0, (relock.onResult(locked, brighter) as AeRelock.Step.Relocked).deltaEv!!, 1e-9)
    }

    @Test
    fun `a settled run broken by searching starts over`() {
        val relock = AeRelock()
        relock.sessionRebuilt(locked = true)
        relock.onResult(converged, recording)
        assertEquals(AeRelock.Step.None, relock.onResult(searching, recording))
        assertEquals(AeRelock.Step.None, relock.onResult(converged, recording))
        assertEquals(AeRelock.Step.Relock, relock.onResult(converged, recording))
    }

    @Test
    fun `a relock that could not be sent waits for AE to settle again`() {
        val relock = AeRelock()
        relock.sessionRebuilt(locked = true)
        relock.onResult(converged, recording)
        assertEquals(AeRelock.Step.Relock, relock.onResult(converged, recording))
        relock.relockNotSent()
        assertTrue(relock.waiting)
        assertEquals(AeRelock.Step.None, relock.onResult(converged, recording))
        assertEquals(AeRelock.Step.Relock, relock.onResult(converged, recording))
    }

    @Test
    fun `the relock notice names the difference only beyond a third of a stop`() {
        assertEquals(null, LiveControlText.relockNotice(null))
        assertEquals(null, LiveControlText.relockNotice(0.3))
        assertEquals("Exposure locked again · 0.8 EV darker", LiveControlText.relockNotice(-0.83))
        assertEquals("Exposure locked again · 0.5 EV brighter", LiveControlText.relockNotice(0.5))
    }

    @Test
    fun `the relock AE state values are the Camera2 ones`() {
        assertEquals(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED, AeRelock.AE_STATE_CONVERGED)
        assertEquals(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_LOCKED, AeRelock.AE_STATE_LOCKED)
        assertEquals(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED, AeRelock.AE_STATE_FLASH_REQUIRED)
    }

    @Test
    fun `a PRECAPTURE that arrives a frame late is still waited for`() {
        val late = PrecaptureWatch()
        assertFalse(late.onResult(PrecaptureWatch.AE_STATE_CONVERGED)) // trigger result still shows the old state
        assertFalse(late.onResult(PrecaptureWatch.AE_STATE_PRECAPTURE))
        assertFalse(late.onResult(PrecaptureWatch.AE_STATE_PRECAPTURE))
        assertTrue(late.onResult(PrecaptureWatch.AE_STATE_CONVERGED))
    }
}
