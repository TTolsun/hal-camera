package dev.halcamera.ui

import dev.halcamera.telemetry.Event
import dev.halcamera.camera.ConfiguredOutput
import dev.halcamera.camera.OutputDescriptor
import dev.halcamera.camera.OutputKind
import dev.halcamera.camera.StreamConfiguration
import org.junit.Assert.*
import org.junit.Test

class ResultCallbackTimelineTest {
    @Test fun eachUpdateShowsTheNewestCompleteFrameWithoutAnExtraRefreshDelay() {
        val t = ResultCallbackTimeline().apply { autoHold = false }
        val events = frame(1, 0) + frame(2, 33) + frame(3, 66)
        update(t, events, 100)
        assertEquals(1L, t.displayed!!.number)
        update(t, events, 133)
        assertEquals(2L, t.displayed!!.number)
        update(t, events, 166)
        assertEquals(3L, t.displayed!!.number)
        assertEquals(listOf(133.0), t.displayed!!.rows[2].latenciesMs)
    }

    @Test fun missingOutputCannotKeepAnOldCompleteFrameDisplayedIndefinitely() {
        val t = ResultCallbackTimeline().apply { autoHold = false }
        val events = frame(1, 0) + frame(2, 100).filter { it.kind != "preview_available" }
        update(t, events, 200)
        assertEquals(1L, t.displayed!!.number)
        update(t, events, 350)
        assertEquals(2L, t.displayed!!.number)
        assertEquals("Awaiting data", t.displayed!!.rows[2].state)
        assertTrue(t.displayed!!.rows[2].latenciesMs.isEmpty())
        update(t, events + e(360, "preview_available", sensor = 2, values = mapOf("stream" to "preview")), 360)
        assertEquals(listOf(293.0), t.displayed!!.rows[2].latenciesMs)
    }

    @Test fun durationCyclesThroughRequestedOrderAndWrapsToThreeSeconds() {
        val t = ResultCallbackTimeline()
        val values = (0..6).map { t.holdSeconds.also { t.cycleHoldSeconds() } }
        assertEquals(listOf(3, 5, 10, 15, 30, 1, 3), values)
    }
    @Test fun automaticAxisExpandsImmediatelyAndShrinksOnlyAfterThreeStableSeconds() {
        val t = ResultCallbackTimeline().apply { autoHold = false }
        val slow = frame(1, 0).filter { it.kind != "preview_available" } +
            e(800, "preview_available", sensor = 1, values = mapOf("stream" to "preview"))
        update(t, slow, 800)
        assertEquals(1000, t.axisMs)
        update(t, frame(2, 1000), 1100)
        assertEquals(1000, t.axisMs)
        update(t, frame(3, 1500), 1600)
        update(t, frame(4, 3999), 4099)
        assertEquals(1000, t.axisMs)
        update(t, frame(4, 3999), 4100)
        assertEquals(200, t.axisMs)
    }

    @Test fun automaticAxisDoesNotShrinkDuringHoldAndIncludesLateResult() {
        val t = ResultCallbackTimeline().apply { holdSeconds = 10 }
        update(t, frame(1, 0), 100)
        val photo = frame(2, 1000, true)
        update(t, photo, 1100)
        val late = photo.filter { it.kind != "capture_result" } + e(2300, "capture_result", 2)
        update(t, late, 2300)
        assertEquals(2000, t.axisMs)
        update(t, late, 7000)
        assertEquals(2000, t.axisMs)
        t.toggleAutoHold(7_000_000_000)
        update(t, frame(3, 8000), 8100)
        assertEquals(2000, t.axisMs)
    }

