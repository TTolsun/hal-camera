package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class BracketFilesTest {
    @Test fun `a bracket request id becomes a file name tag`() {
        assertEquals("AEB1_EV+0.0", BracketFiles.nameTag("bracket-124404_265-1-ev+0.0"))
        assertEquals("AEB2_EV-2.0", BracketFiles.nameTag("bracket-124404_265-2-ev-2.0"))
        assertEquals("AEB3_EV+2.0", BracketFiles.nameTag("bracket-124404_265-3-ev+2.0"))
    }

    @Test fun `only bracket shots get a tagged file name`() {
        assertEquals("HAL_1_ab_AEB2_EV-2.0", BracketFiles.named("HAL_1_ab", "bracket-x-2-ev-2.0"))
        assertEquals("HAL_1_ab", BracketFiles.named("HAL_1_ab", "burst-x-01"))
        assertEquals("HAL_1_ab", BracketFiles.named("HAL_1_ab", null))
    }

    @Test fun `other requests get no tag`() {
        assertNull(BracketFiles.nameTag(null))
        assertNull(BracketFiles.nameTag("burst-124925_817-01"))
        assertNull(BracketFiles.nameTag("cli-7"))
    }

    @Test fun `the tag uses the EV the plan wrote`() {
        val id = "bracket-x-2-${BracketPlan.tag(-20, 0.1)}"
        assertEquals("AEB2_EV-2.0", BracketFiles.nameTag(id))
    }

    @Test fun `the gallery badge names the shot and its output`() {
        assertEquals("AEB −2.0 · JPEG", BracketFiles.badge("HAL_20261009_124404_265_ab12cd_AEB2_EV-2.0_JPEG.jpg"))
        assertEquals("AEB +0.0 · YUV", BracketFiles.badge("HAL_20261009_124404_265_ab12cd_AEB1_EV+0.0_YUV.jpg"))
        assertEquals("AEB +2.0 · RAW", BracketFiles.badge("HAL_20261009_124404_265_ab12cd_AEB3_EV+2.0_RAW.dng"))
        assertEquals("AEB · HDR", BracketFiles.badge("HAL_20261009_124409_001_ef34ab_AEB_HDR.jpg"))
    }

    @Test fun `ordinary photos get no bracket badge`() {
        assertNull(BracketFiles.badge("HAL_20261009_124404_265_ab12cd_JPEG.jpg"))
        assertNull(BracketFiles.badge("HAL_20261009_124404_265_ab12cd_YUV.jpg"))
        assertNull(BracketFiles.badge("HAL_20261009_124404_265_ab12cd.mp4"))
    }
}
