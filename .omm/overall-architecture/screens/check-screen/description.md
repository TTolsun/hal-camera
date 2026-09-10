`app/src/main/java/dev/halcamera/check/CheckActivity.kt` (319 lines). The screen that actually performs a measurement run.

It wires the pieces together: a `TextureView` preview, one `Camera2Engine`, a dedicated `FlightRecorder` tuned for a whole run (120 s retention, 60,000 events, `preNs = 0`, `postNs = 0` because there is no incident window here), an `AutoCheckRunner` it implements `Driver`, `Scheduler` and `Listener` for, then `CheckEvaluator` per endpoint and `HealthReport` to write the result.

It also collects the run environment the evaluation cannot see: thermal status through `PowerManager`, battery through `BatteryManager`, and the media performance class used for CDD gating. It holds the screen on with `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` for the duration.

The result is rendered in three layers, matching `PRODUCT-v0.2.md` 11.3: an L1 verdict, L2 evidence per endpoint, and L3 raw numbers behind a "상세 분석 보기" toggle. With `EXTRA_SHOW_LATEST` the whole camera path is skipped and a stored run is rendered instead; `EXTRA_RUN_FILE` is canonicalised and required to sit directly inside `files/checks`, so an arbitrary path cannot be rendered.
