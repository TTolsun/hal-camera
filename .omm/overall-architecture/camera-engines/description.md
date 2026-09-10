`app/src/main/java/dev/halcamera/camera/` — two interchangeable implementations of one four-method interface, plus the characteristics helpers they share.

Both engines are kept on purpose. Camera2 is the measurement path, because it exposes `CaptureRequest` / `TotalCaptureResult` metadata directly and is what every metric definition is written against. CameraX is the comparison path: the same interface, the same telemetry callback (attached through `Camera2Interop.Extender`), so a difference in observed numbers can be attributed to the library rather than to the measurement code.

The interface is deliberately small — `start()`, `capture()`, `setZoom(ratio)`, `close(done)` — and its hardest contract is the close callback: it fires only once the engine has genuinely relinquished the camera, because the next open is chained off it.