    @Test fun sixRowsTrackMultipleYuvOutputsByIdAndSingleShotYuvAlsoTriggersHold() {
        val outputs = StreamConfiguration(listOf(
            ConfiguredOutput(OutputDescriptor("view", OutputKind.PREVIEW, true), 0),
            ConfiguredOutput(OutputDescriptor("small", OutputKind.YUV, true), 1),
            ConfiguredOutput(OutputDescriptor("large", OutputKind.YUV, false, stillCapture = true), 2),
            ConfiguredOutput(OutputDescriptor("compressed", OutputKind.JPEG, false, stillCapture = true), 3)))
        val meta = mapOf("callbackStreams" to outputs.metadata(), "partialResultCount" to 2)
        val t = ResultCallbackTimeline()
        t.update(ResultCallbackSeries.read(emptyList(), "s", 0, meta), "s", 0, 0)
        val events = listOf(e(10, "capture_partial", 7), e(100, "capture_started", 7, values = mapOf("firstStart" to true)),
            e(160, "capture_result", 7),
            e(180, "image_available", sensor = 7, values = mapOf("stream" to "small")),
            e(210, "image_available", sensor = 7, values = mapOf("stream" to "large")),
            e(220, "preview_available", sensor = 7, values = mapOf("stream" to "view")))
        t.update(ResultCallbackSeries.read(events, "s", 220_000_000, meta), "s", 0, 220_000_000)
        assertEquals(7L, t.displayed!!.number)
        assertEquals(6, t.displayed!!.rows.size)
        assertEquals(listOf("Shutter", "Metadata", "Preview", "YUV 1", "YUV 2", "JPEG"), t.displayed!!.rows.map { it.label })
        assertEquals(listOf(80.0), t.displayed!!.rows[3].latenciesMs)
        assertEquals(listOf(110.0), t.displayed!!.rows[4].latenciesMs)
        assertEquals(listOf(120.0), t.displayed!!.rows[2].latenciesMs)
        assertNotNull(t.autoHoldUntilNs)
    }
    @Test fun partialReceivedBeforeStartedIsExcludedAndStartUsesPreviousCallback() {
        val t = ResultCallbackTimeline()
        val events = listOf(e(20, "capture_partial", 7), e(100, "capture_started", 7, values = mapOf("previousStartAtNs" to 67_000_000L)),
            e(160, "capture_result", 7), e(200, "preview_available", sensor = 7, values = mapOf("stream" to "preview")))
        update(t, events, 200)
        assertEquals(7L, t.displayed!!.number)
        assertEquals(listOf(33.0), t.displayed!!.rows[0].latenciesMs)
        assertEquals(listOf(93.0), t.displayed!!.rows[1].latenciesMs)
        assertFalse(t.displayed!!.rows.any { it.id == "partial" })
    }
    private val config = mapOf("partialResultCount" to 2, "callbackStreams" to listOf(
        mapOf("id" to "preview", "label" to "Preview", "repeating" to true),
        mapOf("id" to "still", "label" to "JPEG", "repeating" to false)))
    private fun e(ms: Long, kind: String, frame: Long? = null, sensor: Long? = frame, values: Map<String, Any?> = emptyMap()) =
        Event(ms * 1_000_000, "s", kind, frame, sensor, values)
    private fun frame(number: Long, start: Long, photo: Boolean = false) = listOf(
        e(start, "capture_started", number, values = mapOf("streams" to listOf(if (photo) "still" else "preview"),
            "firstStart" to (number == 1L), "previousStartAtNs" to if (number == 1L) null else (start - 33) * 1_000_000)),
        e(start + 20, "capture_partial", number), e(start + 30, "capture_partial", number),
        e(start + 60, "capture_result", number),
        e(start + 100, if (photo) "image_available" else "preview_available", sensor = number,
            values = mapOf("stream" to if (photo) "still" else "preview")))
    private fun update(t: ResultCallbackTimeline, events: List<Event>, ms: Long, session: String = "s", configuredAt: Long = 0) {
        t.update(ResultCallbackSeries.read(events, session, ms * 1_000_000, config + ("callbackStreamsAtNs" to configuredAt)),
            session, configuredAt, ms * 1_000_000)
    }

    @Test fun allRowsBelongToOneFrameAndPartialArrivalsAreIgnored() {
        val t = ResultCallbackTimeline()
        update(t, frame(1, 0) + frame(2, 80), 150)
        assertEquals(1L, t.displayed!!.number)
        assertEquals(listOf(0.0), t.displayed!!.rows[0].latenciesMs)
        assertEquals(listOf(60.0), t.displayed!!.rows[1].latenciesMs)
        assertEquals("Not requested", t.displayed!!.rows.last().state)
        assertEquals(200, t.axisMs)
    }

    @Test fun jpegSelectsItsOwnFrameThenAutomaticallyResumesWithoutRetriggering() {
        val t = ResultCallbackTimeline().apply { holdSeconds = 3 }
        update(t, frame(1, 0), 100)
        val events = frame(1, 0) + frame(2, 1000, true) + frame(3, 1050)
        update(t, events, 1200)
        assertEquals(2L, t.displayed!!.number)
        assertEquals("Not requested", t.displayed!!.rows[2].state)
        assertEquals(listOf(33.0), t.displayed!!.rows[0].latenciesMs)
        assertEquals(listOf(133.0), t.displayed!!.rows[3].latenciesMs)
        assertEquals(3, t.remainingSeconds(1_200_000_000))
        update(t, events, 4199)
        assertEquals(2L, t.displayed!!.number)
        update(t, events, 4200)
        assertNull(t.autoHoldUntilNs)
        assertEquals(3L, t.displayed!!.number)
        update(t, events, 4300)
        assertNull(t.autoHoldUntilNs)
    }

