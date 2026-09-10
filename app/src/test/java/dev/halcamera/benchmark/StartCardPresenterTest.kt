package dev.halcamera.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The start card contract of 8.2: what stops a run, what only warns about it, and what the card says either way. */
class StartCardPresenterTest {

    private val profile = BenchmarkProfile.CAMERA2_STANDARD_V1
    private val supported = Compatibility("device_setup", true, emptyList(), true)
    private val unsupported = Compatibility("static_table", false, listOf("YUV_SIZE"), false)

    private fun card(
        compatibility: Compatibility = supported,
        engineName: String = "Camera2",
        thermalStatus: Int? = 0,
        powerSaveMode: Boolean? = false
    ) = StartCardPresenter.present(profile, compatibility, "후면 메인", engineName, thermalStatus, powerSaveMode)

    // ---- what stops a run ----

    @Test fun anUnsupportedProfileOffersNoStartAndNamesTheReason() {
        val c = card(compatibility = unsupported)
        assertFalse(c.canStart)
        assertTrue(c.blockedReason!!.contains("YUV_SIZE"))
    }

    @Test fun severeThermalStopsTheRun() {
        val c = card(thermalStatus = StartCardPresenter.THERMAL_SEVERE)
        assertFalse(c.canStart)
        assertTrue(c.blockedReason!!.contains("SEVERE"))
    }

    @Test fun unsupportedIsReportedBeforeThermal() {
        // Waiting for the device to cool would not make an unsupported profile runnable, so the card must not
        // send the developer to wait (3.6: the profile is never lowered to fit).
        val c = card(compatibility = unsupported, thermalStatus = StartCardPresenter.THERMAL_SEVERE)
        assertTrue(c.blockedReason!!.contains("YUV_SIZE"))
    }

    @Test fun aCoolSupportedCameraStarts() {
        val c = card()
        assertTrue(c.canStart)
        assertNull(c.blockedReason)
        assertTrue(c.notices.isEmpty())
    }

    @Test fun anUnknownThermalStatusDoesNotBlock() {
        // Below API 29 there is no thermal status at all; a missing reading is not a hot device.
        val c = card(thermalStatus = null, powerSaveMode = null)
        assertTrue(c.canStart)
        assertTrue(c.notices.isEmpty())
    }

    // ---- what only warns ----

    @Test fun moderateThermalStartsAndNamesTheFlagItWillCarry() {
        val c = card(thermalStatus = ValidityFlags.THERMAL_MODERATE)
        assertTrue(c.canStart)
        assertEquals(1, c.notices.size)
        assertTrue(c.notices[0].contains(ValidityFlags.THERMAL_HIGH.code))
    }

    @Test fun powerSaveModeStartsAndNamesTheFlagItWillCarry() {
        val c = card(powerSaveMode = true)
        assertTrue(c.canStart)
        assertTrue(c.notices.single().contains(ValidityFlags.POWER_SAVE_MODE.code))
    }

    @Test fun enteringFromCameraXAnnouncesTheSwitch() {
        assertTrue(card(engineName = "CameraX").notices.single().contains("Camera2"))
        assertTrue(card(engineName = "Camera2").notices.isEmpty())
    }

    @Test fun everyWarningIsListed() {
        val c = card(engineName = "CameraX", thermalStatus = ValidityFlags.THERMAL_MODERATE, powerSaveMode = true)
        assertEquals(3, c.notices.size)
        assertTrue(c.canStart)
    }

    // ---- what the card says ----

    @Test fun theTitleNamesTheEngineCameraConditionAndLaunchMode() {
        assertEquals("Camera2 · 후면 메인 · 1080p30 · warm reopen", card().titleLine)
    }

    @Test fun theVerdictCarriesTheMethodThatProducedIt() {
        assertEquals("✓ 이 카메라에서 실행 가능 (device_setup)", card().verdictLine)
        assertEquals("✗ 실행할 수 없음 (static_table)", card(compatibility = unsupported).verdictLine)
    }

    @Test fun anUnparsableProfileFallsBackToItsRawStrings() {
        val odd = profile.copy(previewSize = "full", fpsRange = "auto")
        assertEquals("full auto", StartCardPresenter.conditionLabel(odd))
    }

    @Test fun aBlockedCardStillDescribesTheProfile() {
        val c = card(compatibility = unsupported)
        assertEquals("Profile  camera2-standard-v1", c.profileLine)
        assertTrue(c.detailLine.contains("10회 open"))
    }
}
