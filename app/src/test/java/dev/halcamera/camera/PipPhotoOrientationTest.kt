package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class PipPhotoOrientationTest {
    @Test fun secondaryBackAndFrontFollowDisplayOnNaturalLandscapeDevice() {
        assertEquals(0,pipPhotoOrientation(90,false,90))
        assertEquals(0,pipPhotoOrientation(270,true,90))
        assertEquals(180,pipPhotoOrientation(90,false,270))
        assertEquals(180,pipPhotoOrientation(270,true,270))
    }
    @Test fun portraitPhoneRetainsSensorRotation() {
        assertEquals(90,pipPhotoOrientation(90,false,0))
        assertEquals(270,pipPhotoOrientation(270,true,0))
    }
}
