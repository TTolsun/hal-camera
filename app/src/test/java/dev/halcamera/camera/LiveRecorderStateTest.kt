package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class LiveRecorderStateTest {
    @Test fun `idle prepared encoder can stop despite not being busy`() {
        val ready = LiveRecorderState.PREPARED
        assertFalse(ready.busy)
        val failed = ready.stop()
        assertTrue(failed.busy)
        assertTrue(failed.stopping)
        assertFalse(failed.prepared)
        assertFalse(failed.recording)
    }

    @Test fun `stopping retains whether MediaRecorder stop is required`() {
        for (state in LiveRecorderState.entries) {
            val stopped = state.stop()
            assertEquals(state.recording, stopped.recording)
            assertEquals(stopped, stopped.stop())
            assertFalse(stopped.prepared)
        }
        assertFalse(LiveRecorderState.STARTING.stop().recording)
        assertTrue(LiveRecorderState.RECORDING.stop().recording)
    }

    @Test fun `mode availability and effective defaults agree for both PIP inputs`() {
        for (mode in LiveModePolicy.entries) {
            assertEquals(mode == LiveModePolicy.PHOTO, mode.allowsRaw)
            assertEquals(mode == LiveModePolicy.VIDEO, mode.allowsStabilization)
            assertEquals(if (mode.allowsStabilization) LiveStabilization.AUTO else LiveStabilization.OFF, mode.stabilization())
            for (requested in LiveStabilization.entries) {
                assertEquals(if (mode.allowsStabilization) requested else LiveStabilization.OFF, mode.stabilization(requested))
            }
        }
    }
}