    @Test fun openingOverlayAndEnablingAutoHoldNeverReplayOldPhotos() {
        val t = ResultCallbackTimeline().apply { autoHold = false }
        val events = frame(1, 0, true)
        update(t, events, 200)
        t.autoHold = true
        update(t, events, 700)
        assertNull(t.autoHoldUntilNs)
        t.reset()
        update(t, events, 1000)
        assertNull(t.autoHoldUntilNs)
    }

    @Test fun disabledAutoHoldAndRepeatingOutputsDoNotFreeze() {
        val t = ResultCallbackTimeline().apply { autoHold = false }
        update(t, frame(1, 0), 100)
        update(t, frame(1, 0) + frame(2, 1000, true), 1200)
        assertNull(t.autoHoldUntilNs)
        t.autoHold = true
        update(t, frame(3, 2000), 2200)
        assertNull(t.autoHoldUntilNs)
    }

    @Test fun buttonDisablesAutoHoldAndReenablingWaitsForTheNextPhotograph() {
        val t = ResultCallbackTimeline()
        update(t, frame(1, 0), 100)
        t.toggleAutoHold(100_000_000)
        val events = frame(1, 0) + frame(2, 1000, true)
        update(t, events, 1200)
        assertFalse(t.autoHold)
        assertNull(t.autoHoldUntilNs)
        t.toggleAutoHold(1_200_000_000)
        update(t, events, 1300)
        assertTrue(t.autoHold)
        assertNull(t.autoHoldUntilNs)
        update(t, events + frame(3, 2000, true), 2100)
        assertEquals(3L, t.displayed!!.number)
        assertNotNull(t.autoHoldUntilNs)
        t.toggleAutoHold(2_100_000_000)
        assertFalse(t.autoHold)
        assertNull(t.autoHoldUntilNs)
    }

    @Test fun enablingAutoHoldDoesNotFreezeTheCurrentPreviewFrame() {
        val t = ResultCallbackTimeline().apply { autoHold = false }
        update(t, frame(1, 0), 100)
        t.toggleAutoHold(100_000_000)
        update(t, frame(2, 1000), 1100)
        assertTrue(t.autoHold)
        assertEquals(2L, t.displayed!!.number)
        assertNull(t.autoHoldUntilNs)
    }

    @Test fun lateResultsFillOnlyHeldFrameAndNewShotRestartsTimer() {
        val t = ResultCallbackTimeline().apply { holdSeconds = 5 }
        update(t, frame(1, 0), 100)
        val photo = frame(2, 1000, true).filter { it.kind != "capture_result" }
        update(t, photo, 1100)
        assertTrue(t.displayed!!.rows[1].latenciesMs.isEmpty())
        update(t, photo + e(1250, "capture_result", 2) + frame(3, 1150), 1300)
        assertEquals(2L, t.displayed!!.number)
        assertEquals(listOf(33.0), t.displayed!!.rows[0].latenciesMs)
        assertEquals(listOf(283.0), t.displayed!!.rows[1].latenciesMs)
        update(t, photo + frame(4, 2000, true), 2100)
        assertEquals(4L, t.displayed!!.number)
        assertEquals(7_100_000_000L, t.autoHoldUntilNs)
    }

    @Test fun reconfigurationClearsHeldFrameAndThirtySecondHoldOutlivesRecorderWindow() {
        val t = ResultCallbackTimeline().apply { holdSeconds = 30 }
        update(t, frame(1, 0), 100)
        update(t, frame(2, 1000, true), 1100)
        update(t, emptyList(), 15000)
        assertEquals(2L, t.displayed!!.number)
        update(t, emptyList(), 16000, configuredAt = 15_000_000_000L)
        assertNull(t.displayed!!.number)
        assertNull(t.autoHoldUntilNs)
    }

    @Test fun unmatchedPhotoDoesNotBorrowLatencyFromAnotherFrame() {
        val t = ResultCallbackTimeline()
        update(t, frame(1, 0), 100)
        update(t, frame(1, 0) + e(200, "image_available", sensor = 999, values = mapOf("stream" to "still")), 200)
        assertNull(t.displayed!!.number)
        assertEquals("No start time", t.displayed!!.rows.last().state)
        assertTrue(t.displayed!!.rows.all { it.latenciesMs.isEmpty() })
    }
}
