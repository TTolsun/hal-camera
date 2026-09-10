Stage 0, the source. Everything measured originates in an Android callback; nothing is polled and nothing is timed by a loop.

Four callback families feed the pipeline. `CameraCaptureSession.CaptureCallback` supplies `onCaptureStarted` (frame number, sensor timestamp, request tag), `onCaptureCompleted` (the full `TotalCaptureResult`), `onCaptureFailed` and `onCaptureBufferLost`. `CameraDevice.StateCallback` supplies open, disconnect, error and closed. `ImageReader.OnImageAvailableListener` (Camera2) and the `ImageAnalysis` analyzer (CameraX) supply image arrival with the image's own timestamp. `CameraState` on the CameraX side supplies open and closed transitions.

The stage's defining limit is what these callbacks *are*: they report when the app was notified, not when the hardware did the work. Every downstream name reflects that — `request_observed`, `observedResultGap`, `partialMs` — and the exported bundles restate it in prose.
