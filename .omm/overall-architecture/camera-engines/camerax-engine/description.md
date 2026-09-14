`app/src/main/java/dev/halcamera/camera/CameraXEngine.kt` (118 lines). The comparison engine, used only from `MainActivity`.

It binds `Preview`, `ImageAnalysis` (KEEP_ONLY_LATEST) and `ImageCapture` (MINIMIZE_LATENCY) to the activity lifecycle through `ProcessCameraProvider`. The point of the class is that it feeds the *same* `Telemetry.callback` as Camera2: `Camera2Interop.Extender(builder).setSessionCaptureCallback(...)` attaches the capture callback to the preview use case, so both engines produce identical event kinds and the numbers stay comparable.

Camera selection goes through a `CameraSelector` filter matching `Camera2CameraInfo.from(it).cameraId`, so a specific camera id can be targeted rather than just front or back. Zoom is `CameraControl.setZoomRatio` clamped to the reported `zoomState`. Negotiated resolutions are read back from each use case's `resolutionInfo` and stored in the session map exactly as Camera2 does.

Its close path is the delicate one: it observes `cameraState` with `observeForever` rather than a lifecycle-bound observer, precisely so the release still completes while the Activity is stopped, and guards `finish()` with a `finished` flag against the case where the state is already CLOSED when `unbind` returns.
