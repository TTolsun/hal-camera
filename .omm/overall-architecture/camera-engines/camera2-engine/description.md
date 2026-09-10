`app/src/main/java/dev/halcamera/camera/Camera2Engine.kt` (246 lines). The measurement engine, and the one `CheckActivity` uses.

It runs everything on a private `HandlerThread` named `CD.Camera2` and posts UI work back to the main looper. On `start()` it registers the session, then opens as soon as the `TextureView`'s `SurfaceTexture` is available. `configure()` negotiates three streams by picking the largest size under a pixel budget: preview at up to 1280x720, YUV_420_888 analysis at up to 640x480, JPEG still at up to 1920x1080. The negotiated sizes are recorded into the session map and emitted as events, because a comparison between two runs is only meaningful once the streams match.

Zoom is applied two ways: `CONTROL_ZOOM_RATIO` clamped to the reported range on API 30 and above, and a centred `SCALER_CROP_REGION` crop on older devices, where only ratios of 1x and above exist. AF mode is `CONTINUOUS_PICTURE` when available, otherwise `OFF` — which is why an ultra-wide with no AF reports H.7 as UNSUPPORTED rather than as a failure.

Two details protect the measurements. A still capture that gets no image within 5 s emits `capture_timeout` and clears the in-flight flag, so the run continues rather than hanging. And `close()` sets `active = false` first, then drives session and device close, only calling `finishClose()` (which releases readers, emits `closed` and quits the thread) once; the caller's callback runs from there.

`transform()` handles the preview matrix. Its comment records the reason for the split: in portrait the pipeline already rotates and mirrors buffers, so only the stretch is undone, while in landscape the display rotation has to be applied explicitly.
