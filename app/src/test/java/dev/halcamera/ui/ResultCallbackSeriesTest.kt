package dev.halcamera.ui

import dev.halcamera.telemetry.Event
import dev.halcamera.camera.OutputDescriptor
import dev.halcamera.camera.OutputKind
import dev.halcamera.camera.DualOutputs
import dev.halcamera.camera.ConfiguredOutput
import dev.halcamera.camera.StreamConfiguration
import org.junit.Assert.*
import org.junit.Test

class ResultCallbackSeriesTest {
    @Test fun dualGlInputRequiresBothPhysicalIdentityAndAnUnambiguousFrameEvenWhenMetadataIsLate() {
        val rows = listOf("6", "5").mapIndexed(DualOutputs::glPreview)
        val config = metadata(*rows.map { it.metadata() }.toTypedArray())
        fun input(time: Long, stream: String, physical: String?, stamp: Long) =
            Event(time, "s", DualOutputs.GL_INPUT, sensorNs = stamp,
                values = mapOf("stream" to stream, "physicalId" to physical))
        fun result(frame: Long, main: Long, sub: Long) = Event(80 + frame, "s", "capture_result", frame,
            values = mapOf("physicalTimestamps" to mapOf("6" to main, "5" to sub)))
        val inputs = listOf(
            Event(10, "s", "capture_started", 1, 700, mapOf("firstStart" to true)),
            Event(20, "s", "capture_started", 2, 800), Event(30, "s", "capture_started", 3, 900),
            input(40, "preview_main", "6", 501), input(41, "preview_sub", "5", 501),
            input(42, "preview_main", "5", 501), input(43, "preview_main", null, 501),
            input(44, "preview_sub", "5", 700), input(45, "preview_main", "6", 503),
            input(46, "preview_sub", "5", 601), input(47, "preview_sub", "5", 0),
            Event(48, "s", "preview_available", sensorNs = 501, values = mapOf("stream" to "preview_main")))
        fun previews(events: List<Event>) = ResultCallbackSeries.read(events, "s", 120, config).tracks.takeLast(2)
        assertTrue(previews(inputs).all { it.points.isEmpty() && it.unavailable == null })
        val results = listOf(result(1, 501, 601), result(2, 503, 501), result(3, 504, 0),
            result(4, 503, 601), result(5, 501, 501).copy(session = "other"))
        val tracks = previews(inputs + results)
        assertEquals(listOf(40L to 10L), tracks.first().points.map { it.atNs to it.startAtNs })
        assertEquals(listOf(41L to 20L), tracks.last().points.map { it.atNs to it.startAtNs })
    }
    @Test fun appConversionAndComposedRecordingEventsNeverBecomeCameraCallbackRows() {
        val config = StreamConfiguration(listOf(ConfiguredOutput(OutputDescriptor("yuv", OutputKind.YUV, true), "camera-surface")))
        val events = listOf(Event(0, "s", "capture_started", 1, 900L, mapOf("firstStart" to true)),
            Event(20_000_000, "s", "capture_result", 1, 900L),
            Event(30_000_000, "s", "image_available", sensorNs = 900L, values = mapOf("stream" to "yuv")),
            Event(40_000_000, "s", "app_jpeg_available", sensorNs = 900L, values = mapOf("stream" to "yuv_jpeg")),
            Event(50_000_000, "s", "pip_photo_available", sensorNs = 900L, values = mapOf("stream" to "pip_photo")),
            Event(60_000_000, "s", "image_available", sensorNs = 900L, values = mapOf("stream" to "composite_recording")))
        val series = ResultCallbackSeries.read(events, "s", 300_000_000,
            metadata(*config.metadata().toTypedArray()))
        assertEquals(listOf("start", "all", "yuv"), series.tracks.map { it.id })
        assertEquals(30.0, series.tracks.single { it.id == "yuv" }.points.single().latencyMs!!, 0.001)
    }
    @Test fun physicalPipCanUseLogicalSurfaceTimestampWithoutBorrowingAnotherSensorTimestamp() {
        val events = listOf(
            Event(100_000_000, "s", "capture_started", 8, 900, mapOf("firstStart" to true)),
            Event(150_000_000, "s", "capture_result", 8, 900, mapOf("physicalTimestamps" to mapOf("6" to 850L))),
            Event(160_000_000, "s", "pip_preview_available", sensorNs = 900, values = mapOf("stream" to "pip")))
        val config = metadata(stream("pip") + mapOf("eventKind" to "pip_preview_available", "physicalId" to "6"))
        val point = ResultCallbackSeries.read(events, "s", 200_000_000, config).tracks.last().points.single()
        assertEquals(100_000_000L, point.startAtNs)
        assertEquals(60.0, point.latencyMs!!, 0.001)
    }

