`app/src/main/java/dev/halcamera/ui/` — three custom `View` subclasses that draw telemetry directly onto a `Canvas`, plus the `Look` token file covered under Screens. No charting library is used.

`ScopeView` (83 lines) is the expert multi-track scope: it renders raw `Event`s as parallel tracks with vertical cursors marking incident triggers in red and anomalies in orange.

`StripView` (51 lines) is a ten-second sparkline of sensor frame intervals, drawn with two guides — the session baseline and the 1.5x stall threshold — so a stall is visible as a point crossing a line rather than as a number to read.

`TimelineView` (60 lines) implements `PRODUCT-v0.2.md` 12.2 item 3: two dot-lines showing the latest frame's START, PARTIAL and BUFFER offsets above the session-typical p50 offsets, so *where* in the pipeline a delay sits is visible without reading numbers.

All three follow the palette rule from `Look`: trace colours are blue shades, and the status palette (red, orange) is reserved for marking anomalies.
