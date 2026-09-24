package dev.halcamera.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The label is the one place every screen names a camera, so the spelling is pinned here rather than left to
 * whichever screen is being edited.
 */
class CameraLabelTest {

    private fun endpoint(id: String, physical: String?, role: LensRole, facing: Int, focalMm: Double? = null) = CameraEndpoint(
        logicalCameraId = id, physicalCameraId = physical, role = role, facing = facing,
        independentlyOpenable = physical == null, selectableByZoom = false, exposedToCameraX = null,
        equivalentFocalMm = focalMm, timestampSource = null, hardwareLevel = null, zoomRatioMin = null, zoomRatioMax = null
    )

    @Test
    fun `rear lenses read as Wide, UWide and Tele`() {
        assertEquals("Camera · 0 (Wide · Rear)", CameraLabel.full(endpoint("0", null, LensRole.MAIN, CameraLabel.FACING_BACK)))
        assertEquals("Camera · 2 (UWide · Rear)", CameraLabel.full(endpoint("2", null, LensRole.ULTRA_WIDE, CameraLabel.FACING_BACK)))
        assertEquals("Camera · 3 (Tele · Rear)", CameraLabel.full(endpoint("3", null, LensRole.TELE, CameraLabel.FACING_BACK)))
    }

    @Test
    fun `a physical camera is named by its dotted key`() {
        assertEquals("Camera · 0.2 (UWide · Rear)", CameraLabel.full(endpoint("0", "2", LensRole.ULTRA_WIDE, CameraLabel.FACING_BACK)))
    }

    @Test
    fun `facing alone is enough when the lens was never placed`() {
        // The enumeration never infers a front lens, and an unreadable rear focal length leaves UNKNOWN. With no
        // focal length reported there is nothing to add either, so these stay at the facing alone.
        assertEquals("Camera · 1 (Front)", CameraLabel.full(endpoint("1", null, LensRole.FRONT, CameraLabel.FACING_FRONT)))
        assertEquals("Camera · 4 (External)", CameraLabel.full(endpoint("4", null, LensRole.EXTERNAL, CameraLabel.FACING_EXTERNAL)))
        assertEquals("Camera · 5 (Rear)", CameraLabel.full(endpoint("5", null, LensRole.UNKNOWN, CameraLabel.FACING_BACK)))
    }

    @Test
    fun `two front cameras are told apart by their reported field of view`() {
        // Without the focal length these read "Camera · 1 (Front)" and "Camera · 3 (Front)", and the picker gave
        // no reason to pick either one. The lens is still not named: the 20/35 mm bands were chosen for rear
        // modules, and the number separates the two even when they land in the same band.
        assertEquals("Camera · 1 (Front · 24 mm)", CameraLabel.full(endpoint("1", null, LensRole.FRONT, CameraLabel.FACING_FRONT, 24.2)))
        assertEquals("Camera · 3 (Front · 14 mm)", CameraLabel.full(endpoint("3", null, LensRole.FRONT, CameraLabel.FACING_FRONT, 13.8)))
    }

    @Test
    fun `a rear camera that lost the Wide name to another one still says how wide it is`() {
        // dedupeMain leaves the second MAIN candidate UNKNOWN, which is exactly the camera the picker cannot place.
        assertEquals("Camera · 5 (Rear · 26 mm)", CameraLabel.full(endpoint("5", null, LensRole.UNKNOWN, CameraLabel.FACING_BACK, 26.0)))
    }

    @Test
    fun `a named lens does not repeat itself as a number`() {
        // The rear labels are unchanged: "Wide" already places the lens, so the millimetres would only be clutter.
        assertEquals("Camera · 0 (Wide · Rear)", CameraLabel.full(endpoint("0", null, LensRole.MAIN, CameraLabel.FACING_BACK, 24.0)))
    }

    @Test
    fun `an EXTERNAL role with no reported facing does not claim External`() {
        // Both enumerations fall back to EXTERNAL when LENS_FACING is unreadable, so the role alone proves nothing
        // and PROBE must not print a facing the HAL never reported.
        assertEquals("Camera · 6", CameraLabel.full("6", LensRole.EXTERNAL, null))
        assertEquals("Camera · 7", CameraLabel.full("7", LensRole.EXTERNAL, -1))
    }

    @Test
    fun `the label falls back to the short form when nothing is known`() {
        assertEquals("Camera · 9", CameraLabel.full("9", LensRole.UNKNOWN, null))
        assertEquals("Camera · 0", CameraLabel.short("0"))
        assertEquals("Camera · —", CameraLabel.short(""))
    }
}