    @Test fun pipIncludesEveryPhysicalResultAndMatchesItsOwnSensorTimestamp() {
        val events = listOf(
            Event(100_000_000, "s", "capture_started", 8, 900, mapOf("firstStart" to true)),
            Event(150_000_000, "s", "capture_result", 8, 900,
                mapOf("physicalTimestamps" to mapOf("2" to 905L, "5" to 901L))),
            Event(160_000_000, "s", "pip_preview_available", sensorNs = 900, values = mapOf("stream" to "preview")),
            Event(170_000_000, "s", "pip_preview_available", sensorNs = 905, values = mapOf("stream" to "pip")),
            Event(180_000_000, "other-service", "capture_result", 8, 900,
                mapOf("physicalTimestamps" to mapOf("9" to 905L))))
        val config = metadata(stream("preview") + ("eventKind" to "pip_preview_available"),
            OutputDescriptor("pip", OutputKind.YUV, true, physicalId = "2",
                eventKind = "pip_preview_available").metadata())
        val tracks = ResultCallbackSeries.read(events, "s", 200_000_000, config).tracks
        assertEquals(listOf("start", "all", "metadata:2", "metadata:5", "preview", "pip"), tracks.map { it.id })
        assertEquals(listOf("Meta (Phy)", "Meta (Phy)", "preview", "YUV (Phy)"), tracks.drop(2).map { it.label })
        assertEquals(listOf(50.0, 50.0, 60.0, 70.0), tracks.drop(2).map { it.points.single().latencyMs })
        assertTrue(tracks.all { it.points.single().startAtNs == 100_000_000L })
    }

    @Test fun physicalBuffersMatchTheirLogicalRequestWithoutSharingSensorTimestamps() {
        val events = listOf(
            Event(100_000_000, "s", "capture_started", 8, 900, mapOf("firstStart" to true)),
            Event(160_000_000, "s", "preview_available", sensorNs = 901, values = mapOf("stream" to "preview_main")),
            Event(170_000_000, "s", "preview_available", sensorNs = 905, values = mapOf("stream" to "preview_sub")),
            Event(180_000_000, "s", "dual_physical_result", 8, values = mapOf("timestamps" to mapOf("5" to 901L, "6" to 905L)))
        )
        val config = metadata(
            stream("preview_main") + ("eventKind" to "preview_available"),
            stream("preview_sub") + ("eventKind" to "preview_available")) + ("physicalIds" to listOf("5", "6"))
        val tracks = ResultCallbackSeries.read(events, "s", 200_000_000, config).tracks
        assertEquals(60.0, tracks[2].points.single().latencyMs!!, 0.001)
        assertEquals(70.0, tracks[3].points.single().latencyMs!!, 0.001)
    }
    @Test fun dualPhotoCanMatchTheLogicalStillResultTimestampButNeverBorrowPreviewTime() {
        val events = listOf(
            Event(10, "s", "capture_started", 1, 100, mapOf("firstStart" to true)),
            Event(20, "s", "capture_result", 1, 100),
            Event(30, "s", "capture_started", 2, 200, mapOf("requestTag" to "dual_photo")),
            Event(40, "s", "capture_result", 2, 201, mapOf("physicalTimestamps" to mapOf("6" to 202L))),
            Event(50, "s", "image_available", sensorNs = 201, values = mapOf("stream" to "photo_sub")),
            Event(60, "s", "image_available", sensorNs = 100, values = mapOf("stream" to "photo_sub")))
        val config = metadata(stream("photo_sub")) + ("physicalIds" to listOf("5", "6"))
        assertEquals(listOf(30L, null), ResultCallbackSeries.read(events, "s", 60, config).tracks.last().points.map { it.startAtNs })
    }

