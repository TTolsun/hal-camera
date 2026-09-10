The three Activities, all built in Kotlin without XML layouts or Compose. Covers `home/HomeActivity.kt` (112 lines), `check/CheckActivity.kt` (319 lines), `MainActivity.kt` (566 lines) and the shared visual tokens in `ui/Look.kt`.

`HomeActivity` is the launcher. It is a consumer surface: light canvas, one blue action, and a status card rendered from the newest stored run through `home/RunSummary`. It never opens the camera itself; it asks for the CAMERA permission and starts `CheckActivity`.

`CheckActivity` runs the 60-second Auto Check. It owns a `Camera2Engine`, its own `FlightRecorder` (120 s retention, 60k events, no pre/post incident window) and an `AutoCheckRunner`, and renders three phases: guidance, running with preview and progress, and a three-layer result (verdict, evidence, raw). With `EXTRA_SHOW_LATEST` it renders a stored run without touching the camera at all.

`MainActivity` is the expert surface: live preview, engine switch between Camera2 and CameraX, zoom presets, the live health strip and the incident recorder. `EXTRA_CONSUMER` puts it into a plain-language incident mode reachable from Home.
