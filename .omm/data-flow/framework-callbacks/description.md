Stage 0, the source. Everything measured originates in an Android callback; nothing is polled and nothing is timed by a loop.

Four callback families feed the pipeline. `CameraCaptureSession.CaptureCallback` supplies `onCaptureStarted` (frame number, sensor timestamp, request tag), `onCaptureCompleted` (the full `TotalCaptureResult`), `onCaptureFailed` and `onCaptureBufferLost`. `CameraDevice.StateCallback` supplies open, disconnect, error and closed. `ImageReader.OnImageAvailableListener` (Camera2) and the `ImageAnalysis` analyzer (CameraX) supply image arrival with the image's own timestamp. `CameraState` on the CameraX side supplies open and closed transitions; the camerax-callbacks child describes that wiring.

The stage's defining limit is what these callbacks *are*: they report when the app was notified, not when the hardware did the work. Every downstream name reflects that — `request_observed`, `observedResultGap`, `partialMs` — and the exported bundles restate it in prose.

콜백 시간축용으로 capture_partial과 preview_presented를 관측하며, callback_streams 이벤트와 세션의 callbackStreams에 현재 출력 구성 및 관측 가능 여부를 기록합니다. 기존 capture_result·image_available의 의미는 유지합니다.