    @Test fun matchingTimestampOnOtherSensorDoesNotSelectTheWrongFrame() {
        val events = listOf(
            Event(100_000_000, "s", "capture_started", 8, 900, mapOf("firstStart" to true)),
            Event(130_000_000, "s", "capture_started", 9, 930, mapOf("previousStartAtNs" to 100_000_000L)),
            Event(150_000_000, "s", "dual_physical_result", 8, values = mapOf("timestamps" to mapOf("5" to 901L, "6" to 905L))),
            Event(160_000_000, "s", "dual_physical_result", 9, values = mapOf("timestamps" to mapOf("5" to 931L, "6" to 901L))),
            Event(170_000_000, "s", "preview_available", sensorNs = 901, values = mapOf("stream" to "preview_main")))
        val config = metadata(stream("preview_main") + ("eventKind" to "preview_available")) +
            ("physicalIds" to listOf("5", "6"))
        assertEquals(100_000_000L, ResultCallbackSeries.read(events, "s", 200_000_000, config).tracks[2].points.single().startAtNs)
    }
    @Test fun previewUsesBufferReceiptAndIgnoresLaterDisplayUpdates() {
        val config = metadata(stream("preview", "Preview") + ("eventKind" to "preview_available"))
        val events = listOf(
            Event(100_000_000, "s", "capture_started", 8, 900,
                mapOf("previousStartAtNs" to 67_000_000L)),
            Event(160_000_000, "s", "preview_available", sensorNs = 900, values = mapOf("stream" to "preview")),
            Event(210_000_000, "s", "preview_presented", sensorNs = 900, values = mapOf("stream" to "preview")))
        val series = ResultCallbackSeries.read(events, "s", 250_000_000, config)
        assertEquals("Shutter", series.tracks[0].label)
        assertEquals(93.0, series.tracks[2].points.single().latencyMs!!, 0.001)
        val displayOnly = ResultCallbackSeries.read(events.filter { it.kind != "preview_available" }, "s", 250_000_000, config)
        assertTrue(displayOnly.tracks[2].points.isEmpty())
    }

    private fun stream(id: String, label: String = id, observable: Boolean = true) =
        mapOf("id" to id, "label" to label, "observable" to observable)
    private fun metadata(vararg outputs: Map<String, Any?>) = mapOf("callbackStreams" to outputs.toList(), "partialResultCount" to 2)

    @Test fun metadataRowsAlwaysExistAndOutputsFollowConfigurationWithoutNeedingSamples() {
        val config = metadata(stream("preview"), stream("yuv"), stream("jpeg"), stream("raw"))
        val series = ResultCallbackSeries.read(emptyList(), "s", 0, config)
        assertEquals(listOf("start", "all", "preview", "yuv", "jpeg", "raw"), series.tracks.map { it.id })
        assertTrue(series.tracks.all { it.points.isEmpty() })
    }

    @Test fun firstStartIsZeroAndPartialIsExcluded() {
        val events = listOf(
            Event(100_000_000, "s", "capture_started", 7, 999, mapOf("firstStart" to true)),
            Event(120_000_000, "other", "capture_started", 7, 999),
            Event(125_000_000, "s", "capture_partial", 7),
            Event(145_000_000, "s", "capture_result", 7)
        )
        val tracks = ResultCallbackSeries.read(events, "s", 150_000_000, metadata()).tracks
        assertEquals(0.0, tracks[0].points.single().latencyMs!!, 0.001)
        assertEquals(45.0, tracks[1].points.single().latencyMs!!, 0.001)
        assertFalse(tracks.any { it.id == "partial" })
    }

    @Test fun bufferLatencyMatchesSensorTimestampWithoutSubtractingSensorAndAppClocks() {
        val sensorTime = 99_000_000_000L
        val events = listOf(Event(100_000_000, "s", "capture_started", 1, sensorTime, mapOf("firstStart" to true)),
            Event(150_000_000, "s", "image_available", sensorNs = sensorTime, values = mapOf("stream" to "jpeg")),
            Event(180_000_000, "s", "preview_available", sensorNs = sensorTime, values = mapOf("stream" to "preview")))
        val tracks = ResultCallbackSeries.read(events, "s", 200_000_000, metadata(stream("jpeg"), stream("preview"))).tracks
        assertEquals(50.0, tracks[2].points.single().latencyMs!!, 0.001)
        assertEquals(80.0, tracks[3].points.single().latencyMs!!, 0.001)
    }

    @Test fun unobservableOutputsNeverBorrowMetadataNumbers() {
        val events = listOf(Event(0, "s", "capture_started", 1, values = mapOf("firstStart" to true)), Event(20_000_000, "s", "capture_result", 1))
        val config = metadata(stream("recording", observable = false)) + ("partialResultCount" to 1)
        val tracks = ResultCallbackSeries.read(events, "s", 40_000_000, config).tracks
        assertEquals("No callback", tracks[2].unavailable)
        assertTrue(tracks[2].points.isEmpty())
        assertEquals(20.0, tracks[1].points.single().latencyMs!!, 0.001)
    }

    @Test fun reconfigurationDropsOldOutputRowsAndOldFrameNumbers() {
        val events = listOf(Event(0, "s", "capture_started", 1), Event(20_000_000, "s", "capture_result", 1),
            Event(120_000_000, "s", "capture_result", 1))
        val config = metadata(stream("preview"), stream("recording", observable = false)) + ("callbackStreamsAtNs" to 100_000_000L)
        val series = ResultCallbackSeries.read(events, "s", 150_000_000, config)
        assertEquals(1, series.tracks[1].points.size)
        assertNull(series.tracks[1].points.single().latencyMs)
        assertFalse(series.tracks.any { it.id == "jpeg" })
    }

