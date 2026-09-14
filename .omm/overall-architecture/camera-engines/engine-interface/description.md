`app/src/main/java/dev/halcamera/camera/CameraEngine.kt` (58 lines). The interface plus four top-level helpers that both engines and the endpoint resolver share.

`CameraEngine` declares `start()`, `capture()`, `setZoom(ratio)` and `close(done)`. Two comments in it carry contracts that matter elsewhere: the zoom argument is only a *requested* ratio, since the effective ratio is knowable only from capture results, and the `close` callback means the camera has been relinquished.

`zoomRange(manager, id)` reads `CONTROL_ZOOM_RATIO_RANGE` on API 30 and above and falls back to `1f..SCALER_AVAILABLE_MAX_DIGITAL_ZOOM` below, which is why sub-1x ultra-wide selection only exists on API 30+. `zoomPresets(range)` builds the stock-camera-style button row (ultra-wide, 1x, 2x, 3x, and a 5x or 10x step) clipped to what the device actually reports, capped at five entries.

`describeCamera(manager, id)` snapshots the characteristics that make a session interpretable later: hardware level, timestamp source and whether it is comparable to elapsed realtime, lens facing, orientation, capabilities, physical ids, zoom range, output sizes per format and target FPS ranges. It attaches an explicit note that per-format support does not imply the stream combination is supported. `Telemetry.registerSession` is the extension that stores that map and emits the first `open_requested` event.
