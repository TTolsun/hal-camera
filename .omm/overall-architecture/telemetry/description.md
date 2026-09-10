`app/src/main/java/dev/halcamera/telemetry/` — the recording layer every measurement is derived from. Three files: `Telemetry.kt` (the Camera2 callback adapter), `FlightRecorder.kt` (the ring buffer and incident windows) and `IncidentExporter.kt` (the ZIP bundle).

`Telemetry` turns framework callbacks into flat `Event` records. `onCaptureStarted` produces `capture_started` plus `request_observed`; `onCaptureCompleted` produces `capture_result` carrying AE/AF/AWB state, exposure, ISO, frame duration, focus distance, zoom, crop region and the derived interval and FPS; `onCaptureFailed` and `onCaptureBufferLost` produce `capture_failed` and `buffer_lost`. `image()` records `image_available` from either engine's reader or analyzer.

`FlightRecorder` holds those events in a time- and size-bounded ring and can additionally capture an incident: a pre-trigger slice already in the buffer plus a post-trigger window still to arrive.

The layer's discipline is that it records observations, not conclusions. `request_observed` carries an explicit note that it is emitted at `onCaptureStarted` and is not a request-submission timestamp, and `FrameStats` documents that its gaps are observed result gaps, not HAL drops.
