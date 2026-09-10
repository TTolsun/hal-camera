`app/src/main/java/dev/halcamera/MainActivity.kt` (566 lines), the largest file in the project and the original entry point before `HomeActivity` took over the launcher role.

It is the live instrument. It owns a `FlightRecorder` with the default incident settings (30 s retention, 10 s pre-trigger, 5 s post-trigger), hosts either engine behind the same `CameraEngine` interface (a `TextureView` for Camera2, a `PreviewView` for CameraX), draws zoom presets derived from `zoomPresets(zoomRange(...))`, runs `HealthMonitor` on the recorder's live tap to keep a health banner current, and exports an incident ZIP through `IncidentExporter` and `FileProvider`. It samples its own CPU time with `Process.getElapsedCpuTime()` for the diagnostics panel.

`EXTRA_CONSUMER` switches the wording to plain language and reduces the surface to the incident button and a summary, which is how the home screen reaches it.

This class is where the architecture's separation breaks down: view construction, permission flow, engine lifecycle, incident export and sampling all live in it, and none of it is unit tested.
