package dev.halcamera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The #168 tap mapping and tap bookkeeping that do not need a camera. */
class TouchMeterTest {

    private fun sensor(u: Double, v: Double, orientation: Int, front: Boolean = false) =
        TouchMeter.toSensor(u, v, orientation, front).let { (x, y) -> Math.round(x * 100) / 100.0 to Math.round(y * 100) / 100.0 }

    @Test
    fun `the centre stays the centre for every orientation and facing`() {
        for (o in listOf(0, 90, 180, 270)) for (front in listOf(false, true)) assertEquals(0.5 to 0.5, sensor(0.5, 0.5, o, front))
    }

    @Test
    fun `a back sensor at 90 degrees has its top on the right of the upright picture`() {
        // Upright top-left is sensor bottom-left; upright top-right is sensor top-left.
        assertEquals(0.0 to 1.0, sensor(0.0, 0.0, 90))
        assertEquals(0.0 to 0.0, sensor(1.0, 0.0, 90))
        assertEquals(0.25 to 0.9, sensor(0.1, 0.25, 90))
    }

    @Test
    fun `mapping back through the forward rotation returns the tapped point`() {
        // Forward: the preview is the readout rotated clockwise, then mirrored for a front camera.
        fun forward(x: Double, y: Double, o: Int, front: Boolean): Pair<Double, Double> {
            var u = x; var v = y
            repeat(o / 90) { val nu = 1 - v; v = u; u = nu }
            return (if (front) 1 - u else u) to v
        }
        for (o in listOf(0, 90, 180, 270)) for (front in listOf(false, true)) {
            val (x, y) = TouchMeter.toSensor(0.2, 0.7, o, front)
            val (u, v) = forward(x, y, o, front)
            assertEquals("o=$o front=$front", 0.2, u, 1e-9); assertEquals(0.7, v, 1e-9)
        }
    }

    @Test
    fun `a front camera mirrors before rotating`() {
        assertEquals(sensor(0.8, 0.3, 270), sensor(0.2, 0.3, 270, front = true))
    }

    @Test
    fun `a 16 by 9 preview meters inside the centre band of a 4 by 3 array`() {
        val base = MeterRect(0, 0, 4000, 3000)
        val centre = TouchMeter.region(0.5, 0.5, base, 16.0 / 9)
        assertEquals(2000, (centre.left + centre.right) / 2); assertEquals(1500, (centre.top + centre.bottom) / 2)
        assertEquals(375, centre.width) // shorter visible side 2250 / 6
        val top = TouchMeter.region(0.5, 0.0, base, 16.0 / 9)
        assertEquals(375, top.top) // clamped to the visible band, which starts 375 px down
    }

    @Test
    fun `a corner tap is clamped inside the base rectangle`() {
        val base = MeterRect(500, 375, 3500, 2625) // a 1.33x crop region below API 30
        val corner = TouchMeter.region(1.0, 1.0, base, 4.0 / 3)
        assertEquals(base.right, corner.right); assertEquals(base.bottom, corner.bottom)
        assertEquals(375, corner.width)
        val origin = TouchMeter.region(0.0, 0.0, base, 4.0 / 3)
        assertEquals(base.left, origin.left); assertEquals(base.top, origin.top)
    }

    @Test
    fun `only the latest tap's trigger and results decide the outcome`() {
        val watch = TouchFocusWatch()
        val first = watch.tap()
        val second = watch.tap()
        assertNull(watch.triggerCompleted(first, TouchFocusWatch.AF_STATE_FOCUSED_LOCKED))
        assertFalse(watch.timedOut(first))
        assertNull(watch.onResult(TouchFocusWatch.AF_STATE_FOCUSED_LOCKED)) // before the trigger of the latest tap
        assertNull(watch.triggerCompleted(second, 3)) // ACTIVE_SCAN
        assertNull(watch.onResult(3))
        assertEquals(TouchPhase.FAILED, watch.onResult(TouchFocusWatch.AF_STATE_NOT_FOCUSED_LOCKED))
        assertNull(watch.onResult(TouchFocusWatch.AF_STATE_FOCUSED_LOCKED)) // decided once
        assertFalse(watch.timedOut(second))
    }

    @Test
    fun `a scan with no answer times out, and a device without AF state counts as focused`() {
        val watch = TouchFocusWatch()
        val gen = watch.tap()
        assertTrue(watch.timedOut(gen))
        assertNull(watch.triggerCompleted(gen, TouchFocusWatch.AF_STATE_FOCUSED_LOCKED))
        val next = watch.tap()
        assertEquals(TouchPhase.FOCUSED, watch.triggerCompleted(next, null))
    }

    @Test
    fun `cancel drops a tap in flight`() {
        val watch = TouchFocusWatch()
        val gen = watch.tap()
        watch.cancel()
        assertNull(watch.triggerCompleted(gen, TouchFocusWatch.AF_STATE_FOCUSED_LOCKED))
        assertFalse(watch.timedOut(gen))
    }

    @Test
    fun `a long press is metered after two settled AE results, once`() {
        val watch = TouchExposureWatch()
        watch.press()
        assertFalse(watch.onResult(1)) // SEARCHING
        assertFalse(watch.onResult(AeRelock.AE_STATE_CONVERGED)) // one settled result may be the old region's
        assertTrue(watch.onResult(AeRelock.AE_STATE_FLASH_REQUIRED))
        assertFalse(watch.onResult(AeRelock.AE_STATE_CONVERGED))
    }

    @Test
    fun `a newer press or a cancel silences the older one`() {
        val watch = TouchExposureWatch()
        val first = watch.press()
        val second = watch.press()
        assertFalse(watch.timedOut(first))
        assertTrue(watch.timedOut(second))
        assertFalse(watch.onResult(AeRelock.AE_STATE_CONVERGED))
        watch.press(); watch.cancel()
        assertFalse(watch.onResult(null))
    }

    @Test
    fun `a device without AE state is metered at once`() {
        val watch = TouchExposureWatch()
        watch.press()
        assertTrue(watch.onResult(null))
    }

    @Test
    fun `the copied AF state values are the Camera2 ones`() {
        assertEquals(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED, TouchFocusWatch.AF_STATE_FOCUSED_LOCKED)
        assertEquals(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED, TouchFocusWatch.AF_STATE_NOT_FOCUSED_LOCKED)
    }
}
