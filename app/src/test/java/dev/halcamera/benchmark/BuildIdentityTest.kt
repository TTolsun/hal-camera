package dev.halcamera.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildIdentityTest {
    private val device = DeviceInfo("samsung", "SM-S936N", "BP4A.251205.006", "inc1", "samsung/fp1", "vendor/fp1", 36, "2025-12-01", "hal-1")
    private val app = AppInfo("0.3.0", 3)
    private val subject = SubjectLabel("SW41", "9c01d2e", "camera/release", null)
    private val a = BuildIdentity(device, app, subject)

    @Test fun identicalRunsAgreeOnEveryAxis() {
        val c = BuildIdentity.compare(a, a)
        assertTrue(c.sameSystemFingerprint); assertEquals(true, c.sameVendorFingerprint); assertEquals(true, c.sameCameraInfoVersion)
        assertTrue(c.sameAppVersion); assertEquals(true, c.sameSubjectLabel); assertEquals(true, c.sameSubjectCommit)
        assertEquals(true, c.sameCameraBuild)
    }

    @Test fun sameAndroidDifferentCameraBuildIsVisible() {
        val b = a.copy(device = device.copy(vendorFingerprint = "vendor/fp2"), subject = SubjectLabel("SW42", "a8f29c1"))
        val c = BuildIdentity.compare(a, b)
        assertTrue(c.sameSystemFingerprint)
        assertEquals(false, c.sameVendorFingerprint)
        assertEquals(false, c.sameCameraBuild)
        assertEquals(false, c.sameSubjectLabel); assertEquals(false, c.sameSubjectCommit)
    }

    @Test fun missingInformationYieldsNullNotFalse() {
        val b = a.copy(device = device.copy(vendorFingerprint = null, cameraInfoVersion = null), subject = SubjectLabel())
        val c = BuildIdentity.compare(a, b)
        assertNull(c.sameVendorFingerprint); assertNull(c.sameCameraInfoVersion); assertNull(c.sameCameraBuild)
        assertNull(c.sameSubjectLabel); assertNull(c.sameSubjectCommit)
        // Vendor unknown but INFO_VERSION known: the camera build line falls back to INFO_VERSION.
        val d = a.copy(device = device.copy(vendorFingerprint = null))
        assertEquals(true, BuildIdentity.compare(a, d).sameCameraBuild)
    }

    @Test fun sameVendorFingerprintDoesNotHideADifferentInfoVersion() {
        val b = a.copy(device = device.copy(cameraInfoVersion = "hal-2"))
        val c = BuildIdentity.compare(a, b)
        assertEquals(true, c.sameVendorFingerprint)
        assertEquals(false, c.sameCameraInfoVersion)
        assertEquals(false, c.sameCameraBuild)
        // Only INFO_VERSION known and equal: true. Neither known: null.
        val onlyInfo = a.copy(device = device.copy(vendorFingerprint = null))
        assertEquals(true, BuildIdentity.compare(onlyInfo, onlyInfo).sameCameraBuild)
        val nothing = a.copy(device = device.copy(vendorFingerprint = null, cameraInfoVersion = null))
        assertNull(BuildIdentity.compare(nothing, nothing).sameCameraBuild)
    }

    @Test fun appVersionDiffersByCodeToo() {
        val c = BuildIdentity.compare(a, a.copy(app = AppInfo("0.3.0", 4)))
        assertFalse(c.sameAppVersion)
    }

    @Test fun jsonRoundTrip() {
        val c = BuildIdentity.compare(a, a.copy(device = device.copy(vendorFingerprint = null)))
        assertEquals(c, BuildIdentityComparison.fromJsonMap(c.toJsonMap()))
    }
}
