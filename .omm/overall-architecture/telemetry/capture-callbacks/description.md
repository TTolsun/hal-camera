`app/src/main/java/dev/halcamera/telemetry/Telemetry.kt`. A thin adapter between Camera2 callbacks and `FlightRecorder`, plus a `ConcurrentHashMap` of per-session characteristics.

`callback(sessionId, alive)` builds one `CameraCaptureSession.CaptureCallback` with its own `FrameTracker`. Every override checks `alive()` first, so a callback arriving after the engine was closed records nothing. `onCaptureCompleted` is wrapped in `Trace.beginSection("CD.result")`, which makes the same work visible in a Perfetto trace taken alongside the app.

The values it records per result are the raw metadata the whole metric layer later reads by key: `ae`, `af`, `afMode` (falling back to the request when the result omits it), `awb`, `exposureNs`, `iso`, `frameDurationNs`, `focusDiopters`, `zoomRatio` (API 30+ only), `cropRegion`, plus the derived `intervalMs`, `resultFps` and `observedResultGap`.

Telemetry stores raw state integers; presentation belongs to the UI layer. It does not decode display labels.