    @Test fun signedTimeDifferencesArePreservedWhileMissingFutureAndExpiredSamplesAreExcluded() {
        val events = listOf(Event(200_000_000, "s", "capture_started", 1, values = mapOf("firstStart" to true)),
            Event(150_000_000, "s", "capture_result", 1), Event(250_000_000, "s", "capture_result", 2),
            Event(900_000_000, "s", "capture_result", 3))
        val points = ResultCallbackSeries.read(events, "s", 300_000_000, metadata()).tracks[1].points
        assertEquals(2, points.size)
        assertEquals(-50.0, points[0].latencyMs!!, 0.001)
        assertNull(points[1].latencyMs)
        assertTrue(ResultCallbackSeries.read(events, "s", 12_000_000_000, metadata()).tracks.all { it.points.isEmpty() })
    }

    @Test fun successiveFramesUsePreviousStartForEveryRowWithoutAccumulating() {
        val events = listOf(
            Event(100_000_000, "s", "capture_started", 1, values = mapOf("firstStart" to true)),
            Event(133_000_000, "s", "capture_started", 2, 200, mapOf("previousStartAtNs" to 100_000_000L)),
            Event(143_000_000, "s", "capture_result", 2),
            Event(153_000_000, "s", "image_available", sensorNs = 200, values = mapOf("stream" to "jpeg")),
            Event(166_000_000, "s", "capture_started", 3, values = mapOf("previousStartAtNs" to 133_000_000L)))
        val tracks = ResultCallbackSeries.read(events, "s", 200_000_000, metadata(stream("jpeg"))).tracks
        assertEquals(listOf(0.0, 33.0, 33.0), tracks[0].points.map { it.latencyMs })
        assertEquals(43.0, tracks[1].points.single().latencyMs!!, 0.001)
        assertEquals(53.0, tracks[2].points.single().latencyMs!!, 0.001)
        assertEquals(133_000_000L, tracks[2].points.single().startAtNs)
    }

    @Test fun recordedReferenceSurvivesEvictionAndMissingHistoryIsNotZero() {
        val start = Event(40_000_000_000, "s", "capture_started", 700,
            values = mapOf("previousStartAtNs" to 39_967_000_000L))
        val events = listOf(start, Event(40_020_000_000, "s", "capture_result", 700))
        val tracks = ResultCallbackSeries.read(events, "s", 40_100_000_000, metadata()).tracks
        assertEquals(33.0, tracks[0].points.single().latencyMs!!, 0.001)
        assertEquals(53.0, tracks[1].points.single().latencyMs!!, 0.001)
        val unknown = ResultCallbackSeries.read(listOf(start.copy(values = emptyMap())), "s", 40_100_000_000, metadata())
        assertNull(unknown.tracks[0].points.single().latencyMs)
    }

    @Test fun firstStartAfterReconfigurationResetsEvenWithEarlierFramesRetained() {
        val events = listOf(
            Event(100_000_000, "s", "capture_started", 1, values = mapOf("firstStart" to true)),
            Event(200_000_000, "s", "capture_started", 2, values = mapOf("firstStart" to true)),
            Event(233_000_000, "s", "capture_started", 3, values = mapOf("previousStartAtNs" to 200_000_000L)))
        val series = ResultCallbackSeries.read(events, "s", 250_000_000,
            metadata() + ("callbackStreamsAtNs" to 150_000_000L))
        assertEquals(listOf(0.0, 33.0), series.tracks[0].points.map { it.latencyMs })
        assertEquals(listOf(2L, 3L), series.frames.map { it.number })
    }

    @Test fun recordingUsesItsOwnPrivateBufferArrivalAndExactSensorIdentity() {
        val config = metadata(stream("recording", "Recording"))
        val start = Event(100_000_000, "s", "capture_started", 8, 90_000_000_000L,
            mapOf("previousStartAtNs" to 67_000_000L))
        val metadata = Event(160_000_000, "s", "capture_result", 8, 90_000_000_000L)
        val noBuffer = ResultCallbackSeries.read(listOf(start, metadata), "s", 200_000_000, config)
        assertTrue(noBuffer.tracks[2].points.isEmpty())
        val buffer = Event(175_000_000, "s", "image_available", sensorNs = 90_000_000_000L,
            values = mapOf("stream" to "recording", "format" to 34))
        val series = ResultCallbackSeries.read(listOf(start, metadata, buffer), "s", 200_000_000, config)
        assertEquals(108.0, series.tracks[2].points.single().latencyMs!!, 0.001)
        assertEquals(100_000_000L, series.tracks[2].points.single().startAtNs)
        assertEquals(93.0, series.tracks[1].points.single().latencyMs!!, 0.001)
    }
}
