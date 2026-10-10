# Live controls and PIP callback validation

Validated on Galaxy S25+ (SM-S936N), Android 16 / API 36, on 2026-10-10.

## Local checks

- `testDebugUnitTest`: 730 tests passed, including physical/logical timestamp matching, service isolation, Hold duration cycling, zero-second live updates, and duration preservation after reset.
- `lintDebug` and signed `assembleRelease`: passed.
- Documentation regression tests: 33 passed.

## Device checks

- Camera2 Photo and Video; CameraX Photo and Video: expanded controls occupy the former Live/status row. Collapse restores the status row. Rapid toggles settle correctly.
- FPS, ISO, exposure, AE and AF fit one line. Physical ID is absent. Active PIP and Callback use yellow text without a filled background or bold weight.
- Camera2 Service 0 + Physical 6: Shutter, main Metadata, Meta (Phy), main Preview and Preview (Phy) all have matched frame timing. The device stamps the physical SurfaceTexture with the logical request timestamp; the graph supports this and physical sensor timestamps.
- Camera2 physical PIP and CameraX service PIP retain the configured 640x480 YUV analysis stream; its YUV row receives real image callbacks. Physical labels are Meta (Phy) and Preview (Phy); the composite photo is Jpeg.
- CameraX Service 0 + Service 1: main-service metadata and compositor input timing appear. The secondary service's independent result timeline is excluded.
- PIP photo capture in both engines: a 3-second Hold setting holds the photographed frame, includes the composite-image event, and resumes. At 0 seconds, capture does not hold the timeline.
- Time choices cycle 0, 1, 3, 5, 10, 0. Photo/Video and Camera2/CameraX changes clear PIP and close Callback while preserving the chosen duration. Installing an update also preserves the duration.
- Saved-photo feedback disappears automatically. Photos generated during validation remain in the device gallery.

Screenshots and image-free event ZIPs were reviewed locally. Camera screenshots include private surroundings and are not published in the repository.

## Review notes

Physical metadata is delivered with the main TotalCaptureResult, so its row uses that same callback receipt time. Compositor input and composite-photo events are app observations, not HAL processing or encoded-file completion times. Matching uses timestamp equality only; latency uses app monotonic timestamps. General CameraX preview buffers remain unobservable outside PIP. Multi modes reuse the PIP text styling; this change does not add a Callback graph to Multi.
